/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerUtilsIoTest {

    @Test
    public void writeTextDetectsNoChangeAndReadBytesMatches() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-io");
        Path filePath = tempDir.resolve("sample.txt");
        File file = filePath.toFile();

        boolean written = CompilerUtils.writeText(file, "hello");
        assertTrue(written, "First write should report changes");

        boolean unchanged = CompilerUtils.writeText(file, "hello");
        assertFalse(unchanged, "Repeat write with identical content should be treated as unchanged");

        boolean changed = CompilerUtils.writeText(file, "different");
        assertTrue(changed, "Modified content should trigger a rewrite");

        byte[] bytes = CompilerUtils.readBytes(file);
        assertNotNull(bytes, "Expected bytes to be read");
        String decoded = CompilerUtils.decodeUTF8(bytes);

        assertEquals("different", decoded, "Decoded bytes should match last write");
    }

    @Test
    public void writeBytesFailsWhenParentIsNotDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-io-error");
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.createFile(parentFile);

        File target = parentFile.resolve("child.bin").toFile();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CompilerUtils.writeBytes(target, new byte[]{1, 2, 3}));
        String message = ex.getMessage();
        assertNotNull(message, "Expected failure message");
        assertTrue(message.contains("Unable to create directory"), "Expected 'Unable to create directory' in: " + message);
    }

    @Test
    public void encodeDecodeUtf8Matches() throws Exception {
        byte[] bytes = CompilerUtils.encodeUTF8("sample-text");
        String value = CompilerUtils.decodeUTF8(bytes);
        assertEquals("sample-text", value, "UTF-8 encode/decode should round-trip");
    }

    @Test
    public void defineClassLoadsCompiledBytes() throws Exception {
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
            assertNotNull(bytes, "Expected compiled bytes for DefineClassTarget");

            Class<?> clazz = CompilerUtils.defineClass(Thread.currentThread().getContextClassLoader(),
                    "test.DefineClassTarget", bytes);
            assertEquals("test.DefineClassTarget", clazz.getName(), "Defined class should have expected name");
            Object instance = clazz.getDeclaredConstructor().newInstance();
            String id = (String) clazz.getMethod("id").invoke(instance);
            assertEquals("ok", id, "id() should match compiled source");

            Map<String, byte[]> compiledContext = cachedCompiler.compileFromJava(
                    "test.DefineClassTargetContext",
                    "package test; public class DefineClassTargetContext { public String ctx() { return \"ctx\"; } }",
                    myJavaFileManager);
            byte[] contextBytes = compiledContext.get("test.DefineClassTargetContext");
            assertNotNull(contextBytes, "Expected compiled bytes for DefineClassTargetContext");
            CompilerUtils.defineClass("test.DefineClassTargetContext", contextBytes);
            Class<?> contextDefined = Class.forName("test.DefineClassTargetContext");
            Object contextInstance = contextDefined.getDeclaredConstructor().newInstance();
            String ctx = (String) contextDefined.getMethod("ctx").invoke(contextInstance);
            assertEquals("ctx", ctx, "ctx() should match compiled source");
        }
    }

    @Test
    public void addClassPathHandlesMissingDirectory() {
        Path nonExisting = Paths.get("not-existing-" + System.nanoTime());
        boolean result = CompilerUtils.addClassPath(nonExisting.toString());
        assertFalse(result, "Missing directories should return false");
    }

    @Test
    public void addClassPathAddsExistingDirectory() throws Exception {
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
    public void readTextInlineShortcutAndReadBytesMissing() throws Exception {
        String inline = CompilerUtils.readText("=inline");
        assertEquals("inline", inline, "Inline readText should drop '=' prefix");

        Object missing = CompilerUtils.readBytes(new File("definitely-missing-" + System.nanoTime()));
        assertNull(missing, "Missing file should return null");

        Path tempFile = Files.createTempFile("compiler-utils-bytes", ".bin");
        Files.write(tempFile, "bytes".getBytes(StandardCharsets.UTF_8));
        byte[] present = CompilerUtils.readBytes(tempFile.toFile());
        assertEquals("bytes", new String(present, StandardCharsets.UTF_8), "readBytes should return file content");
    }

    @Test
    public void closeSwallowsExceptions() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        CompilerUtils.close(() -> {
            invoked.set(true);
            throw new IOException("boom");
        });
        assertTrue(invoked.get(), "Closeable should be invoked");
    }

    @Test
    public void closeIgnoresNullReference() throws Exception {
        CompilerUtils.close(null);
    }

    @Test
    public void getInputStreamSupportsInlineContent() throws Exception {
        try (InputStream is = CompilerUtils.getInputStream("=inline-data")) {
            String value = new String(readFully(is), StandardCharsets.UTF_8);
            assertEquals("inline-data", value, "Inline content should be returned");
        }
        Path tempFile = Files.createTempFile("compiler-utils-stream", ".txt");
        Files.write(tempFile, "file-data".getBytes(StandardCharsets.UTF_8));
        try (InputStream is = CompilerUtils.getInputStream(tempFile.toString())) {
            String value = new String(readFully(is), StandardCharsets.UTF_8);
            assertEquals("file-data", value, "File content should be returned");
        }
    }

    @Test
    public void getInputStreamRejectsEmptyFilename() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> CompilerUtils.getInputStream(""));
    }

    @Test
    public void getInputStreamUsesSlashFallback() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = AccessController.doPrivileged(
                (PrivilegedAction<ClassLoader>) () -> new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                    if ("/fallback-resource".equals(name)) {
                        return new ByteArrayInputStream("fallback".getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                }
        });
        Thread.currentThread().setContextClassLoader(loader);
        try (InputStream is = CompilerUtils.getInputStream("fallback-resource")) {
            assertEquals("fallback", new String(readFully(is), StandardCharsets.UTF_8), "Should fall back to leading slash lookup");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void sanitizePathPreventsTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> CompilerUtils.sanitizePath(Paths.get("..", "escape")));
    }

    @Test
    public void writeBytesCreatesMissingParentDirectories() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-parent");
        Path nested = tempDir.resolve("nested").resolve("file.bin");
        boolean changed = CompilerUtils.writeBytes(nested.toFile(), new byte[]{10, 20, 30});
        assertTrue(changed, "Path with missing parents should be created");
        assertTrue(Files.exists(nested), "Written file should exist");
    }

    @Test
    public void readBytesRejectsDirectories() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-dir");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CompilerUtils.readBytes(tempDir.toFile()));
        String message = ex.getMessage();
        assertNotNull(message, "Expected a message for directory read failure");
        assertTrue(message.contains("Unable to read file"), "Unexpected message: " + message);
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
