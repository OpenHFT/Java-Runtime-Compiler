/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.Assert.*;

public class CachedCompilerModuleClassLoaderDiagnosticsTest {

    @Test
    public void moduleLikeLoaderCanLoadClassAfterSuccessfulDefineClass() throws Exception {
        ModuleLikeClassLoader loader = new ModuleLikeClassLoader();
        CachedCompiler compiler = new CachedCompiler(null, null);

        Class<?> clazz = compiler.loadFromJava(loader,
                "app.Generated",
                "package app; public class Generated { public int value() { return 42; } }");

        assertEquals("app.Generated", clazz.getName());
        assertSame(clazz, loader.loadClass("app.Generated"));
        assertEquals(42, clazz.getDeclaredMethod("value").invoke(clazz.getDeclaredConstructor().newInstance()));
    }

    @Test
    public void compileFailureForLoaderOnlyDependencyReportsJavacDiagnostics() throws Exception {
        ModuleLikeClassLoader loader = new ModuleLikeClassLoader();
        defineLoaderOnlyDependency(loader);

        assertSame("Sanity check: the supplied class loader can see the dependency",
                loader.loadClass("app.Dto"),
                Class.forName("app.Dto", false, loader));

        CachedCompiler compiler = new CachedCompiler(null, null);
        StringWriter diagnostics = new StringWriter();

        ClassNotFoundException thrown = assertThrows(ClassNotFoundException.class,
                () -> compiler.loadFromJava(loader,
                        "app.GeneratedUsesDto",
                        "package app; public class GeneratedUsesDto { app.Dto dto; }",
                        new PrintWriter(diagnostics)));

        assertTrue("Thrown exception should identify compilation failure: " + thrown.getMessage(),
                thrown.getMessage().contains("Compilation failed for app.GeneratedUsesDto"));
        assertTrue("Thrown exception should include javac missing-symbol diagnostics: " + thrown.getMessage(),
                thrown.getMessage().contains("cannot find symbol"));
        assertTrue("Thrown exception should include the dependency javac could not resolve: " + thrown.getMessage(),
                thrown.getMessage().contains("Dto"));
        assertNotNull("Thrown exception should carry a diagnostic cause", thrown.getCause());
        assertTrue("javac diagnostics should mention the dependency that the compiler could not resolve: "
                        + diagnostics,
                diagnostics.toString().contains("Dto"));
        assertNull("The generated class should not have been defined after javac failure",
                loader.findLoaded("app.GeneratedUsesDto"));
    }

    @Test
    public void successfulCompileWithoutRequestedClassReportsMissingOutput() {
        ModuleLikeClassLoader loader = new ModuleLikeClassLoader();
        CachedCompiler compiler = new CachedCompiler(null, null);

        ClassNotFoundException thrown = assertThrows(ClassNotFoundException.class,
                () -> compiler.loadFromJava(loader,
                        "app.Expected",
                        "package app; class Different {}"));

        assertTrue("Thrown exception should identify the missing requested class: " + thrown.getMessage(),
                thrown.getMessage().contains("Compilation did not produce requested class app.Expected"));
        assertTrue("Thrown exception should identify the class javac actually produced: " + thrown.getMessage(),
                thrown.getMessage().contains("app.Different"));
        assertNotNull("Thrown exception should carry a diagnostic cause", thrown.getCause());
        assertNull("The requested class should not have been defined",
                loader.findLoaded("app.Expected"));
    }

    private static void defineLoaderOnlyDependency(ModuleLikeClassLoader loader) throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull("System compiler required", javac);

        try (StandardJavaFileManager standardManager = javac.getStandardFileManager(null, null, null)) {
            CachedCompiler bytecodeCompiler = new CachedCompiler(null, null);
            MyJavaFileManager fileManager = new MyJavaFileManager(standardManager);
            Map<String, byte[]> classes = bytecodeCompiler.compileFromJava(
                    "app.Dto",
                    "package app; public class Dto {}",
                    fileManager);
            byte[] dtoBytes = classes.get("app.Dto");
            assertNotNull(dtoBytes);
            CompilerUtils.defineClass(loader, "app.Dto", dtoBytes);
        }
    }

    private static final class ModuleLikeClassLoader extends ClassLoader {
        private static final String APP_PREFIX = "app.";

        ModuleLikeClassLoader() {
            super(CachedCompilerModuleClassLoaderDiagnosticsTest.class.getClassLoader());
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            return loadClass(name, false);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(APP_PREFIX)) {
                return super.loadClass(name, resolve);
            }
            return findClass(name, resolve);
        }

        private Class<?> findClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            throw new ClassNotFoundException(name + " from [Module \"deployment.repro.war\" from Service Module Loader]");
        }

        Class<?> findLoaded(String name) {
            return findLoadedClass(name);
        }
    }
}
