/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
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
import java.nio.file.Path;
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
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String JAVA_CLASS_PATH = "java.class.path";
    static volatile JavaCompiler s_compiler;
    static volatile StandardJavaFileManager s_standardJavaFileManager;

    /*
     * Use sun.misc.Unsafe to gain access to ClassLoader.defineClass. This allows
     * compiled bytecode to be defined without standard reflection checks. The
     * fallback path calls setAccessible if the internal 'override' field is absent.
     */
    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe u = (Unsafe) theUnsafe.get(null);
            DEFINE_CLASS_METHOD = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            try {
                Field f = AccessibleObject.class.getDeclaredField("override");
                long offset = u.objectFieldOffset(f);
                u.putBoolean(DEFINE_CLASS_METHOD, offset, true);
            } catch (NoSuchFieldException e) {
                DEFINE_CLASS_METHOD.setAccessible(true);
            }
        } catch (NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            throw new AssertionError(e);
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
     * Reinitialises the cached {@link JavaCompiler}. This method synchronises
     * on a dedicated lock to avoid racy lazy initialisation of static fields.
     *
     * @throws AssertionError if the compiler classes cannot be loaded.
     */
    private static void reset() {
        synchronized (CompilerUtils.class) {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                try {
                    Class<?> javacTool = Class.forName("com.sun.tools.javac.api.JavacTool");
                    Method create = javacTool.getMethod("create");
                    compiler = (JavaCompiler) create.invoke(null);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            s_compiler = compiler;
            // Invalidate any cached file manager tied to the previous compiler.
            s_standardJavaFileManager = null;
        }
    }

    static JavaCompiler currentCompiler() {
        JavaCompiler compiler = s_compiler;
        if (compiler != null) {
            return compiler;
        }
        reset();
        return s_compiler;
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
        File file = new File(dir);
        if (file.exists()) {
            String path;
            try {
                path = file.getCanonicalPath();
            } catch (IOException ignored) {
                path = file.getAbsolutePath();
            }
            if (!Arrays.asList(System.getProperty(JAVA_CLASS_PATH).split(File.pathSeparator)).contains(path))
                System.setProperty(JAVA_CLASS_PATH, System.getProperty(JAVA_CLASS_PATH) + File.pathSeparator + path);

        } else {
            return false;
        }
        reset();
        return true;
    }

    /**
     * Normalizes relative paths and rejects traversal attempts beyond the current root.
     *
     * @param path value to sanitize.
     * @return normalized path when safe.
     * @throws IllegalArgumentException if the path attempts to traverse upward ("..").
     */
    static Path sanitizePath(@NotNull Path path) {
        Objects.requireNonNull(path, "path");
        Path normalized = path.normalize();
        if (normalized.isAbsolute()) {
            return normalized;
        }
        if (normalized.getNameCount() > 0 && "..".equals(normalized.getName(0).toString())) {
            throw new IllegalArgumentException("Path traversal attempt: " + path);
        }
        return normalized;
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
        try {
            return new String(bytes, UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    @Nullable
    @SuppressWarnings("ReturnOfNull")
    private static byte[] readBytes(@NotNull File file) {
        if (!file.exists()) return null;
        long len = file.length();
        if (len > Runtime.getRuntime().totalMemory() / 10)
            throw new IllegalStateException("Attempted to read large file " + file + " was " + len + " bytes.");
        byte[] bytes = new byte[(int) len];
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            dis.readFully(bytes);
            return bytes;
        } catch (IOException e) {
            LOGGER.warn("Unable to read {}", file, e);
            throw new IllegalStateException("Unable to read file " + file, e);
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
        try {
            return text.getBytes(UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
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
        File parentDir = file.getParentFile();
        if (!parentDir.isDirectory() && !parentDir.mkdirs())
            throw new IllegalStateException("Unable to create directory " + parentDir);
        // only write to disk if it has changed.
        File bak = null;
        if (file.exists()) {
            byte[] bytes2 = readBytes(file);
            if (Arrays.equals(bytes, bytes2))
                return false;
            bak = new File(parentDir, file.getName() + ".bak");
            if (!file.renameTo(bak)) {
                LOGGER.debug("Unable to rename {} to backup {}", file, bak);
            }
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(bytes);
        } catch (IOException e) {
            LOGGER.warn("Unable to write {} as {}", file, decodeUTF8(bytes), e);
            if (file.exists() && !file.delete()) {
                LOGGER.debug("Unable to delete {}", file);
            }
            if (bak != null && bak.exists() && !bak.renameTo(file)) {
                LOGGER.debug("Unable to restore backup {} to {}", bak, file);
            }
            throw new IllegalStateException("Unable to write " + file, e);
        } finally {
            close(fos);
            if (bak != null && bak.exists() && file.exists()) {
                if (!bak.delete()) {
                    LOGGER.debug("Unable to delete backup {}", bak);
                }
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
        return new FileInputStream(filename);
    }
}
