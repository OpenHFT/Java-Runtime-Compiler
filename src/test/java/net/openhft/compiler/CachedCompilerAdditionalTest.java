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
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class CachedCompilerAdditionalTest {

    @Test
    public void compileFromJavaReturnsBytecode() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);

        try (StandardJavaFileManager standardManager = compiler.getStandardFileManager(null, null, null)) {
            CachedCompiler cachedCompiler = new CachedCompiler(null, null);
            MyJavaFileManager fileManager = new MyJavaFileManager(standardManager);
            Map<String, byte[]> classes = cachedCompiler.compileFromJava(
                    "coverage.Sample",
                    "package coverage; public class Sample { public int value() { return 42; } }",
                    fileManager);
            byte[] bytes = classes.get("coverage.Sample");
            assertNotNull(bytes);
            assertTrue(bytes.length > 0);
        }
    }

    @Test
    public void compileFromJavaReturnsEmptyMapOnFailure() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        try (StandardJavaFileManager standardManager = compiler.getStandardFileManager(null, null, null)) {
            CachedCompiler cachedCompiler = new CachedCompiler(null, null);
            MyJavaFileManager fileManager = new MyJavaFileManager(standardManager);
            Map<String, byte[]> classes = cachedCompiler.compileFromJava(
                    "coverage.Broken",
                    "package coverage; public class Broken { this does not compile }",
                    fileManager);
            assertTrue("Broken source should not produce classes", classes.isEmpty());
        }
    }

    @Test
    public void updateFileManagerForClassLoaderInvokesConsumer() throws Exception {
        CachedCompiler compiler = new CachedCompiler(null, null);
        ClassLoader loader = new ClassLoader() {
        };
        compiler.loadFromJava(loader, "coverage.UpdateTarget", "package coverage; public class UpdateTarget {}");

        AtomicBoolean invoked = new AtomicBoolean(false);
        compiler.updateFileManagerForClassLoader(loader, fm -> invoked.set(true));
        assertTrue("Consumer should be invoked when manager exists", invoked.get());
    }

    @Test
    public void updateFileManagerNoOpWhenClassLoaderUnknown() {
        CachedCompiler compiler = new CachedCompiler(null, null);
        AtomicBoolean invoked = new AtomicBoolean(false);
        compiler.updateFileManagerForClassLoader(new ClassLoader() {
        }, fm -> invoked.set(true));
        assertTrue("Consumer should not be invoked when manager missing", !invoked.get());
    }

    @Test
    public void closeClosesAllManagedFileManagers() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);
        CachedCompiler cachedCompiler = new CachedCompiler(null, null);
        AtomicBoolean closed = new AtomicBoolean(false);
        cachedCompiler.setFileManagerOverride(standard -> new TrackingFileManager(standard, closed));

        ClassLoader loader = new ClassLoader() {
        };
        cachedCompiler.loadFromJava(loader, "coverage.CloseTarget", "package coverage; public class CloseTarget {}");
        cachedCompiler.close();
        assertTrue("Close should propagate to file managers", closed.get());
    }

    @Test
    public void createDefaultWriterFlushesOnClose() throws Exception {
        Method factory = CachedCompiler.class.getDeclaredMethod("createDefaultWriter");
        factory.setAccessible(true);
        PrintWriter writer = (PrintWriter) factory.invoke(null);
        writer.println("exercise-default-writer");
        writer.close(); // ensures the overridden close() path is covered
    }

    @Test
    public void validateClassNameAllowsDescriptorForms() throws Exception {
        Method validate = CachedCompiler.class.getDeclaredMethod("validateClassName", String.class);
        validate.setAccessible(true);

        validate.invoke(null, "module-info");
        validate.invoke(null, "example.package-info");
        validate.invoke(null, "example.deep.package-info");

        InvocationTargetException trailingHyphen = assertThrows(InvocationTargetException.class,
                () -> validate.invoke(null, "example.Invalid-"));
        assertTrue(trailingHyphen.getCause() instanceof IllegalArgumentException);

        InvocationTargetException emptySegment = assertThrows(InvocationTargetException.class,
                () -> validate.invoke(null, "example..impl"));
        assertTrue(emptySegment.getCause() instanceof IllegalArgumentException);

        InvocationTargetException invalidCharacter = assertThrows(InvocationTargetException.class,
                () -> validate.invoke(null, "example.Invalid?Name"));
        assertTrue(invalidCharacter.getCause() instanceof IllegalArgumentException);
    }

    @Test
    public void safeResolvePreventsPathTraversal() throws Exception {
        Method method = CachedCompiler.class.getDeclaredMethod("safeResolve", File.class, String.class);
        method.setAccessible(true);
        Path root = Files.createTempDirectory("cached-compiler-safe");
        try {
            File resolved = (File) method.invoke(null, root.toFile(), "valid/Name.class");
            assertTrue(resolved.toPath().startsWith(root));

            InvocationTargetException traversal = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(null, root.toFile(), "../escape"));
            assertTrue(traversal.getCause() instanceof IllegalArgumentException);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void writesSourceAndClassFilesWhenDirectoriesProvided() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", compiler);

        Path sourceDir = Files.createTempDirectory("cached-compiler-src");
        Path classDir = Files.createTempDirectory("cached-compiler-classes");
        try {
            CachedCompiler firstPass = new CachedCompiler(sourceDir.toFile(), classDir.toFile());

            String className = "coverage.FileOutput";
            String versionOne = "package coverage; public class FileOutput { public String value() { return \"v1\"; } }";
            ClassLoader loaderOne = new ClassLoader() {
            };
            firstPass.loadFromJava(loaderOne, className, versionOne);
            firstPass.close();

            Path sourceFile = sourceDir.resolve("coverage/FileOutput.java");
            Path classFile = classDir.resolve("coverage/FileOutput.class");
            assertTrue("Source file should be emitted", Files.exists(sourceFile));
            assertTrue("Class file should be emitted", Files.exists(classFile));
            byte[] firstBytes = Files.readAllBytes(classFile);

            CachedCompiler secondPass = new CachedCompiler(sourceDir.toFile(), classDir.toFile());
            String versionTwo = "package coverage; public class FileOutput { public String value() { return \"v2\"; } }";
            ClassLoader loaderTwo = new ClassLoader() {
            };
            secondPass.loadFromJava(loaderTwo, className, versionTwo);
            secondPass.close();

            byte[] updatedBytes = Files.readAllBytes(classFile);
            assertTrue("Updating the source should change emitted bytecode", !Arrays.equals(firstBytes, updatedBytes));

            Path backupFile = classDir.resolve("coverage/FileOutput.class.bak");
            assertTrue("Backup should be cleaned up", !Files.exists(backupFile));
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
