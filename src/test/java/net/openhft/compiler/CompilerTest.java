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
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

// `CompilerTest` class extends `TestCase`, used to test compiler functionality, specifically related to the `FooBarTee` class.
public class CompilerTest extends TestCase {
    // Constant reference to the parent directory for the compiler. Could be "essence-file" or the current directory.
    static final File parent;

    // Constant string representing the class name to be tested.
    private static final String EG_FOO_BAR_TEE = "eg.FooBarTee";

    // Constant integer representing the number of runs or iterations for the test.
    private static final int RUNS = 1000 * 1000;

    // Static block to initialize the `parent` field. It checks if "essence-file" exists, and if not, sets the parent to the current directory.
    static {
        File parent2 = new File("essence-file");
        if (parent2.exists()) {
            parent = parent2;

        } else {
            parent = new File(".");
        }
    }

    // Main method to create a new instance of `CompilerTest` and invoke the `test_compiler` method.
    // The specific implementation of the `test_compiler` method is not provided in the given code.
    public static void main(String[] args) throws Throwable {
        new CompilerTest().test_compiler();
    }
    // Further test methods or implementations should go here.

    public void test_compiler() throws Throwable {
    // Uncomment the line below to enable debugging in CompilerUtils.
    // CompilerUtils.setDebug(true);

    // Add the class path "target/test-classes" for the compiler.
    CompilerUtils.addClassPath("target/test-classes");

        // this writes the file to disk only when debugging is enabled.
        CachedCompiler cc = CompilerUtils.DEBUGGING ?
                new CachedCompiler(new File(parent, "target/generated-test-sources"), new File(parent, "target/test-classes")) :
                CompilerUtils.CACHED_COMPILER;

// Text to be used in the generated class, including the current date.
        String text = "generated test " + new Date();
        try {
            final Class aClass =
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

        // Create a new instance of "FooBarTee3" by invoking its constructor with the argument "test foo bar tee".
        FooBarTee fooBarTee = (FooBarTee) aClass
                .getConstructor(String.class)
                .newInstance("test foo bar tee");

        // Retrieve the "foo" field from "fooBarTee" and perform assertions to validate the functionality.
        Foo foo = fooBarTee.foo;
        assertNotNull(foo); // Assert that "foo" is not null.
        assertEquals(text, foo.s); // Assert that the text in "foo.s" matches the generated text.
    } catch (ClassNotFoundException cnfe) {
        cnfe.printStackTrace();
        // Handle ClassNotFoundException, if encountered.
        // TODO: This needs to be fixed on teamcity.
    }
}


public void test_fromFile()
        throws ClassNotFoundException, IOException, IllegalAccessException, InstantiationException,
        NoSuchMethodException, InvocationTargetException, NoSuchFieldException {
    // Load class "eg.FooBarTee2" from the given resource file.
    Class clazz = CompilerUtils.loadFromResource("eg.FooBarTee2", "eg/FooBarTee2.jcf");

    // Store the current System.out print stream to revert back later.
    PrintStream out = System.out;

    try {
        // Replace System.out with a no-op print stream to suppress any console output during the test.
        System.setOut(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
            }
        }));

        // Get the constructor of the loaded class that accepts a String argument.
        final Constructor stringConstructor = clazz.getConstructor(String.class);
        long start = 0;

        // Perform the test RUNS times, including a warm-up phase of -RUNS / 10 iterations.
        for (int i = -RUNS / 10; i < RUNS; i++) {
            if (i == 0) start = System.nanoTime(); // Start the timer when the warm-up phase ends.

            // Create an instance of the loaded class using its constructor.
            Object fooBarTee2 = stringConstructor.newInstance(getName());

            // Access the "foo" field of the created instance.
            Foo foo = (Foo) clazz.getDeclaredField("foo").get(fooBarTee2);

            // Assert that "foo" is not null and that its "s" field contains the expected string value.
            assertNotNull(foo);
            assertEquals("load java class from file.", foo.s);
        }

        // Measure the time taken for the actual RUNS and print it.
        long time = System.nanoTime() - start;
        out.printf("Build build small container %,d ns.%n", time / RUNS);
    } finally {
        // Restore the original System.out print stream.
        System.setOut(out);
    }
}


public void test_settingPrintStreamWithCompilerErrors() throws Exception {
    // Atomic booleans to track if System.out or System.err were used.
    final AtomicBoolean usedSysOut = new AtomicBoolean(false);
    final AtomicBoolean usedSysErr = new AtomicBoolean(false);

    // Store the original System.out and System.err print streams to revert back later.
    final PrintStream out = System.out;
    final PrintStream err = System.err;
    final StringWriter writer = new StringWriter();

    try {
        // Replace System.out and System.err with custom streams that set the atomic booleans if written to.
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

        // Attempt to compile a class with a deliberate syntax error (using "clazz" instead of "class").
        CompilerUtils.CACHED_COMPILER.loadFromJava(
                getClass().getClassLoader(), "TestClass", "clazz TestClass {}",
                new PrintWriter(writer));

        // If compilation succeeds, this is a failure in the test.
        fail("Should have failed to compile");
    } catch (ClassNotFoundException e) {
        // Expected exception for compilation failure.
    } finally {
        // Restore the original System.out and System.err print streams.
        System.setOut(out);
        System.setErr(err);
    }

    // Assert that the custom streams were not used, proving that compiler errors were not sent to System.out or System.err.
    assertFalse(usedSysOut.get());
    assertFalse(usedSysErr.get());

    // List of expected error messages from the compiler.
    List<String> expectedInErrorFromCompiler = Arrays.asList(
            "TestClass.java:1: error", "clazz TestClass {}");

    // Check that the expected error messages are present in the captured compiler output.
    for (String expectedError : expectedInErrorFromCompiler) {
        String errorMessage = String.format("Does not contain expected '%s' in:\n%s", expectedError, writer.toString());
        assertTrue(errorMessage, writer.toString().contains(expectedError));
    }
}


public void test_settingPrintStreamWithNoErrors() throws Exception {
    // Atomic booleans to track if System.out or System.err were used.
    final AtomicBoolean usedSysOut = new AtomicBoolean(false);
    final AtomicBoolean usedSysErr = new AtomicBoolean(false);

    // Store the original System.out and System.err print streams to revert back later.
    final PrintStream out = System.out;
    final PrintStream err = System.err;
    final StringWriter writer = new StringWriter();

    try {
        // Replace System.out and System.err with custom streams that set the atomic booleans if written to.
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

        // Attempt to compile a class with correct syntax.
        CompilerUtils.CACHED_COMPILER.loadFromJava(
                getClass().getClassLoader(), "TestClass", "class TestClass {}",
                new PrintWriter(writer));
    } finally {
        // Restore the original System.out and System.err print streams.
        System.setOut(out);
        System.setErr(err);
    }

    // Assert that the custom streams were not used, proving that no compilation messages were sent to System.out or System.err.
    assertFalse(usedSysOut.get());
    assertFalse(usedSysErr.get());

    // Assert that no compilation messages were written to the writer, as the code snippet was correct and should not generate any errors or warnings.
    assertEquals("", writer.toString());
}


public void test_settingPrintStreamWithWarnings() throws Exception {
    // Atomic booleans to track if System.out or System.err were used.
    final AtomicBoolean usedSysOut = new AtomicBoolean(false);
    final AtomicBoolean usedSysErr = new AtomicBoolean(false);

    // Store the original System.out and System.err print streams to revert back later.
    final PrintStream out = System.out;
    final PrintStream err = System.err;
    final StringWriter writer = new StringWriter();

    try {
        // Replace System.out and System.err with custom streams that set the atomic booleans if written to.
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

        // Attempt to compile a class with code that should generate a warning.
        CompilerUtils.CACHED_COMPILER.loadFromJava(
                getClass().getClassLoader(), "TestClass",
                // Definition with a mandatory warning due to usage of a deprecated method.
                "class TestClass { int i = new Date().getDay(); }",
                new PrintWriter(writer));
    } finally {
        // Restore the original System.out and System.err print streams.
        System.setOut(out);
        System.setErr(err);
    }

    // Assert that the custom streams were not used, even though the code snippet may have triggered a warning.
    assertFalse(usedSysOut.get());
    assertFalse(usedSysErr.get());

    // This code asserts that no compilation messages were written to the writer.
    // Depending on the behavior of the compiler for warnings, this assertion might not reflect the intended testing of warnings.
    // It would be more accurate to inspect the writer's content for the expected warning message instead of expecting it to be empty.
    assertEquals("", writer.toString());
}


public void test_compilerErrorsDoNotBreakNextCompilations() throws Exception {
    // Create a PrintWriter that writes to nowhere, to suppress compiler output
    PrintWriter quietWriter = new PrintWriter(new StringWriter());

    // Attempt to compile a class with a deliberate error ("clazz" instead of "class")
    try {
        CompilerUtils.CACHED_COMPILER.loadFromJava(
                getClass().getClassLoader(), "X", "clazz X {}", quietWriter);
        fail("Should have failed to compile"); // This line should never be reached
    } catch (ClassNotFoundException e) {
        // Expected error; continue with test
    }

    // Compile a valid class named "S" with a static string field
    Class testClass = CompilerUtils.CACHED_COMPILER.loadFromJava(
            getClass().getClassLoader(), "S", "class S {" +
                    "public static final String s = \"ok\";}");

    // Compile a valid class that implements Callable and returns the static field from "S"
    Callable callable = (Callable)
            CompilerUtils.CACHED_COMPILER.loadFromJava(
                            getClass().getClassLoader(), "OtherClass",
                            "import java.util.concurrent.Callable; " +
                                    "public class OtherClass implements Callable<String> {" +
                                    "public String call() { return S.s; }}")
                    .getDeclaredConstructor()
                    .newInstance();

    // Assert that the name of the compiled class is "S"
    assertEquals("S", testClass.getName());

    // Assert that calling the callable returns "ok"
    assertEquals("ok", callable.call());
}


    @Test
    public void testNewCompiler() throws Exception {
        // Iterate three times to perform three distinct compilations
        for (int i = 1; i <= 3; i++) {
            // Create a new ClassLoader; this helps isolate the compiled classes
            ClassLoader classLoader = new ClassLoader() {
            };
            // Create a new CachedCompiler instance
            CachedCompiler cc = new CachedCompiler(null, null);

            // Compile class "A" with a static integer field "i" set to the current value of loop variable "i"
            Class a = cc.loadFromJava(classLoader, "A", "public class A { static int i = " + i + "; }");

            // Compile class "B" that implements MyIntSupplier interface, and returns the value of field "i" from class "A"
            Class b = cc.loadFromJava(classLoader, "B", "public class B implements net.openhft.compiler.MyIntSupplier { public int get() { return A.i; } }");

            // Create an instance of class "B"
            MyIntSupplier bi = (MyIntSupplier) b.getDeclaredConstructor().newInstance();

            // Assert that the value returned by the "get" method of the "B" instance equals the current value of "i"
            assertEquals(i, bi.get());
        }
    }
}

