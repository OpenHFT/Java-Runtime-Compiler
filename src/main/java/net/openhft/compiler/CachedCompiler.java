/*
 * Copyright 2014 Higher Frequency Trading
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.openhft.compiler.CompilerUtils.*;

@SuppressWarnings("StaticNonFinalField")
public class CachedCompiler implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(CachedCompiler.class);
    /** Writes to {@link System#err} */
    private static final PrintWriter DEFAULT_WRITER = new PrintWriter(System.err);
    private static final List<String> DEFAULT_OPTIONS = Arrays.asList("-g", "-nowarn");

    private final Map<ClassLoader, Map<String, Class<?>>> loadedClassesMap = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<ClassLoader, MyJavaFileManager> fileManagerMap = Collections.synchronizedMap(new WeakHashMap<>());
    public Function<StandardJavaFileManager, MyJavaFileManager> fileManagerOverride;

    @Nullable
    private final File sourceDir;
    @Nullable
    private final File classDir;
    @NotNull
    private final List<String> options;

    private final ConcurrentMap<String, JavaFileObject> javaFileObjects = new ConcurrentHashMap<>();

    /**
     * Delegates to {@link #CachedCompiler(File, File, List)} with default {@code javac} compilation
     * options {@code -g} (generate debug information) and {@code -nowarn}.
     */
    public CachedCompiler(@Nullable File sourceDir, @Nullable File classDir) {
        this(sourceDir, classDir, DEFAULT_OPTIONS);
    }

    /**
     * @param sourceDir where to write {@code .java} source code files to be compiled; {@code null}
     *      to not write them to the file system
     * @param classDir where to write compiled {@code .class} files; {@code null} to not write them
     *      to the file system
     * @param options {@code javac} compilation options
     */
    public CachedCompiler(@Nullable File sourceDir, @Nullable File classDir, @NotNull List<String> options) {
        this.sourceDir = sourceDir;
        this.classDir = classDir;
        this.options = options;
    }

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
     * Delegates to {@link #loadFromJava(ClassLoader, String, String, PrintWriter, DiagnosticListener)}.
     * <ul>
     *   <li>The class loader of {@link CachedCompiler} is used for defining and loading the class
     *   <li>Only error diagnostics are collected, and are written to {@link System#err}
     * </ul>
     */
    public Class<?> loadFromJava(@NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(getClass().getClassLoader(), className, javaCode, DEFAULT_WRITER);
    }

    /**
     * Delegates to {@link #loadFromJava(ClassLoader, String, String, PrintWriter, DiagnosticListener)}.
     * Only error diagnostics are collected, and are written to {@link System#err}.
     */
    public Class<?> loadFromJava(@NotNull ClassLoader classLoader,
                              @NotNull String className,
                              @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(classLoader, className, javaCode, DEFAULT_WRITER);
    }

    /**
     * Delegates to {@link #loadFromJava(ClassLoader, String, String, PrintWriter, DiagnosticListener)}.
     * Only error diagnostics are collected, and are written to {@code writer}.
     */
    public Class<?> loadFromJava(@NotNull ClassLoader classLoader,
            @NotNull String className,
            @NotNull String javaCode,
            @Nullable PrintWriter writer) throws ClassNotFoundException {
        return loadFromJava(classLoader, className, javaCode, writer, null);
    }

    /**
     * Gets a previously compiled and loaded class, or compiles the given Java code and
     * loads the class.
     *
     * @param classLoader class loader for defining and loading the class
     * @param className binary name of the class to load, for example {@code com.example.MyClass$Nested}
     * @param javaCode Java code to compile, in case the class had not been compiled and loaded before
     * @param writer writer for compilation information and diagnostics (should be thread-safe);
     *      when {@code null} defaults to writing to {@link System#err}
     * @param diagnosticListener listener for diagnostics emitted by the compiler (should be thread-safe);
     *      when {@code null}, error diagnostics are written to the {@code writer}, other diagnostics are ignored
     * @return the loaded class
     * @throws ClassNotFoundException if compiling or loading the class failed; inspect {@code writer} or
     *      {@code diagnosticListener} for additional details
     */
    public Class<?> loadFromJava(@NotNull ClassLoader classLoader,
                              @NotNull String className,
                              @NotNull String javaCode,
                              @Nullable PrintWriter writer,
                              @Nullable DiagnosticListener<? super JavaFileObject> diagnosticListener) throws ClassNotFoundException {
        Class<?> clazz = null;
        Map<String, Class<?>> loadedClasses;
        synchronized (loadedClassesMap) {
            loadedClasses = loadedClassesMap.get(classLoader);
            if (loadedClasses == null)
                loadedClassesMap.put(classLoader, loadedClasses = new LinkedHashMap<>());
            else
                clazz = loadedClasses.get(className);
        }

        if (clazz != null)
            return clazz;

        final PrintWriter writerFinal = writer == null ? DEFAULT_WRITER : writer;
        final DiagnosticListener<? super JavaFileObject> diagnosticListenerFinal;
        if (diagnosticListener == null) {
            diagnosticListenerFinal = new DiagnosticListener<JavaFileObject>() {
                @Override
                public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        writerFinal.println(diagnostic);
                    }
                }
            };
        } else {
            diagnosticListenerFinal = diagnosticListener;
        }

        List<Diagnostic<?>> errorDiagnostics = Collections.synchronizedList(new ArrayList<>());
        DiagnosticListener<JavaFileObject> wrappingDiagnosticListener = new DiagnosticListener<JavaFileObject>() {
            @Override
            public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errorDiagnostics.add(diagnostic);
                }
                diagnosticListenerFinal.report(diagnostic);
            }
        };

        MyJavaFileManager fileManager = fileManagerMap.get(classLoader);
        if (fileManager == null) {
            StandardJavaFileManager standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
            fileManager = getFileManager(standardJavaFileManager);
            fileManagerMap.put(classLoader, fileManager);
        }
        final Map<String, byte[]> compiled = compileFromJava(className, javaCode, writerFinal, wrappingDiagnosticListener, fileManager);
        for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
            String className2 = entry.getKey();
            synchronized (loadedClassesMap) {
                if (loadedClasses.containsKey(className2))
                    continue;
            }
            byte[] bytes = entry.getValue();
            if (classDir != null) {
                String filename = className2.replaceAll("\\.", '\\' + File.separator) + ".class";
                boolean changed = writeBytes(new File(classDir, filename), bytes);
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
            try {
                clazz = classLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                if (errorDiagnostics.isEmpty()) {
                    throw e;
                }

                // Enhance exception message with compilation errors, otherwise it will might not
                // be obvious that reason why loading failed is because compilation failed
                String message = "Failed to load class " + className + "\nCompilation errors:\n";
                message += errorDiagnostics.stream().map(Diagnostic::toString).collect(Collectors.joining("\n"));
                throw new ClassNotFoundException(message, e);
            }
            loadedClasses.put(className, clazz);
        }
        return clazz;
    }

    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className,
                                        @NotNull String javaCode,
                                        @NotNull PrintWriter writer,
                                        @NotNull DiagnosticListener<? super JavaFileObject> diagnosticListener,
                                        MyJavaFileManager fileManager) {
        Iterable<? extends JavaFileObject> compilationUnits;
        if (sourceDir != null) {
            String filename = className.replaceAll("\\.", '\\' + File.separator) + ".java";
            File file = new File(sourceDir, filename);
            writeText(file, javaCode);
            if (s_standardJavaFileManager == null)
                s_standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
            compilationUnits = s_standardJavaFileManager.getJavaFileObjects(file);

        } else {
            javaFileObjects.put(className, new JavaSourceFromString(className, javaCode));
            compilationUnits = new ArrayList<>(javaFileObjects.values()); // To prevent CME from compiler code
        }
        // reuse the same file manager to allow caching of jar files
        boolean ok = s_compiler.getTask(writer, fileManager, diagnosticListener, options, null, compilationUnits).call();

        if (!ok) {
            // compilation error, so we want to exclude this file from future compilation passes
            if (sourceDir == null)
                javaFileObjects.remove(className);

            // nothing to return due to compiler error
            return Collections.emptyMap();
        } else {
            Map<String, byte[]> result = fileManager.getAllBuffers();

            return result;
        }
    }

    private @NotNull MyJavaFileManager getFileManager(StandardJavaFileManager fm) {
        return fileManagerOverride != null
                ? fileManagerOverride.apply(fm)
                : new MyJavaFileManager(fm);
    }
}
