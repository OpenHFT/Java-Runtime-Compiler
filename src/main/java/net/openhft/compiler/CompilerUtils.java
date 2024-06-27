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
import java.util.Arrays;

/**
 * This class supports loading and debugging Java Classes dynamically.
 */
public enum CompilerUtils {
    ; // none
    public static final boolean DEBUGGING = isDebug();
    public static final CachedCompiler CACHED_COMPILER = new CachedCompiler(null, null);

    private static final Logger LOGGER = LoggerFactory.getLogger(CompilerUtils.class);
    private static final Method DEFINE_CLASS_METHOD;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String JAVA_CLASS_PATH = "java.class.path";
    static JavaCompiler s_compiler;
    static StandardJavaFileManager s_standardJavaFileManager;

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

    /**
     * Checks if the JVM is running in debug mode.
     *
     * @return true if the JVM is in debug mode, false otherwise
     */
    public static boolean isDebug() {
        String inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments().toString();
        return inputArguments.contains("-Xdebug") || inputArguments.contains("-agentlib:jdwp=");
    }

    /**
     * Resets the Java compiler instance. Attempts to load the system Java compiler
     * or the JavacTool if the system compiler is not available.
     */
    public static void reset() {
        s_compiler = ToolProvider.getSystemJavaCompiler();
        if (s_compiler == null) {
            try {
                Class<?> javacTool = Class.forName("com.sun.tools.javac.api.JavacTool");
                Method create = javacTool.getMethod("create");
                s_compiler = (JavaCompiler) create.invoke(null);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Loads a Java class file from the classpath or local file system.
     *
     * @param className    The expected class name of the outer class
     * @param resourceName The full file name with extension
     * @return The outer class loaded
     * @throws IOException            If the resource could not be loaded
     * @throws ClassNotFoundException If the class name didn't match or failed to initialize
     */
    public static Class<?> loadFromResource(@NotNull String className, @NotNull String resourceName) throws IOException, ClassNotFoundException {
        return loadFromJava(className, readText(resourceName));
    }

    /**
     * Loads a Java class from the provided Java source code.
     *
     * @param className The expected class name of the outer class
     * @param javaCode  The Java source code to compile and load
     * @return The outer class loaded
     * @throws ClassNotFoundException If the class name didn't match or failed to initialize
     */
    private static Class<?> loadFromJava(@NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        return CACHED_COMPILER.loadFromJava(Thread.currentThread().getContextClassLoader(), className, javaCode);
    }

    /**
     * Adds a directory to the classpath for compiling. This can be required with custom
     * libraries or dependencies.
     *
     * @param dir The directory to add
     * @return Whether the directory was found and added successfully
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
            // Add the directory to the classpath if not already present
            if (!Arrays.asList(System.getProperty(JAVA_CLASS_PATH).split(File.pathSeparator)).contains(path))
                System.setProperty(JAVA_CLASS_PATH, System.getProperty(JAVA_CLASS_PATH) + File.pathSeparator + path);

        } else {
            return false;
        }
        reset();
        return true;
    }

    /**
     * Defines a class from the provided byte code.
     *
     * @param className The expected class name
     * @param bytes     The byte code of the class
     */
    public static void defineClass(@NotNull String className, @NotNull byte[] bytes) {
        defineClass(Thread.currentThread().getContextClassLoader(), className, bytes);
    }

    /**
     * Define a class for byte code.
     *
     * @param classLoader to load the class into.
     * @param className   expected to load.
     * @param bytes       of the byte code.
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
     * Reads the text content from a resource.
     *
     * @param resourceName The name of the resource
     * @return The text content of the resource
     * @throws IOException If the resource cannot be read
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

    /**
     * Decodes a byte array into a UTF-8 string.
     *
     * @param bytes The byte array to decode
     * @return The decoded string
     */
    @NotNull
    public static String decodeUTF8(@NotNull byte[] bytes) {
        try {
            return new String(bytes, UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Reads the bytes from a file.
     *
     * @param file The file to read
     * @return The bytes read from the file, or null if the file does not exist
     */
    @Nullable
    @SuppressWarnings("ReturnOfNull")
    public static byte[] readBytes(@NotNull File file) {
        if (!file.exists()) return null;
        long len = file.length();
        if (len > Runtime.getRuntime().totalMemory() / 10)
            throw new IllegalStateException("Attempted to read large file " + file + " was " + len + " bytes.");
        byte[] bytes = new byte[(int) len];
        DataInputStream dis = null;
        try {
            dis = new DataInputStream(new FileInputStream(file));
            dis.readFully(bytes);
        } catch (IOException e) {
            close(dis);
            LOGGER.warn("Unable to read {}", file, e);
            throw new IllegalStateException("Unable to read file " + file, e);
        }

        return bytes;
    }

    /**
     * Closes a Closeable object, suppressing any IOException that occurs.
     *
     * @param closeable The Closeable object to close
     */
    public static void close(@Nullable Closeable closeable) {
        if (closeable != null)
            try {
                closeable.close();
            } catch (IOException e) {
                LOGGER.trace("Failed to close {}", closeable, e);
            }
    }

    /**
     * Writes a string as UTF-8 text to a file.
     *
     * @param file The file to write to
     * @param text The text to write
     * @return true if the file was written successfully, false otherwise
     */
    public static boolean writeText(@NotNull File file, @NotNull String text) {
        return writeBytes(file, encodeUTF8(text));
    }

    /**
     * Encodes a string into a byte array using UTF-8 encoding.
     *
     * @param text The string to encode
     * @return The encoded byte array
     */
    @NotNull
    public static byte[] encodeUTF8(@NotNull String text) {
        try {
            return text.getBytes(UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Writes a byte array to a file. If the file already exists and its contents
     * are identical to the byte array, the file is not modified. If the contents
     * differ, the file is backed up before being overwritten.
     *
     * @param file  The file to write to
     * @param bytes The byte array to write
     * @return true if the file was written successfully, false otherwise
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
            file.renameTo(bak);
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(bytes);
        } catch (IOException e) {
            close(fos);
            LOGGER.warn("Unable to write {} as {}", file, decodeUTF8(bytes), e);
            file.delete();
            if (bak != null)
                bak.renameTo(file);
            throw new IllegalStateException("Unable to write " + file, e);
        }
        return true;
    }

    /**
     * Gets an InputStream for a given filename. The method tries to load the file
     * from the classpath using the context class loader. If the file is not found,
     * it falls back to loading it from the filesystem.
     *
     * @param filename The name of the file to load
     * @return The InputStream for the file
     * @throws FileNotFoundException If the file cannot be found
     */
    @NotNull
    public static InputStream getInputStream(@NotNull String filename) throws FileNotFoundException {
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
