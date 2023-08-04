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
 * This class implements the JavaFileManager interface.
 * It utilizes the Unsafe class to bypass security checks and access private fields for 'override'.
 *
 * @since 2023-08-04
 */
class MyJavaFileManager implements JavaFileManager {
    // Logger instance for logging messages related to this class
    private static final Logger LOG = LoggerFactory.getLogger(MyJavaFileManager.class);

    // Unsafe instance used to perform operations that bypass Java's access control checks
    private final static Unsafe unsafe;

    // Offset of the 'override' field within the AccessibleObject class
    private static final long OVERRIDE_OFFSET;

    static {
        long offset;
        try {
            // Accessing the Unsafe instance using reflection
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        } catch (Exception ex) {
            // Throwing an AssertionError if unable to access the Unsafe instance
            throw new AssertionError(ex);
        }
        try {
            // Getting the field offset for the 'override' field within AccessibleObject
            Field f = AccessibleObject.class.getDeclaredField("override");
            offset = unsafe.objectFieldOffset(f);
        } catch (NoSuchFieldException e) {
            // Assigning a default value of 0 if the field is not found
            offset = 0;
        }
        OVERRIDE_OFFSET = offset;
    }

    // Standard Java File Manager to delegate the functionality
    private final StandardJavaFileManager fileManager;

    // synchronizing due to ConcurrentModificationException
    // Buffer containing the output of the compiled classes
    private final Map<String, CloseableByteArrayOutputStream> buffers = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Constructor to initialize this file manager with a standard file manager.
     *
     * @param fileManager the standard file manager
     */
    MyJavaFileManager(StandardJavaFileManager fileManager) {
        this.fileManager = fileManager;
    }

    // Apparently, this method might not be thread-safe.
    // See https://github.com/OpenHFT/Java-Runtime-Compiler/issues/85
    public synchronized Iterable<Set<Location>> listLocationsForModules(final Location location) {
        return invokeNamedMethodIfAvailable(location, "listLocationsForModules");
    }

    /**
     * Retrieves the class loader for the given location.
     *
     * @param location the location
     * @return the class loader for the specified location
     */
    public ClassLoader getClassLoader(Location location) {
        return fileManager.getClassLoader(location);
    }

    /**
     * Lists the JavaFileObjects that meet the criteria.
     *
     * @param location    the location
     * @param packageName the package name
     * @param kinds       the kinds of JavaFileObjects returned
     * @param recurse     if true, scan subpackages
     * @return an Iterable of JavaFileObjects meeting the criteria
     * @throws IOException if an error occurs while accessing the files
     */
    public synchronized Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
        return fileManager.list(location, packageName, kinds, recurse);
    }

    /**
     * Infers the binary name of a file object.
     *
     * @param location the location
     * @param file     the file object
     * @return the inferred binary name
     */
    public String inferBinaryName(Location location, JavaFileObject file) {
        return fileManager.inferBinaryName(location, file);
    }

    /**
     * Checks if two FileObjects are the same.
     *
     * @param a the first file object
     * @param b the second file object
     * @return true if the objects are the same, false otherwise
     */
    public boolean isSameFile(FileObject a, FileObject b) {
        return fileManager.isSameFile(a, b);
    }

    /**
     * Handles the given option and its arguments.
     *
     * @param current   the current option
     * @param remaining the remaining options
     * @return true if the option is recognized, false otherwise
     */
    public synchronized boolean handleOption(String current, Iterator<String> remaining) {
        return fileManager.handleOption(current, remaining);
    }

    /**
     * Determines whether a location is known to this file manager.
     *
     * @param location the location
     * @return true if the location is known, false otherwise
     */
    public boolean hasLocation(Location location) {
        return fileManager.hasLocation(location);
    }

    /**
     * Returns the Java file object representing the specified class of the specified kind.
     *
     * @param location  the location
     * @param className the class name
     * @param kind      the kind of file object
     * @return the Java file object, null if the file object can't be opened
     * @throws IOException if an error occurs while accessing the files
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
     * Returns the Java file object for output representing the specified class of the specified kind.
     *
     * @param location  the location
     * @param className the class name
     * @param kind      the kind of file object
     * @param sibling   the sibling file object
     * @return the Java file object for output
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
     * Returns a file object for input representing the specified relative name in the specified package in the specified location.
     *
     * @param location     the location
     * @param packageName  the package name
     * @param relativeName the relative name
     * @return the file object
     * @throws IOException if an error occurs while accessing the files
     */
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        return fileManager.getFileForInput(location, packageName, relativeName);
    }

    /**
     * Returns a file object for output representing the specified relative name in the specified package in the specified location.
     *
     * @param location     the location
     * @param packageName  the package name
     * @param relativeName the relative name
     * @param sibling      the sibling file object
     * @return the file object for output
     * @throws IOException if an error occurs while accessing the files
     */
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException {
        return fileManager.getFileForOutput(location, packageName, relativeName, sibling);
    }

    /**
     * Flushes the output streams, if any. This method does nothing in this implementation.
     */
    public void flush() {
        // Do nothing
    }

    /**
     * Closes this file manager, releasing resources like file handles.
     * Any further attempts to use it might throw an IllegalStateException.
     *
     * @throws IOException if an error occurs while closing the file manager
     */
    public void close() throws IOException {
        fileManager.close();
    }


    /**
     * Checks if the given option is supported by the underlying file manager.
     *
     * @param option the option to check
     * @return a negative number if the option is not supported, or a non-negative integer representing
     * the number of arguments for this option if it is supported
     */
    public int isSupportedOption(String option) {
        return fileManager.isSupportedOption(option);
    }

    /**
     * Clears all existing compilation buffers. This method is useful when you want to
     * free up memory used by previously compiled classes.
     */
    public void clearBuffers() {
        buffers.clear();
    }

    /**
     * Retrieves all compilation buffers that contain the bytecode of compiled classes.
     * It waits for the compilation result and returns the compiled classes.
     *
     * @return a map of class names to their corresponding bytecode
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

/**
 * Dynamically invokes a named method if it is available on the underlying file manager.
 * This method is useful for calling proprietary or non-standard methods that might not
 * be available on all implementations.
 *
 * @param location the location parameter for the method invocation
 * @param name     the name of the method to invoke
 * @return the result of the method invocation
 * @throws UnsupportedOperationException if the method could not be found or invoked
 */
            final byte[] value = e.getValue().toByteArray();

            ret.put(e.getKey(), value);
        }

        return ret;
    }

    @SuppressWarnings("unchecked")
/**
 * Dynamically invokes a named method if it is available on the underlying file manager.
 * The method should take a single parameter of type {@link Location}.
 *
 * @param location the location parameter for the method invocation
 * @param name     the name of the method to invoke
 * @return the result of the method invocation
 * @throws UnsupportedOperationException if the method could not be found or invoked
 */
    private <T> T invokeNamedMethodIfAvailable(final Location location, final String name) {
        // Retrieve all declared methods from the underlying file manager class
        final Method[] methods = fileManager.getClass().getDeclaredMethods();

        // Iterate through the methods to find the one with the given name and matching signature
        for (Method method : methods) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 1 &&
                    method.getParameterTypes()[0] == Location.class) {
                try {
                    // If OVERRIDE_OFFSET is 0, use setAccessible; otherwise use unsafe to override accessibility
                    if (OVERRIDE_OFFSET == 0)
                        method.setAccessible(true);
                    else
                        unsafe.putBoolean(method, OVERRIDE_OFFSET, true);

                    // Invoke the found method with the given location and return the result
                    return (T) method.invoke(fileManager, location);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    // An exception occurred while invoking the method; wrap and re-throw
                    throw new UnsupportedOperationException("Unable to invoke method " + name, e);
                }
            }
        }

        // The method with the given name and signature was not found
        throw new UnsupportedOperationException("Unable to find method " + name);
    }
}