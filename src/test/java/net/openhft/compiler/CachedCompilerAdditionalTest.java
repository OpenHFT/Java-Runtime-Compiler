/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.AccessController;
import java.security.PrivilegedAction;

import static org.junit.jupiter.api.Assertions.*;

public class CachedCompilerAdditionalTest {

    @Test
    public void compileFromJavaReturnsBytecode() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "ToolProvider.getSystemJavaCompiler() must return a JavaCompiler instance for CachedCompiler to function");

        try (StandardJavaFileManager standardManager = compiler.getStandardFileManager(null, null, null)) {
            CachedCompiler cachedCompiler = new CachedCompiler(null, null);
            MyJavaFileManager fileManager = new MyJavaFileManager(standardManager);
            Map<String, byte[]> classes = cachedCompiler.compileFromJava(
                    "coverage.Sample",
                    "package coverage; public class Sample { public int value() { return 42; } }",
                    fileManager);
            byte[] bytes = classes.get("coverage.Sample");
            assertNotNull(bytes, "compileFromJava() must return bytecode for successfully compiled class 'coverage.Sample'");
            assertTrue(bytes.length > 0, "Bytecode array for 'coverage.Sample' must contain at least one byte representing valid JVM class file");
        }
    }

    @Test
    public void compileFromJavaReturnsEmptyMapOnFailure() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "ToolProvider.getSystemJavaCompiler() must return a JavaCompiler instance for CachedCompiler to function");
        try (StandardJavaFileManager standardManager = compiler.getStandardFileManager(null, null, null)) {
            CachedCompiler cachedCompiler = new CachedCompiler(null, null);
            MyJavaFileManager fileManager = new MyJavaFileManager(standardManager);
            Map<String, byte[]> classes = cachedCompiler.compileFromJava(
                    "coverage.Broken",
                    "package coverage; public class Broken { this does not compile }",
                    fileManager);
            assertTrue(classes.isEmpty(), "compileFromJava() must return empty map when source code contains syntax errors and compilation fails");
        }
    }

    @Test
    public void updateFileManagerForClassLoaderInvokesConsumer() throws Exception {
        CachedCompiler compiler = new CachedCompiler(null, null);
        ClassLoader loader = AccessController.doPrivileged(
                (PrivilegedAction<ClassLoader>) () -> new ClassLoader() {
                });
        compiler.loadFromJava(loader, "coverage.UpdateTarget", "package coverage; public class UpdateTarget {}");

        AtomicBoolean invoked = new AtomicBoolean(false);
        compiler.updateFileManagerForClassLoader(loader, fm -> invoked.set(true));
        assertTrue(invoked.get(), "updateFileManagerForClassLoader() must invoke consumer when file manager exists for the specified ClassLoader");
    }

    @Test
    public void updateFileManagerNoOpWhenClassLoaderUnknown() {
        CachedCompiler compiler = new CachedCompiler(null, null);
        AtomicBoolean invoked = new AtomicBoolean(false);
        ClassLoader loader = AccessController.doPrivileged(
                (PrivilegedAction<ClassLoader>) () -> new ClassLoader() {
                });
        compiler.updateFileManagerForClassLoader(loader, fm -> invoked.set(true));
        assertFalse(invoked.get(), "updateFileManagerForClassLoader() must not invoke consumer when no file manager is registered for the ClassLoader");
    }

    @Test
    public void closeClosesAllManagedFileManagers() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "ToolProvider.getSystemJavaCompiler() must return a JavaCompiler instance for CachedCompiler to function");
        CachedCompiler cachedCompiler = new CachedCompiler(null, null);
        AtomicBoolean closed = new AtomicBoolean(false);
        cachedCompiler.setFileManagerOverride(standard -> new TrackingFileManager(standard, closed));

        ClassLoader loader = AccessController.doPrivileged(
                (PrivilegedAction<ClassLoader>) () -> new ClassLoader() {
                });
        cachedCompiler.loadFromJava(loader, "coverage.CloseTarget", "package coverage; public class CloseTarget {}");
        cachedCompiler.close();
        assertTrue(closed.get(), "CachedCompiler.close() must propagate close() call to all managed JavaFileManager instances to release resources");
    }

    @Test
    public void createDefaultWriterFlushesOnClose() throws Exception {
        PrintStream originalErr = System.err;
        TrackingOutputStream capturedErr = new TrackingOutputStream();
        PrintStream replacementErr = new PrintStream(capturedErr, false, StandardCharsets.UTF_8.name());
        System.setErr(replacementErr);
        try {
            PrintWriter writer = CachedCompiler.createDefaultWriter();
            writer.print("exercise-default-writer");
            int flushBeforeClose = capturedErr.flushCalls();
            writer.close(); // ensures the overridden close() path is covered
            assertTrue(capturedErr.flushCalls() > flushBeforeClose, "PrintWriter.close() from createDefaultWriter() must flush System.err to ensure buffered diagnostic output is visible");
            String output = new String(capturedErr.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(output.contains("exercise-default-writer"), "System.err must contain 'exercise-default-writer' text written via PrintWriter from createDefaultWriter()");
            assertFalse(capturedErr.isClosed(), "PrintWriter.close() from createDefaultWriter() must not close underlying System.err stream to preserve system error output");
        } finally {
            System.setErr(originalErr);
            replacementErr.close();
        }
    }

    @Test
    public void validateClassNameAllowsDescriptorForms() throws Exception {
        CachedCompiler.validateClassName("module-info");
        CachedCompiler.validateClassName("example.package-info");
        CachedCompiler.validateClassName("example.deep.package-info");

        assertThrows(IllegalArgumentException.class,
                () -> CachedCompiler.validateClassName("example.Invalid-"));

        assertThrows(IllegalArgumentException.class,
                () -> CachedCompiler.validateClassName("example..impl"));

        assertThrows(IllegalArgumentException.class,
                () -> CachedCompiler.validateClassName("example.Invalid?Name"));
    }

    @Test
    public void safeResolvePreventsPathTraversal() throws Exception {
        Path root = Files.createTempDirectory("cached-compiler-safe");
        try {
            File resolved = CachedCompiler.safeResolve(root.toFile(), "valid/Name.class");
            assertTrue(resolved.toPath().startsWith(root), "safeResolve() must return path starting with root directory when resolving 'valid/Name.class' to prevent directory traversal attacks");

            assertThrows(IllegalArgumentException.class,
                    () -> CachedCompiler.safeResolve(root.toFile(), "../escape"));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void writesSourceAndClassFilesWhenDirectoriesProvided() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "ToolProvider.getSystemJavaCompiler() must return a JavaCompiler instance for CachedCompiler to function");

        Path sourceDir = Files.createTempDirectory("cached-compiler-src");
        Path classDir = Files.createTempDirectory("cached-compiler-classes");
        try {
            CachedCompiler firstPass = new CachedCompiler(sourceDir.toFile(), classDir.toFile());

            String className = "coverage.FileOutput";
            String versionOne = "package coverage; public class FileOutput { public String value() { return \"v1\"; } }";
            ClassLoader loaderOne = AccessController.doPrivileged(
                    (PrivilegedAction<ClassLoader>) () -> new ClassLoader() {
                    });
            firstPass.loadFromJava(loaderOne, className, versionOne);
            firstPass.close();

            Path sourceFile = sourceDir.resolve("coverage/FileOutput.java");
            Path classFile = classDir.resolve("coverage/FileOutput.class");
            assertTrue(Files.exists(sourceFile), "CachedCompiler must write 'coverage/FileOutput.java' to source directory when sourceDir is configured");
            assertTrue(Files.exists(classFile), "CachedCompiler must write compiled 'coverage/FileOutput.class' to class directory when classDir is configured");
            byte[] firstBytes = Files.readAllBytes(classFile);

            CachedCompiler secondPass = new CachedCompiler(sourceDir.toFile(), classDir.toFile());
            String versionTwo = "package coverage; public class FileOutput { public String value() { return \"v2\"; } }";
            ClassLoader loaderTwo = AccessController.doPrivileged(
                    (PrivilegedAction<ClassLoader>) () -> new ClassLoader() {
                    });
            secondPass.loadFromJava(loaderTwo, className, versionTwo);
            secondPass.close();

            byte[] updatedBytes = Files.readAllBytes(classFile);
            assertFalse(Arrays.equals(firstBytes, updatedBytes), "Recompiling 'coverage.FileOutput' with modified source code must produce different bytecode from previous compilation");

            Path backupFile = classDir.resolve("coverage/FileOutput.class.bak");
            assertFalse(Files.exists(backupFile), "CachedCompiler must remove temporary '.class.bak' backup file after successfully updating 'coverage/FileOutput.class'");
        } finally {
            deleteRecursively(classDir);
            deleteRecursively(sourceDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int flushCalls;
        private boolean closed;

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public void flush() {
            flushCalls++;
        }

        @Override
        public void close() {
            closed = true;
        }

        int flushCalls() {
            return flushCalls;
        }

        boolean isClosed() {
            return closed;
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }

    private static final class TrackingFileManager extends MyJavaFileManager {
        private final AtomicBoolean closedFlag;

        TrackingFileManager(StandardJavaFileManager delegate, AtomicBoolean closedFlag) {
            super(delegate);
            this.closedFlag = closedFlag;
        }

        @Override
        public void close() throws IOException {
            closedFlag.set(true);
            super.close();
        }
    }
}
