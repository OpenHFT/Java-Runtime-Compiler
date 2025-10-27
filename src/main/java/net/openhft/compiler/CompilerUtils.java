/*
 * Copyright 2014-2025 chronicle.software
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
import sun.misc.Unsafe;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Objects;

/**
 * Provides static utility methods for runtime Java compilation, dynamic class loading,
 * and class-path manipulation. Acts as the primary entry point for simple compilation tasks.
 */
public enum CompilerUtils {
    ; // none
    /**
     * Indicates whether the JVM started with debugging enabled.
     */
    public static final boolean DEBUGGING = isDebug();

    /**
     * In-memory singleton compiler reused across calls.
     */
    public static final CachedCompiler CACHED_COMPILER = new CachedCompiler(null, null);

    private static final Logger LOGGER = LoggerFactory.getLogger(CompilerUtils.class);
    private static final Method DEFINE_CLASS_METHOD;
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final String JAVA_CLASS_PATH = "java.class.path";
    static JavaCompiler s_compiler;
    static StandardJavaFileManager s_standardJavaFileManager;

    /*
     * Use sun.misc.Unsafe to gain access to ClassLoader.defineClass. This allows
     * compiled bytecode to be defined without standard reflection checks. The
     * fallback path calls setAccessible if the internal 'override' field is absent.
     */
    static {
        try {
            Field theUnsafe = AccessController.doPrivileged((PrivilegedAction<Field>) () -> {
                try {
                    Field field = Unsafe.class.getDeclaredField("theUnsafe");
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException(e);
                }
            });
            Unsafe u = (Unsafe) theUnsafe.get(null);
            DEFINE_CLASS_METHOD = AccessController.doPrivileged((PrivilegedAction<Method>) () -> {
                try {
                    return ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(e);
                }
            });
            try {
                Field f = AccessibleObject.class.getDeclaredField("override");
                long offset = u.objectFieldOffset(f);
                u.putBoolean(DEFINE_CLASS_METHOD, offset, true);
            } catch (NoSuchFieldException e) {
                AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                    DEFINE_CLASS_METHOD.setAccessible(true);
                    return null;
                });
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (IllegalStateException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AssertionError(cause);
        }
    }

    static {
        reset();
    }

    private static boolean isDebug() {
        String inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments().toString();
        return inputArguments.contains("-Xdebug") || inputArguments.contains("-agentlib:jdwp=");
    }

    /**
     * Reinitialises the cached {@link JavaCompiler}. This method is not thread-safe
     * and callers must serialise access if used outside static initialisation.
     *
     * @throws AssertionError if the compiler classes cannot be loaded.
     */
    private static synchronized void reset() {
        s_compiler = ToolProvider.getSystemJavaCompiler();
        if (s_compiler == null) {
            try {
                Class<?> javacTool = Class.forName("com.sun.tools.javac.api.JavacTool");
                Method create = javacTool.getMethod("create");
                s_compiler = (JavaCompiler) create.invoke(null);
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new AssertionError(e);
            }
        }
        s_standardJavaFileManager = null;
    }

    static StandardJavaFileManager standardFileManager() {
        synchronized (CompilerUtils.class) {
            if (s_standardJavaFileManager == null) {
                s_standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
            }
            return s_standardJavaFileManager;
        }
    }

    /**
     * Loads a class from a source file found on the class-path or local file system.
     * Thread-safe as it delegates to the cached compiler.
     *
     * @param className    expected class name of the outer class.
     * @param resourceName full file name with extension.
     * @return the loaded outer class.
     * @throws IOException            if the resource cannot be read.
     * @throws ClassNotFoundException if the compiled class name differs or fails to initialise.
     */
    public static Class<?> loadFromResource(@NotNull String className, @NotNull String resourceName) throws IOException, ClassNotFoundException {
        return loadFromJava(className, readText(resourceName));
    }

    /**
     * Load a java class from text.
     *
     * @param className expected class name of the outer class.
     * @param javaCode  to compile and load.
     * @return the outer class loaded.
     * @throws ClassNotFoundException the class name didn't match or failed to initialise.
     */
    private static Class<?> loadFromJava(@NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        return CACHED_COMPILER.loadFromJava(Thread.currentThread().getContextClassLoader(), className, javaCode);
    }

    /**
     * Adds a directory to the compilation class-path. This method is not thread-safe
     * and should be called in a single-threaded context.
     *
     * @param dir directory to add.
     * @return {@code true} if the directory exists and was appended.
     * @throws AssertionError if the compiler cannot be reinitialised.
     */
    public static boolean addClassPath(@NotNull String dir) {
        Path candidate = sanitizePath(dir).toAbsolutePath();
        if (!Files.exists(candidate)) {
            return false;
        }
        String path;
        try {
            path = candidate.toRealPath().toString();
        } catch (IOException e) {
            path = candidate.toString();
        }
        String[] entries = System.getProperty(JAVA_CLASS_PATH).split(File.pathSeparator);
        if (Arrays.stream(entries).noneMatch(path::equals)) {
            System.setProperty(JAVA_CLASS_PATH, System.getProperty(JAVA_CLASS_PATH) + File.pathSeparator + path);
        }
        reset();
        return true;
    }

    /**
     * Define a class for byte code.
     *
     * @param className expected to load.
     * @param bytes     of the byte code.
     */
    public static void defineClass(@NotNull String className, @NotNull byte[] bytes) {
        defineClass(Thread.currentThread().getContextClassLoader(), className, bytes);
    }

    /**
     * Defines a class from the supplied bytecode.
     * Thread-safe and uses {@code Unsafe} to bypass access checks.
     *
     * @param classLoader class loader to define the class within.
     * @param className   expected binary name.
     * @param bytes       compiled bytecode for the class.
     * @return the defined class instance.
     * @throws AssertionError if {@code defineClass} cannot be invoked.
     */
    public static Class<?> defineClass(@Nullable ClassLoader classLoader, @NotNull String className, @NotNull byte[] bytes) {
        try {
            return (Class) DEFINE_CLASS_METHOD.invoke(classLoader, className, bytes, 0, bytes.length);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
            throw new AssertionError(e.getCause());
        }
    }

    /**
     * Reads the supplied resource as UTF-8 text. Thread-safe.
     *
     * @param resourceName resource path or inline text prefixed with '='.
     * @return the text contents of the resource.
     * @throws IOException if an I/O error occurs while reading.
     */
    private static String readText(@NotNull String resourceName) throws IOException {
        if (resourceName.startsWith("="))
            return resourceName.substring(1);
        StringWriter sw = new StringWriter();
        Reader isr = new InputStreamReader(getInputStream(resourceName), UTF_8);
        try {
            char[] chars = new char[8 * 1024];
            int len;
            while ((len = isr.read(chars)) > 0)
                sw.write(chars, 0, len);
        } finally {
            close(isr);
        }
        return sw.toString();
    }

    @NotNull
    private static String decodeUTF8(@NotNull byte[] bytes) {
        return new String(bytes, UTF_8);
    }

    @Nullable
    @SuppressWarnings("ReturnOfNull")
    private static byte[] readBytes(@NotNull File file) {
        Path target = sanitizePath(file.toPath()).toAbsolutePath();
        if (!Files.exists(target)) return null;
        long len;
        try {
            len = Files.size(target);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to determine size for " + target, e);
        }
        if (len > Runtime.getRuntime().totalMemory() / 10)
            throw new IllegalStateException("Attempted to read large file " + target + " was " + len + " bytes.");
        byte[] bytes = new byte[(int) len];
        try (DataInputStream dis = new DataInputStream(new FileInputStream(target.toFile()))) {
            dis.readFully(bytes);
            return bytes;
        } catch (IOException e) {
            LOGGER.warn("Unable to read {}", target, e);
            throw new IllegalStateException("Unable to read file " + target, e);
        }
    }

    private static void close(@Nullable Closeable closeable) {
        if (closeable != null)
            try {
                closeable.close();
            } catch (IOException e) {
                LOGGER.trace("Failed to close {}", closeable, e);
            }
    }

    /**
     * Writes the provided text to the target file using UTF-8.
     * Not thread-safe and may create or overwrite the file.
     *
     * @param file destination file.
     * @param text text to write.
     * @return {@code true} if the contents changed.
     * @throws IllegalStateException if the file cannot be written.
     */
    public static boolean writeText(@NotNull File file, @NotNull String text) {
        return writeBytes(file, encodeUTF8(text));
    }

    /**
     * Encodes the given text as UTF-8 bytes.
     *
     * @param text value to encode.
     * @return UTF-8 encoded representation.
     * @throws AssertionError if the JVM does not support UTF-8.
     */
    @NotNull
    private static byte[] encodeUTF8(@NotNull String text) {
        return text.getBytes(UTF_8);
    }

    /**
     * Writes the given bytes to the specified file. Not thread-safe.
     *
     * @param file  destination file.
     * @param bytes bytes to write.
     * @return {@code true} if the file contents were updated.
     * @throws IllegalStateException if the write fails.
     */
    public static boolean writeBytes(@NotNull File file, @NotNull byte[] bytes) {
        Path target = sanitizePath(file.toPath()).toAbsolutePath();
        Path parent = target.getParent();
        try {
            if (parent != null) {
                if (Files.exists(parent) && !Files.isDirectory(parent)) {
                    throw new IllegalStateException("Unable to create directory " + parent);
                }
                if (Files.notExists(parent)) {
                    Files.createDirectories(parent);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create directory " + parent, e);
        }
        Path backup = null;
        if (Files.exists(target)) {
            byte[] existing = readBytes(target.toFile());
            if (Arrays.equals(bytes, existing)) {
                return false;
            }
            backup = parent == null ? target.resolveSibling(target.getFileName() + ".bak")
                    : parent.resolve(target.getFileName() + ".bak");
            try {
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create backup for " + target, e);
            }
        }

        try {
            Files.write(target, bytes);
        } catch (IOException e) {
            LOGGER.warn("Unable to write {} as {}", target, decodeUTF8(bytes), e);
            try {
                Files.deleteIfExists(target);
                if (backup != null) {
                    Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException restoreError) {
                LOGGER.trace("Failed to restore {} from backup", target, restoreError);
            }
            throw new IllegalStateException("Unable to write " + target, e);
        }

        if (backup != null) {
            try {
                Files.deleteIfExists(backup);
            } catch (IOException e) {
                LOGGER.trace("Failed to delete backup {}", backup, e);
            }
        }
        return true;
    }

    /**
     * Opens the named resource as an {@link InputStream}. Thread-safe.
     *
     * @param filename name of a class-path resource or file; a leading '=' denotes inline text.
     * @return stream for the resource.
     * @throws FileNotFoundException if no file or resource exists.
     */
    @NotNull
    private static InputStream getInputStream(@NotNull String filename) throws FileNotFoundException {
        if (filename.isEmpty()) throw new IllegalArgumentException("The file name cannot be empty.");
        if (filename.charAt(0) == '=') return new ByteArrayInputStream(encodeUTF8(filename.substring(1)));
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        InputStream is = contextClassLoader.getResourceAsStream(filename);
        if (is != null) return is;
        InputStream is2 = contextClassLoader.getResourceAsStream('/' + filename);
        if (is2 != null) return is2;
        Path sanitized = sanitizePath(filename).toAbsolutePath();
        return new FileInputStream(sanitized.toFile());
    }

    private static Path sanitizePath(@NotNull String rawPath) {
        Objects.requireNonNull(rawPath, "path");
        return sanitizePath(Paths.get(rawPath));
    }

    private static Path sanitizePath(@NotNull Path path) {
        Objects.requireNonNull(path, "path");
        Path normalized = path.normalize();
        for (Path element : normalized) {
            if ("..".equals(element.toString())) {
                throw new IllegalArgumentException("Path traversal attempt for " + path);
            }
        }
        return normalized;
    }
}
