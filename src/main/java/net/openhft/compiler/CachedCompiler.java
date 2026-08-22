/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import static net.openhft.compiler.CompilerUtils.*;

/**
 * Manages in-memory compilation with an optional cache. When directories are
 * supplied to the constructors, source and class files are also written to
 * disk to aid debugging. Call {@link #close()} once finished and use
 * {@link #updateFileManagerForClassLoader(ClassLoader, java.util.function.Consumer)}
 * to tune a specific loader.
 */
public class CachedCompiler implements Closeable {
    /**
     * Logger for compilation activity.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CachedCompiler.class);
    /**
     * Writer used when no alternative is supplied.
     */
    private static final PrintWriter DEFAULT_WRITER = createDefaultWriter();
    /**
     * Default compiler flags including debug symbols.
     */
    private static final List<String> DEFAULT_OPTIONS = Arrays.asList("-g", "-nowarn");
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("[\\p{Alnum}_$.\\-]+");
    private static final Pattern CLASS_NAME_SEGMENT_PATTERN = Pattern.compile("[\\p{Alnum}_$]+(?:-[\\p{Alnum}_$]+)*");

    private final Map<ClassLoader, Map<String, Class<?>>> loadedClassesMap = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<ClassLoader, MyJavaFileManager> fileManagerMap = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<ClassLoader, Map<String, Object>> definitionLocks = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<ClassLoader, Set<String>> incompleteLookupDefinitions = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Optional testing hook to replace the file manager implementation.
     * <p>
     * This field remains {@code public} to preserve binary compatibility with callers that
     * accessed it directly in previous releases. Prefer {@link #setFileManagerOverride(Function)}
     * for source-compatible code.
     */
    @SuppressWarnings("WeakerAccess")
    public volatile Function<StandardJavaFileManager, MyJavaFileManager> fileManagerOverride;

    @Nullable
    private final File sourceDir;
    @Nullable
    private final File classDir;
    @NotNull
    private final List<String> options;

    private final ConcurrentMap<String, JavaFileObject> javaFileObjects = new ConcurrentHashMap<>();

    /**
     * Create a compiler that optionally writes sources and classes to the given
     * directories. When {@code sourceDir} or {@code classDir} is not null, the
     * corresponding files are written for debugging purposes.
     */
    public CachedCompiler(@Nullable File sourceDir, @Nullable File classDir) {
        this(sourceDir, classDir, DEFAULT_OPTIONS);
    }

    /**
     * Create a compiler with explicit compiler options. Directories behave as in
     * {@link #CachedCompiler(File, File)} and allow inspection of generated
     * output.
     *
     * @param sourceDir where sources are dumped when not null
     * @param classDir  where class files are dumped when not null
     * @param options   additional flags passed to the Java compiler
     */
    public CachedCompiler(@Nullable File sourceDir,
                          @Nullable File classDir,
                          @NotNull List<String> options) {
        this.sourceDir = sourceDir;
        this.classDir = classDir;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
    }

    /**
     * Close any file managers created by this compiler.
     * Normally called when the instance is discarded.
     */
    public void close() {
        try {
            for (MyJavaFileManager fileManager : fileManagerMap.values()) {
                fileManager.close();
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Compile the supplied source and load the class using this instance's
     * class loader. Successfully compiled classes are cached for reuse.
     *
     * @param className expected binary name of the class
     * @param javaCode  source code to compile
     * @return the loaded class instance
     * @throws ClassNotFoundException if the compiled class cannot be defined
     */
    public Class<?> loadFromJava(@NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        validateClassName(className);
        return loadFromJava(getClass().getClassLoader(), className, javaCode, DEFAULT_WRITER);
    }

    /**
     * Compile the source using the supplied class loader. Cached classes are
     * stored per loader key.
     *
     * @param classLoader loader to define the class with
     * @param className   expected binary name
     * @param javaCode    source code to compile
     * @return the loaded class instance
     * @throws ClassNotFoundException if definition fails
     */
    public Class<?> loadFromJava(@NotNull ClassLoader classLoader,
                                 @NotNull String className,
                                 @NotNull String javaCode) throws ClassNotFoundException {
        validateClassName(className);
        return loadFromJava(classLoader, className, javaCode, DEFAULT_WRITER);
    }

    /**
     * Compile the source and define it using the <em>anchor/lookup</em> strategy (issue #91):
     * the compiled classes are defined in the package and {@link ClassLoader} of the supplied
     * {@code anchor} via {@link CompilerUtils#defineClass(MethodHandles.Lookup, byte[])}, using
     * no {@code sun.misc.Unsafe}. The primary class must be declared in the same package as
     * {@code anchor.lookupClass()}; the JDK rejects a cross-package definition.
     * <p>
     * This is the opt-in counterpart to {@link #loadFromJava(ClassLoader, String, String)}: use
     * it when the caller controls the destination package and can hand over a full-privilege
     * {@code Lookup}; use the class-loader overload for arbitrary package names via a
     * compiler-owned loader.
     *
     * @param anchor    a {@code Lookup} with full privileges in the destination package.
     * @param className expected binary name of the primary class (in the anchor's package).
     * @param javaCode  source code to compile.
     * @return the loaded class, defined in the anchor's loader.
     * @throws ClassNotFoundException        if the compiled class cannot be found after definition.
     * @throws IllegalArgumentException      if the lookup lacks package access or names another package.
     * @throws UnsupportedOperationException on Java&nbsp;8, where anchor mode is unavailable.
     */
    public Class<?> loadFromJava(@NotNull MethodHandles.Lookup anchor,
                                 @NotNull String className,
                                 @NotNull String javaCode) throws ClassNotFoundException {
        Objects.requireNonNull(anchor, "anchor");
        validateClassName(className);
        if (!CompilerUtils.isAnchorDefineClassSupported())
            throw new UnsupportedOperationException(
                    "anchor/lookup class definition requires Java 9+ (MethodHandles.Lookup#defineClass)");
        if ((anchor.lookupModes() & MethodHandles.Lookup.PACKAGE) == 0)
            throw new IllegalArgumentException("anchor Lookup must have PACKAGE access");
        final String anchorPackage = packageName(anchor.lookupClass().getName());
        if (!anchorPackage.equals(packageName(className)))
            throw new IllegalArgumentException("class " + className + " is not in anchor package " + anchorPackage);

        final ClassLoader classLoader = anchor.lookupClass().getClassLoader();
        final Map<String, Class<?>> loadedClasses = loadedClassesFor(classLoader);
        synchronized (definitionLock(classLoader, className)) {
            Class<?> primaryClass = loadedClass(loadedClasses, className);
            if (primaryClass != null && !isLookupDefinitionIncomplete(classLoader, className))
                return primaryClass;

            final MyJavaFileManager fileManager = fileManagerFor(classLoader);
            final Map<String, byte[]> compiled = compileFromJava(className, javaCode, DEFAULT_WRITER, fileManager);
            if (!compiled.containsKey(className))
                throw new ClassNotFoundException(className);

            markLookupDefinitionIncomplete(classLoader, className);
            primaryClass = defineCompiledWithLookup(anchor, className, compiled, loadedClasses);
            markLookupDefinitionComplete(classLoader, className);
            return primaryClass;
        }
    }

    /**
     * Compile source code into byte arrays using the provided file manager.
     * Results are cached and reused on subsequent calls when compilation
     * succeeds.
     *
     * @param className   name of the primary class
     * @param javaCode    source to compile
     * @param fileManager manager responsible for storing the compiled output
     * @return map of class names to compiled bytecode
     */
    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className,
                                        @NotNull String javaCode,
                                        MyJavaFileManager fileManager) {
        validateClassName(className);
        return compileFromJava(className, javaCode, DEFAULT_WRITER, fileManager);
    }

    /**
     * Compile source using the given writer and file manager. The resulting
     * byte arrays are cached for the life of this compiler instance, while the returned map
     * contains only class names first produced by this compilation.
     *
     * @param className   name of the primary class
     * @param javaCode    source to compile
     * @param writer      destination for diagnostic output
     * @param fileManager file manager used to collect compiled classes
     * @return map of class names to compiled bytecode
     */
    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className,
                                        @NotNull String javaCode,
                                        final @NotNull PrintWriter writer,
                                        MyJavaFileManager fileManager) {
        validateClassName(className);
        synchronized (fileManager) {
            final List<JavaFileObject> currentCompilationUnits = new ArrayList<>();
            Iterable<? extends JavaFileObject> compilationUnits;
            if (sourceDir != null) {
                String filename = className.replaceAll("\\.", '\\' + File.separator) + ".java";
                File file = safeResolve(sourceDir, filename);
                writeText(file, javaCode);
                if (s_standardJavaFileManager == null)
                    s_standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
                for (JavaFileObject compilationUnit : s_standardJavaFileManager.getJavaFileObjects(file))
                    currentCompilationUnits.add(compilationUnit);
                compilationUnits = currentCompilationUnits;

            } else {
                JavaFileObject currentCompilationUnit = new JavaSourceFromString(className, javaCode);
                javaFileObjects.put(className, currentCompilationUnit);
                currentCompilationUnits.add(currentCompilationUnit);
                compilationUnits = new ArrayList<>(javaFileObjects.values()); // To prevent CME from compiler code
            }
            fileManager.prepareForCompilation(currentCompilationUnits);
            // Reuse the same file manager to cache jar files. Serialisation also prevents
            // concurrent compiler tasks from interleaving writes into its output buffers.
            boolean ok = s_compiler.getTask(writer, fileManager, new DiagnosticListener<JavaFileObject>() {
                @Override
                public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        writer.println(diagnostic);
                    }
                }
            }, options, null, compilationUnits).call();

            if (!ok) {
                // compilation error, so we want to exclude this file from future compilation passes
                if (sourceDir == null)
                    javaFileObjects.remove(className);

                return Collections.emptyMap();
            }
            return fileManager.getBuffersForSources(currentCompilationUnits);
        }
    }


    /**
     * Compile and load using a specific class loader and writer. The
     * compilation result is cached against the loader for future calls.
     *
     * @param classLoader loader to define the class with
     * @param className   expected binary name
     * @param javaCode    source code to compile
     * @param writer      destination for diagnostic messages, may be null
     * @return the loaded class instance
     * @throws ClassNotFoundException if definition fails
     */
    public Class<?> loadFromJava(@NotNull ClassLoader classLoader,
                                 @NotNull String className,
                                 @NotNull String javaCode,
                                 @Nullable PrintWriter writer) throws ClassNotFoundException {
        Class<?> clazz = null;
        Map<String, Class<?>> loadedClasses;
        synchronized (loadedClassesMap) {
            loadedClasses = loadedClassesMap.get(classLoader);
            if (loadedClasses == null)
                loadedClassesMap.put(classLoader, loadedClasses = new LinkedHashMap<>());
            else
                clazz = loadedClasses.get(className);
        }
        PrintWriter printWriter = writer == null ? DEFAULT_WRITER : writer;
        if (clazz != null)
            return clazz;

        synchronized (definitionLock(classLoader, className)) {
            synchronized (loadedClassesMap) {
                clazz = loadedClasses.get(className);
            }
            if (clazz != null)
                return clazz;

            MyJavaFileManager fileManager = fileManagerFor(classLoader);
            final Map<String, byte[]> compiled = compileFromJava(className, javaCode, printWriter, fileManager);
            for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
                String className2 = entry.getKey();
                validateClassName(className2);
                byte[] bytes = entry.getValue();
                if (classDir != null) {
                    String filename = className2.replaceAll("\\.", '\\' + File.separator) + ".class";
                    boolean changed = writeBytes(safeResolve(classDir, filename), bytes);
                    if (changed) {
                        LOG.info("Updated {} in {}", className2, classDir);
                    }
                }

                synchronized (loadedClassesMap) {
                    if (loadedClasses.containsKey(className2))
                        continue;
                    Class<?> clazz2 = CompilerUtils.defineClass(classLoader, className2, bytes);
                    loadedClasses.put(className2, clazz2);
                }
            }
            synchronized (loadedClassesMap) {
                loadedClasses.put(className, clazz = classLoader.loadClass(className));
            }
            markLookupDefinitionComplete(classLoader, className);
            return clazz;
        }
    }

    /**
     * Update the file manager for a specific class loader. This is mainly a
     * testing utility and is ignored when no manager exists for the loader.
     *
     * @param classLoader       the class loader to update
     * @param updateFileManager function applying the update
     */
    public void updateFileManagerForClassLoader(ClassLoader classLoader, Consumer<MyJavaFileManager> updateFileManager) {
        MyJavaFileManager fileManager = fileManagerMap.get(classLoader);
        if (fileManager != null) {
            updateFileManager.accept(fileManager);
        }
    }

    public void setFileManagerOverride(Function<StandardJavaFileManager, MyJavaFileManager> fileManagerOverride) {
        this.fileManagerOverride = fileManagerOverride;
    }

    private @NotNull MyJavaFileManager getFileManager(StandardJavaFileManager fm) {
        return fileManagerOverride != null
                ? fileManagerOverride.apply(fm)
                : new MyJavaFileManager(fm);
    }

    private MyJavaFileManager fileManagerFor(ClassLoader classLoader) {
        synchronized (fileManagerMap) {
            MyJavaFileManager fileManager = fileManagerMap.get(classLoader);
            if (fileManager == null) {
                StandardJavaFileManager standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
                fileManager = getFileManager(standardJavaFileManager);
                fileManagerMap.put(classLoader, fileManager);
            }
            return fileManager;
        }
    }

    private Map<String, Class<?>> loadedClassesFor(ClassLoader classLoader) {
        synchronized (loadedClassesMap) {
            Map<String, Class<?>> loadedClasses = loadedClassesMap.get(classLoader);
            if (loadedClasses == null) {
                loadedClasses = new LinkedHashMap<>();
                loadedClassesMap.put(classLoader, loadedClasses);
            }
            return loadedClasses;
        }
    }

    private Object definitionLock(ClassLoader classLoader, String className) {
        synchronized (definitionLocks) {
            Map<String, Object> loaderLocks = definitionLocks.get(classLoader);
            if (loaderLocks == null) {
                loaderLocks = new HashMap<>();
                definitionLocks.put(classLoader, loaderLocks);
            }
            Object lock = loaderLocks.get(className);
            if (lock == null) {
                lock = new Object();
                loaderLocks.put(className, lock);
            }
            return lock;
        }
    }

    private Class<?> loadedClass(Map<String, Class<?>> loadedClasses, String className) {
        synchronized (loadedClassesMap) {
            return loadedClasses.get(className);
        }
    }

    private Class<?> defineWithLookup(MethodHandles.Lookup anchor,
                                      String className,
                                      byte[] bytes,
                                      Map<String, Class<?>> loadedClasses) {
        validateClassName(className);
        synchronized (loadedClassesMap) {
            Class<?> loaded = loadedClasses.get(className);
            if (loaded != null)
                return loaded;
            Class<?> defined = CompilerUtils.defineClass(anchor, bytes);
            loadedClasses.put(className, defined);
            return defined;
        }
    }

    /**
     * Define a compilation batch while allowing same-source supertypes to be emitted in any order.
     * A failed batch remains marked incomplete so a later call cannot mistake a partially defined
     * primary class for a successful cached load.
     */
    private Class<?> defineCompiledWithLookup(MethodHandles.Lookup anchor,
                                              String primaryClassName,
                                              Map<String, byte[]> compiled,
                                              Map<String, Class<?>> loadedClasses) {
        final Map<String, byte[]> pending = new LinkedHashMap<>(compiled);
        Class<?> primaryClass = null;

        while (!pending.isEmpty()) {
            boolean madeProgress = false;
            NoClassDefFoundError unresolvedDependency = null;
            for (Iterator<Map.Entry<String, byte[]>> iterator = pending.entrySet().iterator(); iterator.hasNext(); ) {
                final Map.Entry<String, byte[]> entry = iterator.next();
                try {
                    final Class<?> defined = defineWithLookup(anchor, entry.getKey(), entry.getValue(), loadedClasses);
                    if (entry.getKey().equals(primaryClassName))
                        primaryClass = defined;
                    iterator.remove();
                    madeProgress = true;
                } catch (NoClassDefFoundError unresolved) {
                    // Lookup#defineClass resolves direct supertypes immediately. Another output
                    // from this batch may provide the missing class, so retry after making a pass.
                    unresolvedDependency = unresolved;
                }
            }
            if (!madeProgress) {
                if (unresolvedDependency != null)
                    throw unresolvedDependency;
                throw new LinkageError("Unable to define compiled classes for " + primaryClassName);
            }
        }

        if (primaryClass == null)
            primaryClass = loadedClass(loadedClasses, primaryClassName);
        if (primaryClass == null)
            throw new LinkageError("Primary class was not defined: " + primaryClassName);
        return primaryClass;
    }

    private boolean isLookupDefinitionIncomplete(ClassLoader classLoader, String className) {
        synchronized (incompleteLookupDefinitions) {
            final Set<String> incomplete = incompleteLookupDefinitions.get(classLoader);
            return incomplete != null && incomplete.contains(className);
        }
    }

    private void markLookupDefinitionIncomplete(ClassLoader classLoader, String className) {
        synchronized (incompleteLookupDefinitions) {
            Set<String> incomplete = incompleteLookupDefinitions.get(classLoader);
            if (incomplete == null) {
                incomplete = new HashSet<>();
                incompleteLookupDefinitions.put(classLoader, incomplete);
            }
            incomplete.add(className);
        }
    }

    private void markLookupDefinitionComplete(ClassLoader classLoader, String className) {
        synchronized (incompleteLookupDefinitions) {
            final Set<String> incomplete = incompleteLookupDefinitions.get(classLoader);
            if (incomplete == null)
                return;
            incomplete.remove(className);
            if (incomplete.isEmpty())
                incompleteLookupDefinitions.remove(classLoader);
        }
    }

    private static String packageName(String className) {
        final int separator = className.lastIndexOf('.');
        return separator < 0 ? "" : className.substring(0, separator);
    }

    private static void validateClassName(String className) {
        Objects.requireNonNull(className, "className");
        if (!CLASS_NAME_PATTERN.matcher(className).matches()) {
            throw new IllegalArgumentException("Invalid class name: " + className);
        }
        for (String segment : className.split("\\.", -1)) {
            if (!CLASS_NAME_SEGMENT_PATTERN.matcher(segment).matches()) {
                throw new IllegalArgumentException("Invalid class name: " + className);
            }
        }
    }

    static File safeResolve(File root, String relativePath) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(relativePath, "relativePath");
        Path base = root.toPath().toAbsolutePath().normalize();
        Path candidate = base.resolve(relativePath).normalize();
        if (!candidate.startsWith(base)) {
            throw new IllegalArgumentException("Attempted path traversal for " + relativePath);
        }
        return candidate.toFile();
    }

    private static PrintWriter createDefaultWriter() {
        OutputStreamWriter writer = new OutputStreamWriter(System.err, StandardCharsets.UTF_8);
        return new PrintWriter(writer, true) {
            @Override
            public void close() {
                flush(); // never close System.err
            }
        };
    }
}
