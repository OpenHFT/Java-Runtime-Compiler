/*
 * Copyright 2025 chronicle.software
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

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CompilerUtilsIoTest {

    @Test
    public void writeTextDetectsNoChangeAndReadBytesMatches() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-io");
        Path filePath = tempDir.resolve("sample.txt");
        File file = filePath.toFile();

        boolean written = CompilerUtils.writeText(file, "hello");
        assertTrue("First write should report changes", written);

        boolean unchanged = CompilerUtils.writeText(file, "hello");
        assertTrue("Repeat write with identical content should be treated as unchanged", !unchanged);

        boolean changed = CompilerUtils.writeText(file, "different");
        assertTrue("Modified content should trigger a rewrite", changed);

        Method readBytes = CompilerUtils.class.getDeclaredMethod("readBytes", File.class);
        readBytes.setAccessible(true);
        byte[] bytes = (byte[]) readBytes.invoke(null, file);
        Method decodeUTF8 = CompilerUtils.class.getDeclaredMethod("decodeUTF8", byte[].class);
        decodeUTF8.setAccessible(true);
        String decoded = (String) decodeUTF8.invoke(null, bytes);

        assertEquals("different", decoded);
    }

    @Test
    public void writeBytesFailsWhenParentIsNotDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-io-error");
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.createFile(parentFile);

        File target = parentFile.resolve("child.bin").toFile();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CompilerUtils.writeBytes(target, new byte[]{1, 2, 3}));
        assertTrue(ex.getMessage().contains("Unable to create directory"));
    }

    @Test
    public void encodeDecodeUtf8Matches() throws Exception {
        Method encode = CompilerUtils.class.getDeclaredMethod("encodeUTF8", String.class);
        Method decode = CompilerUtils.class.getDeclaredMethod("decodeUTF8", byte[].class);
        encode.setAccessible(true);
        decode.setAccessible(true);

        byte[] bytes = (byte[]) encode.invoke(null, "sample-text");
        String value = (String) decode.invoke(null, bytes);
        assertEquals("sample-text", value);
    }

    @Test
    public void defineClassLoadsCompiledBytes() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JDK compiler required for tests", compiler);
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
    public void addClassPathHandlesMissingDirectory() {
        Path nonExisting = Paths.get("not-existing-" + System.nanoTime());
        boolean result = CompilerUtils.addClassPath(nonExisting.toString());
        assertTrue("Missing directories should return false", !result);
    }

    @Test
    public void addClassPathAddsExistingDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("compiler-utils-classpath");
        String originalClasspath = System.getProperty("java.class.path");
        try {
            boolean added = CompilerUtils.addClassPath(tempDir.toAbsolutePath().toString());
            assertTrue("Existing directory should be added", added);
            boolean second = CompilerUtils.addClassPath(tempDir.toAbsolutePath().toString());
            assertTrue("Re-adding the same directory should report true because reset always occurs", second);
        } finally {
            System.setProperty("java.class.path", originalClasspath);
        }
    }

    @Test
    public void readTextInlineShortcutAndReadBytesMissing() throws Exception {
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
    public void closeSwallowsExceptions() throws Exception {
        Method closeMethod = CompilerUtils.class.getDeclaredMethod("close", Closeable.class);
        closeMethod.setAccessible(true);
        closeMethod.invoke(null, (Closeable) () -> {
            throw new IOException("boom");
        });
    }

    @Test
    public void getInputStreamSupportsInlineContent() throws Exception {
        Method method = CompilerUtils.class.getDeclaredMethod("getInputStream", String.class);
        method.setAccessible(true);
        try (InputStream is = (InputStream) method.invoke(null, "=inline-data")) {
            String value = new String(is.readAllBytes());
            assertEquals("inline-data", value);
        }
        Path tempFile = Files.createTempFile("compiler-utils-stream", ".txt");
        Files.write(tempFile, "file-data".getBytes(StandardCharsets.UTF_8));
        try (InputStream is = (InputStream) method.invoke(null, tempFile.toString())) {
            String value = new String(is.readAllBytes());
            assertEquals("file-data", value);
        }
    }
}
