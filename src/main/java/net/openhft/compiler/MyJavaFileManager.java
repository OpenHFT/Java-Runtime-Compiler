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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

import javax.tools.*;
import javax.tools.JavaFileObject.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class is a custom implementation of JavaFileManager used for managing Java file objects
 * and class loaders. It provides additional functionalities to handle in-memory compilation.
 */
public class MyJavaFileManager implements JavaFileManager {
    private static final Logger LOG = LoggerFactory.getLogger(MyJavaFileManager.class);
    private final static Unsafe unsafe;
    private static final long OVERRIDE_OFFSET;

    static {
        long offset;
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        try {
            Field f = AccessibleObject.class.getDeclaredField("override");
            offset = unsafe.objectFieldOffset(f);
        } catch (NoSuchFieldException e) {
            offset = 0;
        }
        OVERRIDE_OFFSET = offset;
    }

    private final StandardJavaFileManager fileManager;

    // synchronizing due to ConcurrentModificationException
    private final Map<String, CloseableByteArrayOutputStream> buffers = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Constructs a MyJavaFileManager with the specified StandardJavaFileManager.
     *
     * @param fileManager The StandardJavaFileManager to be used
     */
    public MyJavaFileManager(StandardJavaFileManager fileManager) {
        this.fileManager = fileManager;
    }

    /**
     * Lists the locations for modules. This method is synchronized due to potential thread safety issues.
     *
     * @param location The location to list modules for
     * @return An iterable of sets of locations
     */
    // Apparently, this method might not be thread-safe.
    // See https://github.com/OpenHFT/Java-Runtime-Compiler/issues/85
    public synchronized Iterable<Set<Location>> listLocationsForModules(final Location location) {
        return invokeNamedMethodIfAvailable(location, "listLocationsForModules");
    }

    /**
     * Infers the module name for a given location. This method is synchronized due to potential thread safety issues.
     *
     * @param location The location to infer the module name for
     * @return The inferred module name
     */
    // Apparently, this method might not be thread-safe.
    // See https://github.com/OpenHFT/Java-Runtime-Compiler/issues/85
    public synchronized String inferModuleName(final Location location) {
        return invokeNamedMethodIfAvailable(location, "inferModuleName");
    }

    /**
     * Gets the class loader for a given location.
     *
     * @param location The location to get the class loader for
     * @return The class loader for the location
     */
    public ClassLoader getClassLoader(Location location) {
        return fileManager.getClassLoader(location);
    }

    /**
     * Lists JavaFileObjects for a given location, package name, and kind. This method is synchronized due to potential
     * thread safety issues.
     *
     * @param location    The location to list JavaFileObjects for
     * @param packageName The package name to list JavaFileObjects for
     * @param kinds       The kinds of JavaFileObjects to list
     * @param recurse     Whether to recurse into subdirectories
     * @return An iterable of JavaFileObjects
     * @throws IOException If an I/O error occurs
     */
    public synchronized Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
        return fileManager.list(location, packageName, kinds, recurse);
    }

    /**
     * Infers the binary name for a given JavaFileObject.
     *
     * @param location The location of the file
     * @param file     The JavaFileObject to infer the binary name for
     * @return The binary name of the file
     */
    public String inferBinaryName(Location location, JavaFileObject file) {
        return fileManager.inferBinaryName(location, file);
    }

    /**
     * Checks if two FileObjects refer to the same file.
     *
     * @param a The first FileObject
     * @param b The second FileObject
     * @return true if the FileObjects refer to the same file, false otherwise
     */
    public boolean isSameFile(FileObject a, FileObject b) {
        return fileManager.isSameFile(a, b);
    }

    /**
     * Handles an option for the file manager. This method is synchronized due to potential
     * thread safety issues.
     *
     * @param current   The current option to handle
     * @param remaining The remaining options iterator
     * @return true if the option was handled, false otherwise
     */
    public synchronized boolean handleOption(String current, Iterator<String> remaining) {
        return fileManager.handleOption(current, remaining);
    }

    /**
     * Checks if a given location is supported by the file manager.
     *
     * @param location The location to check
     * @return true if the location is supported, false otherwise
     */
    public boolean hasLocation(Location location) {
        return fileManager.hasLocation(location);
    }

    /**
     * Gets a JavaFileObject for input at the specified location and class name.
     *
     * @param location  The location to get the JavaFileObject for
     * @param className The class name of the JavaFileObject
     * @param kind      The kind of the JavaFileObject
     * @return The JavaFileObject for input
     * @throws IOException If an I/O error occurs
     */
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {

        if (location == StandardLocation.CLASS_OUTPUT) {
            boolean success;
            final byte[] bytes;
            synchronized (buffers) {
                success = buffers.containsKey(className) && kind == Kind.CLASS;
                bytes = buffers.get(className).toByteArray();
            }
            if (success) {

                return new SimpleJavaFileObject(URI.create(className), kind) {
                    @NotNull
                    public InputStream openInputStream() {
                        return new ByteArrayInputStream(bytes);
                    }
                };
            }
        }
        return fileManager.getJavaFileForInput(location, className, kind);
    }

    /**
     * Gets a JavaFileObject for output at the specified location and class name.
     *
     * @param location  The location to get the JavaFileObject for
     * @param className The class name of the JavaFileObject
     * @param kind      The kind of the JavaFileObject
     * @param sibling   A sibling file object
     * @return The JavaFileObject for output
     */
    @NotNull
    public JavaFileObject getJavaFileForOutput(Location location, final String className, Kind kind, FileObject sibling) {
        return new SimpleJavaFileObject(URI.create(className), kind) {
            @NotNull
            public OutputStream openOutputStream() {
                // CloseableByteArrayOutputStream.closed is used to filter partial results from getAllBuffers()
                CloseableByteArrayOutputStream baos = new CloseableByteArrayOutputStream();

                // Reads from getAllBuffers() should be repeatable:
                // let's ignore compile result in case compilation of this class was triggered before
                buffers.putIfAbsent(className, baos);

                return baos;
            }
        };
    }

    /**
     * Gets a FileObject for input at the specified location, package name, and relative name.
     *
     * @param location     The location to get the FileObject for
     * @param packageName  The package name of the FileObject
     * @param relativeName The relative name of the FileObject
     * @return The FileObject for input
     * @throws IOException If an I/O error occurs
     */
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        return fileManager.getFileForInput(location, packageName, relativeName);
    }

    /**
     * Gets a FileObject for output at the specified location, package name, and relative name.
     *
     * @param location     The location to get the FileObject for
     * @param packageName  The package name of the FileObject
     * @param relativeName The relative name of the FileObject
     * @param sibling      A sibling file object
     * @return The FileObject for output
     * @throws IOException If an I/O error occurs
     */
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException {
        return fileManager.getFileForOutput(location, packageName, relativeName, sibling);
    }

    /**
     * Flushes the file manager. This implementation does nothing.
     */
    public void flush() {
        // Do nothing
    }

    /**
     * Closes the file manager.
     *
     * @throws IOException If an I/O error occurs
     */
    public void close() throws IOException {
        fileManager.close();
    }

    /**
     * Checks if the specified option is supported by the file manager.
     *
     * @param option The option to check
     * @return The number of arguments the option takes, or -1 if the option is not supported
     */
    public int isSupportedOption(String option) {
        return fileManager.isSupportedOption(option);
    }

    /**
     * Clears the buffers used for storing compiled class byte code.
     */
    public void clearBuffers() {
        buffers.clear();
    }

    /**
     * Gets all buffers containing compiled class byte code.
     *
     * @return A map of class names to byte arrays containing the compiled class byte code
     */
    @NotNull
    public Map<String, byte[]> getAllBuffers() {
        Map<String, byte[]> ret = new LinkedHashMap<>(buffers.size() * 2);
        Map<String, CloseableByteArrayOutputStream> compiledClasses = new LinkedHashMap<>(ret.size());

        synchronized (buffers) {
            compiledClasses.putAll(buffers);
        }

        for (Map.Entry<String, CloseableByteArrayOutputStream> e : compiledClasses.entrySet()) {
            try {
                // Await for compilation in case class is still being compiled in previous compiler run.
                e.getValue().closeFuture().get(30, TimeUnit.SECONDS);
            } catch (InterruptedException t) {
                Thread.currentThread().interrupt();

                LOG.warn("Interrupted while waiting for compilation result [class=" + e.getKey() + "]");

                break;
            } catch (ExecutionException | TimeoutException t) {
                LOG.warn("Failed to wait for compilation result [class=" + e.getKey() + "]", t);

                continue;
            }

            final byte[] value = e.getValue().toByteArray();

            ret.put(e.getKey(), value);
        }

        return ret;
    }

    /**
     * Invokes a named method on the file manager if it is available.
     *
     * @param location The location to pass to the method
     * @param name     The name of the method to invoke
     * @param <T>      The return type of the method
     * @return The result of invoking the method
     */
    @SuppressWarnings("unchecked")
    private <T> T invokeNamedMethodIfAvailable(final Location location, final String name) {
        final Method[] methods = fileManager.getClass().getDeclaredMethods();
        for (Method method : methods) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 1 &&
                    method.getParameterTypes()[0] == Location.class) {
                try {
                    if (OVERRIDE_OFFSET == 0)
                        method.setAccessible(true);
                    else
                        unsafe.putBoolean(method, OVERRIDE_OFFSET, true);
                    return (T) method.invoke(fileManager, location);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new UnsupportedOperationException("Unable to invoke method " + name, e);
                }
            }
        }
        throw new UnsupportedOperationException("Unable to find method " + name);
    }
}
