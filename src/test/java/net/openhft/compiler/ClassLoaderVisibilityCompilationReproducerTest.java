/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Minimal reproducer for compilation against types available only through a
 * child application class loader.
 *
 * <p>Run with:</p>
 * <pre>
 * mvn -q -Dtest=ClassLoaderVisibilityCompilationReproducerTest test
 * </pre>
 *
 * <p>The first test characterises the current failure while keeping the test
 * suite green: both application types and their class-file resources are
 * visible through the target loader, but the standard compiler file manager
 * cannot resolve them. The second test is a diagnostic control showing that
 * compilation succeeds when those same class files are explicitly exposed to
 * the compiler. It is not a production implementation.</p>
 */
public class ClassLoaderVisibilityCompilationReproducerTest {
    private static final String CONTRACT_NAME = "isolated.api.LoaderOnlyContract";
    private static final String PAYLOAD_NAME = "isolated.model.LoaderOnlyPayload";
    private static final String GENERATED_NAME = "isolated.generated.GeneratedAdapter";
    private static final String CONTRACT_SOURCE =
            "package isolated.api; " +
                    "public interface LoaderOnlyContract { Object value(); }";
    private static final String PAYLOAD_SOURCE =
            "package isolated.model; " +
                    "public class LoaderOnlyPayload { " +
                    "public String text() { return \"visible\"; } " +
                    "}";
    private static final String GENERATED_SOURCE =
            "package isolated.generated; " +
                    "import isolated.api.LoaderOnlyContract; " +
                    "public class GeneratedAdapter implements LoaderOnlyContract { " +
                    "public isolated.model.LoaderOnlyPayload value() { " +
                    "return new isolated.model.LoaderOnlyPayload(); " +
                    "} " +
                    "}";

    @Test
    public void standardFileManagerCannotResolveTypesAvailableOnlyToTargetLoader() throws Exception {
        try (LoaderFixture fixture = newLoaderFixture()) {
            Class<?> contractClass = fixture.loader.loadClass(CONTRACT_NAME);
            Class<?> payloadClass = fixture.loader.loadClass(PAYLOAD_NAME);
            assertSame(fixture.loader, contractClass.getClassLoader());
            assertSame(fixture.loader, payloadClass.getClassLoader());
            assertClassResourceVisible(fixture.loader, CONTRACT_NAME);
            assertClassResourceVisible(fixture.loader, PAYLOAD_NAME);

            StringWriter diagnostics = new StringWriter();
            ClassNotFoundException failure;
            try (CachedCompiler compiler = new CachedCompiler(null, null)) {
                failure = assertThrows(ClassNotFoundException.class,
                        () -> compiler.loadFromJava(
                                fixture.loader,
                                GENERATED_NAME,
                                GENERATED_SOURCE,
                                new PrintWriter(diagnostics, true)));
            }

            String diagnosticText = diagnostics.toString();
            assertNotNull("The final exception should have a message", failure.getMessage());
            assertTrue("The final exception should identify the class that was not generated",
                    failure.getMessage().contains(GENERATED_NAME));
            assertTrue("The compiler should report the unresolved imported type:\n" + diagnosticText,
                    diagnosticText.contains("isolated.api"));
            assertTrue("The compiler should report the unresolved fully qualified type:\n" + diagnosticText,
                    diagnosticText.contains("isolated.model"));
            assertNull("Compilation failure must not define a partial generated class",
                    fixture.loader.findLoaded(GENERATED_NAME));
        }
    }

    @Test
    public void explicitClassFileBridgeIsACompilingControl() throws Exception {
        try (LoaderFixture fixture = newLoaderFixture()) {
            Class<?> contractClass = fixture.loader.loadClass(CONTRACT_NAME);
            Class<?> payloadClass = fixture.loader.loadClass(PAYLOAD_NAME);

            Class<?> generatedClass;
            try (CachedCompiler compiler = new CachedCompiler(null, null)) {
                compiler.setFileManagerOverride(standardManager ->
                        new ClassFileBridge(standardManager, fixture.definitions));
                generatedClass = compiler.loadFromJava(
                        fixture.loader,
                        GENERATED_NAME,
                        GENERATED_SOURCE);
            }

            Object generated = generatedClass.getDeclaredConstructor().newInstance();
            Object payload = generatedClass.getMethod("value").invoke(generated);
            assertSame(fixture.loader, generatedClass.getClassLoader());
            assertTrue(contractClass.isAssignableFrom(generatedClass));
            assertSame(payloadClass, payload.getClass());
        }
    }

    private static LoaderFixture newLoaderFixture() throws IOException {
        Map<String, byte[]> definitions = new LinkedHashMap<>();
        definitions.put(CONTRACT_NAME, compileClass(CONTRACT_NAME, CONTRACT_SOURCE));
        definitions.put(PAYLOAD_NAME, compileClass(PAYLOAD_NAME, PAYLOAD_SOURCE));

        Path classRoot = Files.createTempDirectory("isolated-loader-classes");
        try {
            for (Map.Entry<String, byte[]> definition : definitions.entrySet()) {
                Path classFile = classRoot.resolve(
                        definition.getKey().replace('.', '/') + JavaFileObject.Kind.CLASS.extension);
                Files.createDirectories(classFile.getParent());
                Files.write(classFile, definition.getValue());
            }
            IsolatedClassLoader loader = new IsolatedClassLoader(classRoot.toUri().toURL());
            return new LoaderFixture(loader, classRoot, definitions);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                deleteRecursively(classRoot);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static byte[] compileClass(String className, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("A full JDK is required to run this reproducer");
        }

        try (StandardJavaFileManager standardManager =
                     compiler.getStandardFileManager(null, null, null)) {
            MyJavaFileManager fileManager = new MyJavaFileManager(standardManager);
            Map<String, byte[]> classes = new CachedCompiler(null, null).compileFromJava(
                    className,
                    source,
                    fileManager);
            byte[] bytes = classes.get(className);
            if (bytes == null) {
                throw new AssertionError("Failed to compile loader-only type " + className);
            }
            return bytes;
        }
    }

    private static void assertClassResourceVisible(ClassLoader loader,
                                                   String binaryName) throws IOException {
        String resourceName = binaryName.replace('.', '/') + JavaFileObject.Kind.CLASS.extension;
        assertNotNull("The loader should expose resource " + resourceName,
                loader.getResource(resourceName));
        try (InputStream input = loader.getResourceAsStream(resourceName)) {
            assertNotNull("The loader should open resource " + resourceName, input);
            assertTrue("The class-file resource should not be empty", input.read() >= 0);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private static final class LoaderFixture implements AutoCloseable {
        private final IsolatedClassLoader loader;
        private final Path classRoot;
        private final Map<String, byte[]> definitions;

        private LoaderFixture(IsolatedClassLoader loader,
                              Path classRoot,
                              Map<String, byte[]> definitions) {
            this.loader = loader;
            this.classRoot = classRoot;
            this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        }

        @Override
        public void close() throws IOException {
            try {
                loader.close();
            } finally {
                deleteRecursively(classRoot);
            }
        }
    }

    private static final class IsolatedClassLoader extends URLClassLoader {
        private IsolatedClassLoader(URL classRoot) {
            super(new URL[]{classRoot},
                    ClassLoaderVisibilityCompilationReproducerTest.class.getClassLoader());
        }

        private Class<?> findLoaded(String name) {
            synchronized (getClassLoadingLock(name)) {
                return findLoadedClass(name);
            }
        }
    }

    private static final class ClassFileBridge extends MyJavaFileManager {
        private final Map<String, InputClassFile> dependencies;

        private ClassFileBridge(StandardJavaFileManager standardManager,
                                Map<String, byte[]> definitions) {
            super(standardManager);
            Map<String, InputClassFile> classFiles = new LinkedHashMap<>();
            definitions.forEach((name, bytes) ->
                    classFiles.put(name, new InputClassFile(name, bytes)));
            this.dependencies = Collections.unmodifiableMap(classFiles);
        }

        @Override
        public JavaFileObject getJavaFileForInput(Location location,
                                                  String className,
                                                  JavaFileObject.Kind kind) throws IOException {
            if (location == StandardLocation.CLASS_PATH && kind == JavaFileObject.Kind.CLASS) {
                JavaFileObject dependency = dependencies.get(className);
                if (dependency != null) {
                    return dependency;
                }
            }
            return super.getJavaFileForInput(location, className, kind);
        }

        @Override
        public synchronized Iterable<JavaFileObject> list(Location location,
                                                          String packageName,
                                                          Set<JavaFileObject.Kind> kinds,
                                                          boolean recurse) throws IOException {
            Iterable<JavaFileObject> delegated = super.list(location, packageName, kinds, recurse);
            if (location != StandardLocation.CLASS_PATH ||
                    !kinds.contains(JavaFileObject.Kind.CLASS)) {
                return delegated;
            }

            List<JavaFileObject> combined = new ArrayList<>();
            for (JavaFileObject file : delegated) {
                combined.add(file);
            }
            for (InputClassFile dependency : dependencies.values()) {
                if (dependency.isInPackage(packageName, recurse)) {
                    combined.add(dependency);
                }
            }
            return Collections.unmodifiableList(combined);
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof InputClassFile) {
                return ((InputClassFile) file).binaryName;
            }
            return super.inferBinaryName(location, file);
        }
    }

    private static final class InputClassFile extends SimpleJavaFileObject {
        private final String binaryName;
        private final byte[] bytes;

        private InputClassFile(String binaryName, byte[] bytes) {
            super(URI.create("memory:///" +
                    binaryName.replace('.', '/') +
                    JavaFileObject.Kind.CLASS.extension), JavaFileObject.Kind.CLASS);
            this.binaryName = binaryName;
            this.bytes = bytes.clone();
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        private boolean isInPackage(String packageName, boolean recurse) {
            int separator = binaryName.lastIndexOf('.');
            String ownPackage = separator < 0 ? "" : binaryName.substring(0, separator);
            return ownPackage.equals(packageName) ||
                    recurse && (packageName.isEmpty() || ownPackage.startsWith(packageName + '.'));
        }
    }
}
