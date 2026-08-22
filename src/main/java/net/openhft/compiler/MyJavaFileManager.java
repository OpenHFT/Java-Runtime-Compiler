/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.*;
import javax.tools.JavaFileObject.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Custom JavaFileManager that stores compiled class files in memory and exposes
 * them as byte arrays, while delegating unresolved operations to a wrapped
 * StandardJavaFileManager.
 */
public class MyJavaFileManager implements JavaFileManager {
    private static final Logger LOG = LoggerFactory.getLogger(MyJavaFileManager.class);
    private final StandardJavaFileManager fileManager;

    // synchronizing due to ConcurrentModificationException
    private final Map<String, CloseableByteArrayOutputStream> buffers = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<URI, Set<String>> outputsBySource = new HashMap<>();

    /**
     * Create a file manager that delegates to the provided instance while
     * keeping compiled class bytes in memory.
     *
     * @param fileManager the underlying file manager to delegate to
     */
    public MyJavaFileManager(StandardJavaFileManager fileManager) {
        this.fileManager = fileManager;
    }

    /**
     * Invoke {@code listLocationsForModules} reflectively if available.
     * This method synchronises on the current instance as some JDK
     * implementations are not thread-safe.
     *
     * @param location the location whose modules are requested
     * @return the module locations or an empty iterable
     */
    public synchronized Iterable<Set<Location>> listLocationsForModules(final Location location) {
        return invokeNamedMethodIfAvailable(location, "listLocationsForModules", Collections.<Set<Location>>emptyList());
    }

    /**
     * Reflectively call {@code inferModuleName} if present on the delegate.
     * As above, the call is synchronised for safety on older JDKs.
     *
     * @param location the location to inspect
     * @return the inferred module name or {@code null}
     */
    public synchronized String inferModuleName(final Location location) {
        return invokeNamedMethodIfAvailable(location, "inferModuleName", (String) null);
    }

    public ClassLoader getClassLoader(Location location) {
        return fileManager.getClassLoader(location);
    }

    public synchronized Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
        return fileManager.list(location, packageName, kinds, recurse);
    }

    public String inferBinaryName(Location location, JavaFileObject file) {
        return fileManager.inferBinaryName(location, file);
    }

    public boolean isSameFile(FileObject a, FileObject b) {
        return fileManager.isSameFile(a, b);
    }

    public synchronized boolean handleOption(String current, Iterator<String> remaining) {
        return fileManager.handleOption(current, remaining);
    }

    public boolean hasLocation(Location location) {
        return fileManager.hasLocation(location);
    }

    /**
     * Return a JavaFileObject backed by the in-memory buffer when the caller
     * requests a class that has just been compiled to {@link StandardLocation#CLASS_OUTPUT}.
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
     * Store compiled class bytes in the internal buffer and return a sink
     * that writes into it.
     */
    @NotNull
    public JavaFileObject getJavaFileForOutput(Location location, final String className, Kind kind, FileObject sibling) {
        return new SimpleJavaFileObject(URI.create(className), kind) {
            @NotNull
            public OutputStream openOutputStream() {
                // CloseableByteArrayOutputStream.closed is used to filter partial results from getAllBuffers()
                CloseableByteArrayOutputStream baos = new CloseableByteArrayOutputStream();

                // Compiler tasks are serialised by CachedCompiler, so replacing a previous
                // result makes repeat compilation return the bytes produced by this task.
                buffers.put(className, baos);
                if (sibling != null) {
                    synchronized (outputsBySource) {
                        Set<String> outputs = outputsBySource.get(sibling.toUri());
                        if (outputs == null) {
                            outputs = new LinkedHashSet<>();
                            outputsBySource.put(sibling.toUri(), outputs);
                        }
                        outputs.add(className);
                    }
                }

                return baos;
            }
        };
    }

    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        return fileManager.getFileForInput(location, packageName, relativeName);
    }

    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException {
        return fileManager.getFileForOutput(location, packageName, relativeName, sibling);
    }

    public void flush() {
        // Do nothing
    }

    public void close() throws IOException {
        fileManager.close();
    }

    public int isSupportedOption(String option) {
        return fileManager.isSupportedOption(option);
    }

    /**
     * Remove all compiled class data from memory.
     */
    public void clearBuffers() {
        buffers.clear();
        synchronized (outputsBySource) {
            outputsBySource.clear();
        }
    }

    void prepareForCompilation(Iterable<? extends JavaFileObject> sources) {
        synchronized (outputsBySource) {
            for (JavaFileObject source : sources)
                outputsBySource.remove(source.toUri());
        }
    }

    @NotNull
    Map<String, byte[]> getBuffersForSources(Iterable<? extends JavaFileObject> sources) {
        final Set<String> outputNames = new LinkedHashSet<>();
        synchronized (outputsBySource) {
            for (JavaFileObject source : sources) {
                Set<String> outputs = outputsBySource.get(source.toUri());
                if (outputs != null)
                    outputNames.addAll(outputs);
            }
        }
        final Map<String, byte[]> result = getAllBuffers();
        result.keySet().retainAll(outputNames);
        return result;
    }

    /**
     * Collect all compiled class buffers, blocking until previous compilation
     * runs finish.
     *
     * @return a map of class name to bytecode
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
     * Invoke a public Java file-manager method by name when running on a JDK that exposes it.
     */
    @SuppressWarnings("unchecked")
    private <T> T invokeNamedMethodIfAvailable(final Location location, final String name, final T defaultValue) {
        final Method method;
        try {
            method = JavaFileManager.class.getMethod(name, Location.class);
        } catch (NoSuchMethodException e) {
            return defaultValue;
        }
        try {
            return (T) method.invoke(fileManager, location);
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException("Unable to access method " + name, e);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException) cause;
            if (cause instanceof Error)
                throw (Error) cause;
            throw new UnsupportedOperationException("Unable to invoke method " + name, cause);
        }
    }
}
