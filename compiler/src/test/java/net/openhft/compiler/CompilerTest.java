/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.compiler;

import com.sun.tools.internal.xjc.util.NullStream;
import eg.FooBarTee;
import eg.components.Foo;
import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;

public class CompilerTest extends TestCase {
    private static final String EG_FOO_BAR_TEE = "eg.FooBarTee";
    static final File parent;

    static {
        File parent2 = new File("essence-file");
        if (parent2.exists()) {
            parent = parent2;
        } else {
            parent = new File(".");
        }
    }

    public static void test_compiler() throws ClassNotFoundException {
        // CompilerUtils.setDebug(true);
        // added so the test passes in Maven.
        CompilerUtils.addClassPath("target/test-classes");
//        ClassLoader loader = CompilerTest.class.getClassLoader();
//        URLClassLoader urlClassLoader = new URLClassLoader(((URLClassLoader)loader).getURLs(), null);
//        Class fooBarTee1 = urlClassLoader.loadClass("eg.FooBarTee");

        // this writes the file to disk only when debugging is enabled.
        CachedCompiler cc = CompilerUtils.DEBUGGING ?
                new CachedCompiler(new File(parent, "src/test/java"), new File(parent, "target/compiled")) :
                CompilerUtils.CACHED_COMPILER;

        String text = "generated test " + new Date();
        cc.loadFromJava(EG_FOO_BAR_TEE, "package eg;\n" +
                '\n' +
                "import eg.components.BarImpl;\n" +
                "import eg.components.TeeImpl;\n" +
                "import eg.components.Foo;\n" +
                '\n' +
                "public class FooBarTee{\n" +
                "    public final String name;\n" +
                "    public final TeeImpl tee;\n" +
                "    public final BarImpl bar;\n" +
                "    public final BarImpl copy;\n" +
                "    public final Foo foo;\n" +
                '\n' +
                "    public FooBarTee(String name) {\n" +
                "        // when viewing this file, ensure it is synchronised with the copy on disk.\n" +
                "        System.out.println(\"" + text + "\");\n" +
                "        this.name = name;\n" +
                '\n' +
                "        tee = new TeeImpl(\"test\");\n" +
                '\n' +
                "        bar = new BarImpl(tee, 55);\n" +
                '\n' +
                "        copy = new BarImpl(tee, 555);\n" +
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
        FooBarTee fooBarTee = new FooBarTee("test foo bar tee");
        Foo foo = fooBarTee.foo;
        assertNotNull(foo);
        assertEquals(text, foo.s);
    }

    private static final int RUNS = 1000 * 1000;

    public void test_fromFile() throws ClassNotFoundException, IOException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException, NoSuchFieldException {
        Class clazz = CompilerUtils.loadFromResource("eg.FooBarTee2", "eg/FooBarTee2.jcf");
        // turn off System.out
        PrintStream out = System.out;
        try {
            System.setOut(new PrintStream(new NullStream()));
            final Constructor stringConstructor = clazz.getConstructor(String.class);
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
}
