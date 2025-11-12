/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Test;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class MyJavaFileManagerTest {

    @Test
    public void bufferedClassReturnedFromInput() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        try (StandardJavaFileManager delegate = compiler.getStandardFileManager(null, null, null)) {
            MyJavaFileManager manager = new MyJavaFileManager(delegate);

            JavaFileObject fileObject = manager.getJavaFileForOutput(StandardLocation.CLASS_OUTPUT,
                    "example.Buffer", JavaFileObject.Kind.CLASS, null);
            byte[] payload = new byte[]{1, 2, 3, 4};
            try (OutputStream os = fileObject.openOutputStream()) {
                os.write(payload);
            }

            JavaFileObject in = manager.getJavaFileForInput(StandardLocation.CLASS_OUTPUT,
                    "example.Buffer", JavaFileObject.Kind.CLASS);
            try (InputStream is = in.openInputStream()) {
                byte[] read = readFully(is);
                assertArrayEquals(payload, read);
            }

            manager.clearBuffers();
            assertTrue("Buffers should be cleared", manager.getAllBuffers().isEmpty());

            // Delegate path for non CLASS_OUTPUT locations
            manager.getJavaFileForInput(StandardLocation.CLASS_PATH,
                    "java.lang.Object", JavaFileObject.Kind.CLASS);
        }
    }

    @Test
    public void getJavaFileForInputDelegatesWhenBufferMissing() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        try (StandardJavaFileManager base = compiler.getStandardFileManager(null, null, null)) {
            AtomicBoolean delegated = new AtomicBoolean(false);
            JavaFileObject expected = new SimpleJavaFileObject(URI.create("string:///expected"), JavaFileObject.Kind.CLASS) {
                @Override
                public InputStream openInputStream() {
                    return new ByteArrayInputStream(new byte[0]);
                }
            };
            StandardJavaFileManager proxy = (StandardJavaFileManager) Proxy.newProxyInstance(
                    StandardJavaFileManager.class.getClassLoader(),
                    new Class[]{StandardJavaFileManager.class},
                    (proxyInstance, method, args) -> {
                        if ("getJavaFileForInput".equals(method.getName())) {
                            delegated.set(true);
                            return expected;
                        }
                        try {
                            return method.invoke(base, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            MyJavaFileManager manager = new MyJavaFileManager(proxy);
            Field buffersField = MyJavaFileManager.class.getDeclaredField("buffers");
            buffersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, CloseableByteArrayOutputStream> buffers =
                    (Map<String, CloseableByteArrayOutputStream>) buffersField.get(manager);
            buffers.put("example.KindMismatch", new CloseableByteArrayOutputStream());

            JavaFileObject result = manager.getJavaFileForInput(StandardLocation.CLASS_OUTPUT,
                    "example.KindMismatch", JavaFileObject.Kind.SOURCE);
            assertTrue("Delegate should be consulted when buffer missing", delegated.get());
            assertTrue("Result should match delegate outcome", result == expected);
        }
    }

    @Test
    public void delegatingMethodsPassThroughToUnderlyingManager() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        try (StandardJavaFileManager base = compiler.getStandardFileManager(null, null, null)) {
            MyJavaFileManager manager = new MyJavaFileManager(base);

            try {
                FileObject a = manager.getJavaFileForOutput(StandardLocation.CLASS_OUTPUT, "example.A", JavaFileObject.Kind.CLASS, null);
                FileObject b = manager.getJavaFileForOutput(StandardLocation.CLASS_OUTPUT, "example.B", JavaFileObject.Kind.CLASS, null);
                manager.isSameFile(a, b);
            } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                // Some JDKs do not support these operations; acceptable for delegation coverage.
            }

            try {
                manager.getFileForInput(StandardLocation.CLASS_PATH, "java/lang", "Object.class");
                manager.getFileForOutput(StandardLocation.CLASS_OUTPUT, "example", "Dummy.class", null);
            } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                // Accept lack of support on older toolchains.
            }

            manager.close();
        }
    }

    @Test
    public void listLocationsForModulesAndInferModuleNameDeferToDelegate() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        try (StandardJavaFileManager delegate = compiler.getStandardFileManager(null, null, null)) {
            MyJavaFileManager manager = new MyJavaFileManager(delegate);
            javax.tools.JavaFileManager.Location modulesLocation = resolveSystemModules();
            if (modulesLocation != null) {
                try {
                    Iterable<Set<javax.tools.JavaFileManager.Location>> locations =
                            manager.listLocationsForModules(modulesLocation);
                    for (Set<javax.tools.JavaFileManager.Location> ignored : locations) {
                        // no-op
                    }
                } catch (UnsupportedOperationException ignored) {
                    // Delegate does not expose module support on this JDK.
                }
            }
            try {
                manager.inferModuleName(StandardLocation.CLASS_PATH);
            } catch (UnsupportedOperationException ignored) {
                // Method not available on older JDKs; acceptable.
            }
        }
    }

    @Test
    public void invokeNamedMethodHandlesMissingMethods() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        try (StandardJavaFileManager delegate = compiler.getStandardFileManager(null, null, null)) {
            MyJavaFileManager manager = new MyJavaFileManager(delegate);
            java.lang.reflect.Method method = MyJavaFileManager.class.getDeclaredMethod(
                    "invokeNamedMethodIfAvailable", javax.tools.JavaFileManager.Location.class, String.class);
            method.setAccessible(true);
            try {
                method.invoke(manager, StandardLocation.CLASS_PATH, "nonExistingMethod");
                fail("Expected UnsupportedOperationException when method is absent");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof UnsupportedOperationException);
            }
        }
    }

    @Test
    public void invokeNamedMethodWrapsInvocationFailures() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        try (StandardJavaFileManager base = compiler.getStandardFileManager(null, null, null)) {
            StandardJavaFileManager proxy = (StandardJavaFileManager) Proxy.newProxyInstance(
                    StandardJavaFileManager.class.getClassLoader(),
                    new Class[]{StandardJavaFileManager.class},
                    (proxyInstance, method, args) -> {
                        if ("listLocationsForModules".equals(method.getName())) {
                            throw new InvocationTargetException(new IOException("forced"));
                        }
                        try {
                            return method.invoke(base, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            MyJavaFileManager manager = new MyJavaFileManager(proxy);
            java.lang.reflect.Method method = MyJavaFileManager.class.getDeclaredMethod(
                    "invokeNamedMethodIfAvailable", javax.tools.JavaFileManager.Location.class, String.class);
            method.setAccessible(true);
            try {
                method.invoke(manager, StandardLocation.CLASS_PATH, "listLocationsForModules");
                fail("Expected invocation failure to be wrapped");
            } catch (InvocationTargetException expected) {
                Throwable cause = expected.getCause();
                if (cause instanceof InvocationTargetException) {
                    cause = ((InvocationTargetException) cause).getCause();
                }
                assertTrue("Unexpected cause: " + cause,
                        cause instanceof UnsupportedOperationException || cause instanceof IOException);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void getAllBuffersSkipsEntriesWhenFutureFails() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        try (StandardJavaFileManager delegate = compiler.getStandardFileManager(null, null, null)) {
            MyJavaFileManager manager = new MyJavaFileManager(delegate);
            Field buffersField = MyJavaFileManager.class.getDeclaredField("buffers");
            buffersField.setAccessible(true);
            Map<String, CloseableByteArrayOutputStream> buffers =
                    (Map<String, CloseableByteArrayOutputStream>) buffersField.get(manager);
            FaultyByteArrayOutputStream faulty = new FaultyByteArrayOutputStream();
            synchronized (buffers) {
                buffers.put("coverage.Faulty", faulty);
            }
            Map<String, byte[]> collected = manager.getAllBuffers();
            assertTrue("Faulty entries should be skipped when the close future fails", collected.isEmpty());
        }
    }

    private static final class FaultyByteArrayOutputStream extends CloseableByteArrayOutputStream {
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        FaultyByteArrayOutputStream() {
            future.completeExceptionally(new RuntimeException("faulty"));
        }

        @Override
        public CompletableFuture<?> closeFuture() {
            return future;
        }
    }

    private static javax.tools.JavaFileManager.Location resolveSystemModules() {
        try {
            return StandardLocation.valueOf("SYSTEM_MODULES");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static byte[] readFully(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
