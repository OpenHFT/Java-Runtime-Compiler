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

import static net.openhft.compiler.CompilerUtils.*;

@SuppressWarnings("StaticNonFinalField")
/**
 * The CachedCompiler class implements Closeable and is used for managing the compilation and caching of classes.
 * It maintains a map of loaded classes and file managers for different class loaders and options for compilation.
 * Instances of this class can be configured with specific source and class directories as well as compilation options.
 *
 * @since 2023-08-04
 */
public class CachedCompiler implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(CachedCompiler.class);
    private static final PrintWriter DEFAULT_WRITER = new PrintWriter(System.err);
    private static final List<String> DEFAULT_OPTIONS = Arrays.asList("-g", "-nowarn");

    // Map containing loaded classes for various class loaders
    private final Map<ClassLoader, Map<String, Class<?>>> loadedClassesMap = Collections.synchronizedMap(new WeakHashMap<>());
    // Map containing file managers for various class loaders
    private final Map<ClassLoader, MyJavaFileManager> fileManagerMap = Collections.synchronizedMap(new WeakHashMap<>());

    // Directory where the source files are located, nullable
    @Nullable
    private final File sourceDir;
    // Directory where the class files are located, nullable
    @Nullable
    private final File classDir;
    // List of options for the compiler, not null
    @NotNull
    private final List<String> options;

    // Concurrent map of Java file objects representing the classes
    private final ConcurrentMap<String, JavaFileObject> javaFileObjects = new ConcurrentHashMap<>();

        /**
     * Constructor that initializes a CachedCompiler instance with specified source and class directories.
     * Uses the default compilation options.
     *
     * @param sourceDir The directory for source files, may be null
     * @param classDir The directory for class files, may be null
     */
    public CachedCompiler(@Nullable File sourceDir, @Nullable File classDir) {
        this(sourceDir, classDir, DEFAULT_OPTIONS);
    }

    /**
     * Constructor that initializes a CachedCompiler instance with specified source and class directories, and compilation options.
     *
     * @param sourceDir The directory for source files, may be null
     * @param classDir The directory for class files, may be null
     * @param options The compilation options for the compiler
     */
    public CachedCompiler(@Nullable File sourceDir, @Nullable File classDir, @NotNull List<String> options) {
        this.sourceDir = sourceDir;
        this.classDir = classDir;
        this.options = options;
    }

    /**
     * Closes the file managers associated with different class loaders.
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
     * Loads a class from the given Java code using the class loader of this class.
     *
     * @param className The name of the class to load
     * @param javaCode The Java code of the class
     * @return The loaded class
     * @throws ClassNotFoundException If the class cannot be found
     */
    public Class loadFromJava(@NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(getClass().getClassLoader(), className, javaCode, DEFAULT_WRITER);
    }

    /**
     * Loads a class from the given Java code using the specified class loader.
     *
     * @param classLoader The class loader to use for loading the class
     * @param className The name of the class to load
     * @param javaCode The Java code of the class
     * @return The loaded class
     * @throws ClassNotFoundException If the class cannot be found
     */
    public Class loadFromJava(@NotNull ClassLoader classLoader,
                              @NotNull String className,
                              @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(classLoader, className, javaCode, DEFAULT_WRITER);
    }

    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className, @NotNull String javaCode, MyJavaFileManager fileManager) {
        return compileFromJava(className, javaCode, DEFAULT_WRITER, fileManager);
    }

    /**
     * Compiles the given Java code into bytecode using the provided file manager and writer.
     *
     * @param className The name of the class to compile
     * @param javaCode The Java code of the class
     * @param writer The writer to use for compilation diagnostics
     * @param fileManager The file manager to use for the compilation
     * @return A map containing the compiled bytecode, keyed by class name. If a compilation error occurs, an empty map is returned.
     */
    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className,
                                        @NotNull String javaCode,
                                        final @NotNull PrintWriter writer,
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

            // nothing to return due to compiler error
            return Collections.emptyMap();
        }
        else {
            Map<String, byte[]> result = fileManager.getAllBuffers();

            return result;
        }
    }

        /**
     * Loads a class from the given Java code using the specified class loader and writer for diagnostics.
     * If the class is already loaded, it returns the loaded class. Otherwise, it compiles the Java code,
     * loads the class, and caches it.
     *
     * @param classLoader The class loader to use for loading the class
     * @param className The name of the class to load
     * @param javaCode The Java code of the class
     * @param writer The writer to use for compilation diagnostics, may be null
     * @return The loaded class
     * @throws ClassNotFoundException If the class cannot be found or loaded
     */
    public Class loadFromJava(@NotNull ClassLoader classLoader,
                              @NotNull String className,
                              @NotNull String javaCode,
                              @Nullable PrintWriter writer) throws ClassNotFoundException {
        Class<?> clazz = null;
        // Synchronize and check if the class is already loaded
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
            // Return the already loaded class

        // Get or create the file manager
        MyJavaFileManager fileManager = fileManagerMap.get(classLoader);
        if (fileManager == null) {
            StandardJavaFileManager standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
            fileManagerMap.put(classLoader, fileManager = new MyJavaFileManager(standardJavaFileManager));
        }

        // Compile the given Java code
        final Map<String, byte[]> compiled = compileFromJava(className, javaCode, printWriter, fileManager);

        // Iterate through the compiled entries and update/write class files
        for (Map.Entry<String, byte[]> entry : compiled.entrySet()) {
            String className2 = entry.getKey();
            synchronized (loadedClassesMap) {
                if (loadedClasses.containsKey(className2))
                    continue; // Skip if already loaded
            }
            byte[] bytes = entry.getValue();

            // Write the compiled class to the file system if classDir is specified
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

                // Define the class using the compiled bytes
                Class<?> clazz2 = CompilerUtils.defineClass(classLoader, className2, bytes);
                synchronized (loadedClassesMap) {
                    loadedClasses.put(className2, clazz2);
                }
            }
        }

        // Load the final class and return
        synchronized (loadedClassesMap) {
            loadedClasses.put(className, clazz = classLoader.loadClass(className));
        }
        return clazz;
    }
}
