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
     *
     * @param sourceDir directory to emit sources to, or {@code null} to keep in memory
     * @param classDir  directory to emit class files to, or {@code null} to keep in memory
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
    @Override
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
     * byte arrays are cached for the life of this compiler instance.
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
        Iterable<? extends JavaFileObject> compilationUnits;
        if (sourceDir != null) {
            String filename = className.replaceAll("\\.", '\\' + File.separator) + ".java";
            File file = safeResolve(sourceDir, filename);
            writeText(file, javaCode);
            StandardJavaFileManager standardJavaFileManager = s_standardJavaFileManager;
            if (standardJavaFileManager == null) {
                synchronized (CompilerUtils.class) {
                    standardJavaFileManager = s_standardJavaFileManager;
                    if (standardJavaFileManager == null) {
                        standardJavaFileManager = currentCompiler().getStandardFileManager(null, null, null);
                        s_standardJavaFileManager = standardJavaFileManager;
                    }
                }
            }
            compilationUnits = standardJavaFileManager.getJavaFileObjects(file);

        } else {
            javaFileObjects.put(className, new JavaSourceFromString(className, javaCode));
            compilationUnits = new ArrayList<>(javaFileObjects.values()); // To prevent CME from compiler code
        }
        // reuse the same file manager to allow caching of jar files
        boolean ok = currentCompiler().getTask(writer, fileManager, new DiagnosticListener<JavaFileObject>() {
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

            // nothing to return due to compiler error
            return Collections.emptyMap();
        } else {

            return fileManager.getAllBuffers();
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

        MyJavaFileManager fileManager = fileManagerMap.get(classLoader);
        if (fileManager == null) {
            StandardJavaFileManager standardJavaFileManager = currentCompiler().getStandardFileManager(null, null, null);
            fileManager = getFileManager(standardJavaFileManager);
            fileManagerMap.put(classLoader, fileManager);
        }
        final Map<String, byte[]> compiled = compileFromJava(className, javaCode, printWriter, fileManager);
        for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
            String className2 = entry.getKey();
            validateClassName(className2);
            synchronized (loadedClassesMap) {
                if (loadedClasses.containsKey(className2))
                    continue;
            }
            byte[] bytes = entry.getValue();
            if (classDir != null) {
                String filename = className2.replaceAll("\\.", '\\' + File.separator) + ".class";
                boolean changed = writeBytes(safeResolve(classDir, filename), bytes);
                if (changed) {
                    LOG.info("Updated {} in {}", className2, classDir);
                }
            }

            synchronized (className2.intern()) { // To prevent duplicate class definition error
                synchronized (loadedClassesMap) {
                    if (loadedClasses.containsKey(className2))
                        continue;
                }

                Class<?> clazz2 = CompilerUtils.defineClass(classLoader, className2, bytes);
                synchronized (loadedClassesMap) {
                    loadedClasses.put(className2, clazz2);
                }
            }
        }
        synchronized (loadedClassesMap) {
            loadedClasses.put(className, clazz = classLoader.loadClass(className));
        }
        return clazz;
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

    /**
     * Overrides the file manager creation hook used during compilation.
     *
     * @param fileManagerOverride factory mapping the standard manager to a custom one
     */
    public void setFileManagerOverride(Function<StandardJavaFileManager, MyJavaFileManager> fileManagerOverride) {
        this.fileManagerOverride = fileManagerOverride;
    }

    private @NotNull MyJavaFileManager getFileManager(StandardJavaFileManager fm) {
        return fileManagerOverride != null
                ? fileManagerOverride.apply(fm)
                : new MyJavaFileManager(fm);
    }

    static void validateClassName(String className) {
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

    static PrintWriter createDefaultWriter() {
        OutputStreamWriter writer = new OutputStreamWriter(System.err, StandardCharsets.UTF_8);
        return new PrintWriter(writer, true) {
            @Override
            public void close() {
                flush(); // never close System.err
            }
        };
    }
}
