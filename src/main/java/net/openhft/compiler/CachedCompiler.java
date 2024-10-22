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

import static net.openhft.compiler.CompilerUtils.*;

/**
 * This class handles the caching and compilation of Java source code.
 * It maintains a cache of loaded classes and provides methods to compile
 * Java source code and load classes dynamically.
 */
@SuppressWarnings("StaticNonFinalField")
public class CachedCompiler implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(CachedCompiler.class);
    private static final PrintWriter DEFAULT_WRITER = new PrintWriter(System.err);
    private static final List<String> DEFAULT_OPTIONS = Arrays.asList("-g", "-nowarn");

    // Map to store loaded classes, synchronized to handle concurrent access
    private final Map<ClassLoader, Map<String, Class<?>>> loadedClassesMap = Collections.synchronizedMap(new WeakHashMap<>());
    // Map to store file managers, synchronized to handle concurrent access
    private final Map<ClassLoader, MyJavaFileManager> fileManagerMap = Collections.synchronizedMap(new WeakHashMap<>());
    public Function<StandardJavaFileManager, MyJavaFileManager> fileManagerOverride;

    @Nullable
    private final File sourceDir; // Directory for source files
    @Nullable
    private final File classDir;  // Directory for class files
    @NotNull
    private final List<String> options; // Compilation options

    // Map to store Java file objects for compilation
    private final ConcurrentMap<String, JavaFileObject> javaFileObjects = new ConcurrentHashMap<>();

    /**
     * Constructor to initialize CachedCompiler with source and class directories, and default options.
     *
     * @param sourceDir The directory for source files
     * @param classDir  The directory for class files
     */
    public CachedCompiler(@Nullable File sourceDir, @Nullable File classDir) {
        this(sourceDir, classDir, DEFAULT_OPTIONS);
    }

    /**
     * Constructor to initialize CachedCompiler with source and class directories, and custom options.
     *
     * @param sourceDir The directory for source files
     * @param classDir  The directory for class files
     * @param options   The compilation options
     */
    public CachedCompiler(@Nullable File sourceDir, @Nullable File classDir, @NotNull List<String> options) {
        this.sourceDir = sourceDir;
        this.classDir = classDir;
        this.options = options;
    }

    /**
     * Closes the CachedCompiler and releases resources associated with the file managers.
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
     * Loads a class from the provided Java source code.
     *
     * @param className The name of the class to be loaded
     * @param javaCode  The Java source code of the class
     * @return The loaded class
     * @throws ClassNotFoundException if the class cannot be found
     */
    public Class<?> loadFromJava(@NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(getClass().getClassLoader(), className, javaCode, DEFAULT_WRITER);
    }

    /**
     * Loads a class from the provided Java source code using the specified class loader.
     *
     * @param classLoader The class loader to be used
     * @param className   The name of the class to be loaded
     * @param javaCode    The Java source code of the class
     * @return The loaded class
     * @throws ClassNotFoundException if the class cannot be found
     */
    public Class<?> loadFromJava(@NotNull ClassLoader classLoader,
                                 @NotNull String className,
                                 @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(classLoader, className, javaCode, DEFAULT_WRITER);
    }

    /**
     * Compiles the provided Java source code and returns a map of class names to byte arrays.
     *
     * @param className   The name of the class to be compiled
     * @param javaCode    The Java source code of the class
     * @param fileManager The file manager to be used for compilation
     * @return A map of class names to byte arrays
     */
    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className, @NotNull String javaCode, MyJavaFileManager fileManager) {
        return compileFromJava(className, javaCode, DEFAULT_WRITER, fileManager);
    }

    /**
     * Compiles the provided Java source code and returns a map of class names to byte arrays.
     *
     * @param className   The name of the class to be compiled
     * @param javaCode    The Java source code of the class
     * @param writer      The PrintWriter to be used for logging
     * @param fileManager The file manager to be used for compilation
     * @return A map of class names to byte arrays
     */
    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className,
                                        @NotNull String javaCode,
                                        final @NotNull PrintWriter writer,
                                        MyJavaFileManager fileManager) {
        Iterable<? extends JavaFileObject> compilationUnits;
        if (sourceDir != null) {
            // Write source file to disk if sourceDir is specified
            String filename = className.replaceAll("\\.", '\\' + File.separator) + ".java";
            File file = new File(sourceDir, filename);
            writeText(file, javaCode);
            if (s_standardJavaFileManager == null)
                s_standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
            compilationUnits = s_standardJavaFileManager.getJavaFileObjects(file);

        } else {
            // Use in-memory Java file object if sourceDir is not specified
            javaFileObjects.put(className, new JavaSourceFromString(className, javaCode));
            compilationUnits = new ArrayList<>(javaFileObjects.values()); // To prevent CME from compiler code
        }
        // Reuse the same file manager to allow caching of jar files
        boolean ok = s_compiler.getTask(writer, fileManager, new DiagnosticListener<JavaFileObject>() {
            @Override
            public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    writer.println(diagnostic);
                }
            }
        }, options, null, compilationUnits).call();

        if (!ok) {
            // Compilation error, so we want to exclude this file from future compilation passes
            if (sourceDir == null)
                javaFileObjects.remove(className);

            // Nothing to return due to compiler error
            return Collections.emptyMap();
        } else {
            // Return compiled class byte arrays
            Map<String, byte[]> result = fileManager.getAllBuffers();

            return result;
        }
    }

    /**
     * Loads a class from the provided Java source code using the specified class loader and writer.
     *
     * @param classLoader The class loader to be used
     * @param className   The name of the class to be loaded
     * @param javaCode    The Java source code of the class
     * @param writer      The PrintWriter to be used for logging
     * @return The loaded class
     * @throws ClassNotFoundException if the class cannot be found
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
        PrintWriter printWriter = (writer == null ? DEFAULT_WRITER : writer);
        if (clazz != null)
            return clazz;

        MyJavaFileManager fileManager = fileManagerMap.get(classLoader);
        if (fileManager == null) {
            StandardJavaFileManager standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
            fileManager = getFileManager(standardJavaFileManager);
            fileManagerMap.put(classLoader, fileManager);
        }
        final Map<String, byte[]> compiled = compileFromJava(className, javaCode, printWriter, fileManager);
        for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
            String className2 = entry.getKey();
            synchronized (loadedClassesMap) {
                if (loadedClasses.containsKey(className2))
                    continue;
            }
            byte[] bytes = entry.getValue();
            if (classDir != null) {
                // Write compiled class file to disk if classDir is specified
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
            loadedClasses.put(className, clazz = classLoader.loadClass(className));
        }
        return clazz;
    }

    /**
     * Gets a MyJavaFileManager instance from the provided StandardJavaFileManager.
     *
     * @param fm The StandardJavaFileManager instance
     * @return The MyJavaFileManager instance
     */
    @NotNull
    public MyJavaFileManager getFileManager(StandardJavaFileManager fm) {
        return fileManagerOverride != null
                ? fileManagerOverride.apply(fm)
                : new MyJavaFileManager(fm);
    }
}
