/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompilerUtilsIoTest {

    @Test
    void writeTextDetectsNoChangeAndReadBytesMatches() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-io");
        Path filePath = tempDir.resolve("sample.txt");
        File file = filePath.toFile();

        boolean written = CompilerUtils.writeText(file, "hello");
        assertTrue(written, "First write should report changes");

        boolean unchanged = CompilerUtils.writeText(file, "hello");
        assertTrue(!unchanged, "Repeat write with identical content should be treated as unchanged");

        boolean changed = CompilerUtils.writeText(file, "different");
        assertTrue(changed, "Modified content should trigger a rewrite");

        Method readBytes = CompilerUtils.class.getDeclaredMethod("readBytes", File.class);
        readBytes.setAccessible(true);
        byte[] bytes = (byte[]) readBytes.invoke(null, file);
        Method decodeUTF8 = CompilerUtils.class.getDeclaredMethod("decodeUTF8", byte[].class);
        decodeUTF8.setAccessible(true);
        String decoded = (String) decodeUTF8.invoke(null, bytes);

        assertEquals("different", decoded);
    }

    @Test
    void writeBytesFailsWhenParentIsNotDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-io-error");
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.createFile(parentFile);

        File target = parentFile.resolve("child.bin").toFile();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CompilerUtils.writeBytes(target, new byte[]{1, 2, 3}));
        assertTrue(ex.getMessage().contains("Unable to create directory"));
    }

    @Test
    void encodeDecodeUtf8Matches() throws Exception {
        Method encode = CompilerUtils.class.getDeclaredMethod("encodeUTF8", String.class);
        Method decode = CompilerUtils.class.getDeclaredMethod("decodeUTF8", byte[].class);
        encode.setAccessible(true);
        decode.setAccessible(true);

        byte[] bytes = (byte[]) encode.invoke(null, "sample-text");
        String value = (String) decode.invoke(null, bytes);
        assertEquals("sample-text", value);
    }

    @Test
    void defineClassLoadsCompiledBytes() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler required for tests");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            CachedCompiler cachedCompiler = new CachedCompiler(null, null);
            MyJavaFileManager myJavaFileManager = new MyJavaFileManager(fileManager);
            Map<String, byte[]> compiled = cachedCompiler.compileFromJava(
                    "test.DefineClassTarget",
                    "package test; public class DefineClassTarget { public String id() { return \"ok\"; } }",
                    myJavaFileManager);
            byte[] bytes = compiled.get("test.DefineClassTarget");
            assertNotNull(bytes);

            Class<?> clazz = CompilerUtils.defineClass(Thread.currentThread().getContextClassLoader(),
                    "test.DefineClassTarget", bytes);
            assertEquals("test.DefineClassTarget", clazz.getName());
            Object instance = clazz.getDeclaredConstructor().newInstance();
            String id = (String) clazz.getMethod("id").invoke(instance);
            assertEquals("ok", id);

            Map<String, byte[]> compiledContext = cachedCompiler.compileFromJava(
                    "test.DefineClassTargetContext",
                    "package test; public class DefineClassTargetContext { public String ctx() { return \"ctx\"; } }",
                    myJavaFileManager);
            byte[] contextBytes = compiledContext.get("test.DefineClassTargetContext");
            assertNotNull(contextBytes);
            CompilerUtils.defineClass("test.DefineClassTargetContext", contextBytes);
            Class<?> contextDefined = Class.forName("test.DefineClassTargetContext");
            Object contextInstance = contextDefined.getDeclaredConstructor().newInstance();
            String ctx = (String) contextDefined.getMethod("ctx").invoke(contextInstance);
            assertEquals("ctx", ctx);
        }
    }

    @Test
    void addClassPathHandlesMissingDirectory() {
        Path nonExisting = Paths.get("not-existing-" + System.nanoTime());
        boolean result = CompilerUtils.addClassPath(nonExisting.toString());
        assertTrue(!result, "Missing directories should return false");
    }

    @Test
    void addClassPathAddsExistingDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-classpath");
        String originalClasspath = System.getProperty("java.class.path");
        try {
            boolean added = CompilerUtils.addClassPath(tempDir.toAbsolutePath().toString());
            assertTrue(added, "Existing directory should be added");
            boolean second = CompilerUtils.addClassPath(tempDir.toAbsolutePath().toString());
            assertTrue(second, "Re-adding the same directory should report true because reset always occurs");
        } finally {
            System.setProperty("java.class.path", originalClasspath);
        }
    }

    @Test
    void readTextInlineShortcutAndReadBytesMissing() throws Exception {
        Method readText = CompilerUtils.class.getDeclaredMethod("readText", String.class);
        readText.setAccessible(true);
        String inline = (String) readText.invoke(null, "=inline");
        assertEquals("inline", inline);

        Method readBytes = CompilerUtils.class.getDeclaredMethod("readBytes", File.class);
        readBytes.setAccessible(true);
        Object missing = readBytes.invoke(null, new File("definitely-missing-" + System.nanoTime()));
        assertEquals(null, missing);

        Path tempFile = Files.createTempFile("compiler-utils-bytes", ".bin");
        Files.write(tempFile, "bytes".getBytes(StandardCharsets.UTF_8));
        byte[] present = (byte[]) readBytes.invoke(null, tempFile.toFile());
        assertEquals("bytes", new String(present, StandardCharsets.UTF_8));
    }

    @Test
    void closeSwallowsExceptions() throws Exception {
        Method closeMethod = CompilerUtils.class.getDeclaredMethod("close", Closeable.class);
        closeMethod.setAccessible(true);
        closeMethod.invoke(null, (Closeable) () -> {
            throw new IOException("boom");
        });
    }

    @Test
    void closeIgnoresNullReference() throws Exception {
        Method closeMethod = CompilerUtils.class.getDeclaredMethod("close", Closeable.class);
        closeMethod.setAccessible(true);
        closeMethod.invoke(null, new Object[]{null});
    }

    @Test
    void getInputStreamSupportsInlineContent() throws Exception {
        Method method = CompilerUtils.class.getDeclaredMethod("getInputStream", String.class);
        method.setAccessible(true);
        try (InputStream is = (InputStream) method.invoke(null, "=inline-data")) {
            String value = new String(readFully(is));
            assertEquals("inline-data", value);
        }
        Path tempFile = Files.createTempFile("compiler-utils-stream", ".txt");
        Files.write(tempFile, "file-data".getBytes(StandardCharsets.UTF_8));
        try (InputStream is = (InputStream) method.invoke(null, tempFile.toString())) {
            String value = new String(readFully(is));
            assertEquals("file-data", value);
        }
    }

    @Test
    void getInputStreamRejectsEmptyFilename() throws Exception {
        Method method = CompilerUtils.class.getDeclaredMethod("getInputStream", String.class);
        method.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> method.invoke(null, ""));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void getInputStreamUsesSlashFallback() throws Exception {
        Method method = CompilerUtils.class.getDeclaredMethod("getInputStream", String.class);
        method.setAccessible(true);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("/fallback-resource".equals(name)) {
                    return new ByteArrayInputStream("fallback".getBytes(StandardCharsets.UTF_8));
                }
                return null;
            }
        };
        Thread.currentThread().setContextClassLoader(loader);
        try (InputStream is = (InputStream) method.invoke(null, "fallback-resource")) {
            assertEquals("fallback", new String(readFully(is), StandardCharsets.UTF_8));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void sanitizePathPreventsTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> CompilerUtils.sanitizePath(Paths.get("..", "escape")));
    }

    @Test
    void writeBytesCreatesMissingParentDirectories() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-parent");
        Path nested = tempDir.resolve("nested").resolve("file.bin");
        boolean changed = CompilerUtils.writeBytes(nested.toFile(), new byte[]{10, 20, 30});
        assertTrue(changed, "Path with missing parents should be created");
        assertTrue(Files.exists(nested));
    }

    @Test
    void readBytesRejectsDirectories() throws Exception {
        Method readBytes = CompilerUtils.class.getDeclaredMethod("readBytes", File.class);
        readBytes.setAccessible(true);
        Path tempDir = Files.createTempDirectory("compiler-utils-dir");
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> readBytes.invoke(null, tempDir.toFile()));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        String message = ex.getCause().getMessage();
        assertTrue(message.contains("Unable to determine size") || message.contains("Unable to read file"));
    }

    private static byte[] readFully(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
