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

import eg.FooBarTee;
import eg.components.Foo;
import junit.framework.TestCase;
import org.junit.Test;

import java.io.*;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.tools.Diagnostic;

public class CompilerTest extends TestCase {
    static final File parent;
    private static final String EG_FOO_BAR_TEE = "eg.FooBarTee";
    private static final int RUNS = 1000 * 1000;

    static {
        File parent2 = new File("essence-file");
        if (parent2.exists()) {
            parent = parent2;

        } else {
            parent = new File(".");
        }
    }

    private CachedCompiler compiler;
    private URLClassLoader classLoader;

    @Override
    protected void setUp() throws Exception {
        // Create new compiler and class loader to prevent tests from affecting each other
        compiler = new CachedCompiler(null, null);
        classLoader = new URLClassLoader(new URL[0]);
    }

    @Override
    protected void tearDown() throws Exception {
        compiler.close();
        classLoader.close();
    }

    public static void main(String[] args) throws Throwable {
        CompilerTest compilerTest = new CompilerTest();
        try {
            compilerTest.setUp();
            compilerTest.test_compiler();
        } finally {
            compilerTest.tearDown();
        }
    }

    public void test_compiler() throws Throwable {
        // CompilerUtils.setDebug(true);
        // added so the test passes in Maven.
        CompilerUtils.addClassPath("target/test-classes");
//        ClassLoader loader = CompilerTest.class.getClassLoader();
//        URLClassLoader urlClassLoader = new URLClassLoader(((URLClassLoader)loader).getURLs(), null);
//        Class fooBarTee1 = urlClassLoader.loadClass("eg.FooBarTee");

        // this writes the file to disk only when debugging is enabled.
        CachedCompiler cc = CompilerUtils.DEBUGGING ?
                new CachedCompiler(new File(parent, "target/generated-test-sources"), new File(parent, "target/test-classes")) :
                compiler;

        String text = "generated test " + new Date();
        try {
            final Class<?> aClass =
                    cc.loadFromJava(EG_FOO_BAR_TEE + 3, "package eg;\n" +
                            '\n' +
                            "import eg.components.BarImpl;\n" +
                            "import eg.components.TeeImpl;\n" +
                            "import eg.components.Foo;\n" +
                            '\n' +
                            "public class FooBarTee3 extends FooBarTee {\n" +
                            '\n' +
                            "    public FooBarTee3(String name) {\n" +
                            "        super(name);\n" +
                            "        // when viewing this file, ensure it is synchronised with the copy on disk.\n" +
                            "        System.out.println(\"" + text + "\");\n" +
                            '\n' +
                            "        // you should see the current date here after synchronisation.\n" +
                            "        foo = new Foo(bar, copy, \"" + text + "\", 5);\n" +
                            "    }\n" +
                            '\n' +
                            "    public void start() {\n" +
                            "    }\n" +
                            '\n' +
                            "    public void stop() {\n" +
                            "    }\n" +
                            '\n' +
                            "    public void close() {\n" +
                            "        stop();\n" +
                            '\n' +
                            "    }\n" +
                            "}\n");

            // add a debug break point here and step into this method.
            FooBarTee fooBarTee = (FooBarTee) aClass
                    .getConstructor(String.class)
                    .newInstance("test foo bar tee");
            Foo foo = fooBarTee.foo;
            assertNotNull(foo);
            assertEquals(text, foo.s);
        } catch (ClassNotFoundException cnfe) {
            cnfe.printStackTrace();
            // TODO FIX on teamcity
        }
    }

    public void test_fromFile() throws Exception {
        Class<?> clazz = CompilerUtils.loadFromResource("eg.FooBarTee2", "eg/FooBarTee2.jcf");
        // turn off System.out
        PrintStream out = System.out;
        try {
            System.setOut(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                }
            }));
            final Constructor<?> stringConstructor = clazz.getConstructor(String.class);
            long start = 0;
            for (int i = -RUNS / 10; i < RUNS; i++) {
                if (i == 0) start = System.nanoTime();

                Object fooBarTee2 = stringConstructor.newInstance(getName());
                Foo foo = (Foo) clazz.getDeclaredField("foo").get(fooBarTee2);
                assertNotNull(foo);
                assertEquals("load java class from file.", foo.s);
            }
            long time = System.nanoTime() - start;
            out.printf("Build build small container %,d ns.%n", time / RUNS);
        } finally {
            System.setOut(out);
        }
    }

    public void test_settingPrintStreamWithCompilerErrors() throws Exception {
        final AtomicBoolean usedSysOut = new AtomicBoolean(false);
        final AtomicBoolean usedSysErr = new AtomicBoolean(false);

        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final StringWriter writer = new StringWriter();

        try {
            System.setOut(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysOut.set(true);
                }
            }));
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysErr.set(true);
                }
            }));

            compiler.loadFromJava(
                    classLoader, "TestClass", "clazz TestClass {}",
                    new PrintWriter(writer));
            fail("Should have failed to compile");
        } catch (ClassNotFoundException e) {
            // Should have been enhanced with additional details
            assertTrue(e.getMessage().contains("Compilation errors"));
            assertTrue(e.getCause() instanceof ClassNotFoundException);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertFalse(usedSysOut.get());
        assertFalse(usedSysErr.get());

        List<String> expectedInErrorFromCompiler = Arrays.asList(
                "TestClass.java:1: error", "clazz TestClass {}");

        for (String expectedError : expectedInErrorFromCompiler) {
            String errorMessage = String.format("Does not contain expected '%s' in:\n%s", expectedError, writer.toString());
            assertTrue(errorMessage, writer.toString().contains(expectedError));
        }
    }

    public void test_settingPrintStreamWithNoErrors() throws Exception {
        final AtomicBoolean usedSysOut = new AtomicBoolean(false);
        final AtomicBoolean usedSysErr = new AtomicBoolean(false);

        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final StringWriter writer = new StringWriter();

        try {
            System.setOut(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                    usedSysOut.set(true);
                }
            }));
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysErr.set(true);
                }
            }));

            compiler.loadFromJava(
                    classLoader, "TestClass", "class TestClass {}",
                    new PrintWriter(writer));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertFalse(usedSysOut.get());
        assertFalse(usedSysErr.get());
        assertEquals("", writer.toString());
    }

    public void test_settingPrintStreamWithWarnings() throws Exception {
        final AtomicBoolean usedSysOut = new AtomicBoolean(false);
        final AtomicBoolean usedSysErr = new AtomicBoolean(false);

        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final StringWriter writer = new StringWriter();

        // Enable lint; otherwise compiler produces no Warning diagnostics but only Note, saying
        // that `-Xlint:deprecation` should be used
        final List<String> options = Arrays.asList("-Xlint:deprecation");
        try (CachedCompiler compiler = new CachedCompiler(null, null, options)) {
            System.setOut(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysOut.set(true);
                }
            }));
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysErr.set(true);
                }
            }));

            compiler.loadFromJava(
                    classLoader, "TestClass",
                    // definition with a mandatory warning for deprecated `Date.getDay()`
                    "import java.util.Date; class TestClass { int i = new Date().getDay(); }",
                    new PrintWriter(writer));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertFalse(usedSysOut.get());
        assertFalse(usedSysErr.get());
        assertEquals("", writer.toString());
    }

    public void test_settingDiagnosticListenerWithCompilerErrors() throws Exception {
        final AtomicBoolean usedSysOut = new AtomicBoolean(false);
        final AtomicBoolean usedSysErr = new AtomicBoolean(false);

        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final StringWriter writer = new StringWriter();
        final List<Diagnostic<?>> diagnostics = Collections.synchronizedList(new ArrayList<>());

        try {
            System.setOut(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysOut.set(true);
                }
            }));
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysErr.set(true);
                }
            }));

            compiler.loadFromJava(
                    classLoader,
                    "TestClass",
                    "clazz TestClass {}",
                    new PrintWriter(writer),
                    diagnostics::add);
            fail("Should have failed to compile");
        } catch (ClassNotFoundException e) {
            // Should have been enhanced with additional details
            assertTrue(e.getMessage().contains("Compilation errors"));
            assertTrue(e.getCause() instanceof ClassNotFoundException);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertFalse(usedSysOut.get());
        assertFalse(usedSysErr.get());
        // Diagnostics should have only been reported to listener; not written to output
        assertEquals("", writer.toString());

        assertEquals(1, diagnostics.size());
        Diagnostic<?> diagnostic = diagnostics.get(0);
        assertEquals(Diagnostic.Kind.ERROR, diagnostic.getKind());
        assertEquals(1, diagnostic.getLineNumber());
    }

    public void test_settingDiagnosticListenerWithWarnings() throws Exception {
        final AtomicBoolean usedSysOut = new AtomicBoolean(false);
        final AtomicBoolean usedSysErr = new AtomicBoolean(false);

        final PrintStream out = System.out;
        final PrintStream err = System.err;
        final StringWriter writer = new StringWriter();
        final List<Diagnostic<?>> diagnostics = Collections.synchronizedList(new ArrayList<>());

        // Enable lint; otherwise compiler only produces no Warning diagnostics but only Note, saying
        // that `-Xlint:unchecked` should be used
        final List<String> options = Arrays.asList("-Xlint:unchecked");
        try (CachedCompiler compiler = new CachedCompiler(null, null, options)) {
            System.setOut(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysOut.set(true);
                }
            }));
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    usedSysErr.set(true);
                }
            }));

            compiler.loadFromJava(
                    classLoader,
                    "TestClass",
                    // definition with a mandatory warning for unchecked cast
                    "import java.util.*; class TestClass { public List<Integer> unsafe(List<?> l) { return (List<Integer>) l; } }",
                    new PrintWriter(writer),
                    diagnostics::add);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertFalse(usedSysOut.get());
        assertFalse(usedSysErr.get());
        // Diagnostics should have only been reported to listener; not written to output
        assertEquals("", writer.toString());

        assertEquals(1, diagnostics.size());
        Diagnostic<?> diagnostic = diagnostics.get(0);
        assertEquals(Diagnostic.Kind.MANDATORY_WARNING, diagnostic.getKind());
        assertEquals(1, diagnostic.getLineNumber());
    }

    public void test_compilerErrorsDoNotBreakNextCompilations() throws Exception {
        // quieten the compiler output
        PrintWriter quietWriter = new PrintWriter(new StringWriter());

        // cause a compiler error
        try {
            compiler.loadFromJava(
                    classLoader, "X", "clazz X {}", quietWriter);
            fail("Should have failed to compile");
        } catch (ClassNotFoundException e) {
            // Should have been enhanced with additional details
            assertTrue(e.getMessage().contains("Compilation errors"));
            assertTrue(e.getCause() instanceof ClassNotFoundException);
        }

        // ensure next class can be compiled and used
        Class<?> testClass = compiler.loadFromJava(
                classLoader, "S", "class S {" +
                        "public static final String s = \"ok\";}");

        Callable<?> callable = (Callable<?>)
                compiler.loadFromJava(
                                classLoader, "OtherClass",
                                "import java.util.concurrent.Callable; " +
                                        "public class OtherClass implements Callable<String> {" +
                                        "public String call() { return S.s; }}")
                        .getDeclaredConstructor()
                        .newInstance();

        assertEquals("S", testClass.getName());
        assertEquals("ok", callable.call());
    }

    public void test_compilerErrorsButLoadingDifferentClass() throws Exception {
        // quieten the compiler output
        PrintWriter quietWriter = new PrintWriter(new StringWriter());

        // TODO: Should this throw an exception due to the compilation error nonetheless to be less
        // error-prone for users, even if loading class would succeed?
        Class<?> testClass = compiler.loadFromJava(classLoader,
                // Load other class which is unaffected by compilation error
                String.class.getName(),
                "clazz X {}", quietWriter);
        assertSame(String.class, testClass);
    }

    @Test
    public void testNewCompiler() throws Exception {
        for (int i = 1; i <= 3; i++) {
            ClassLoader classLoader = new ClassLoader() {
            };
            CachedCompiler cc = new CachedCompiler(null, null);
            Class<?> a = cc.loadFromJava(classLoader, "A", "public class A { static int i = " + i + "; }");
            Class<?> b = cc.loadFromJava(classLoader, "B", "public class B implements net.openhft.compiler.MyIntSupplier { public int get() { return A.i; } }");
            MyIntSupplier bi = (MyIntSupplier) b.getDeclaredConstructor().newInstance();
            assertEquals(i, bi.get());
        }
    }
}
