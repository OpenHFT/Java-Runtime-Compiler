/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.Assert.*;

public class CachedCompilerClassLifetimeTest {
    @Test
    public void liveClassAndNestedClassAreReusedWithoutCompilingAgain() throws Exception {
        try (CachedCompiler compiler = new CachedCompiler(null, null);
             URLClassLoader loader = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            String name = "lifetime.Live";
            Class<?> type = compiler.loadFromJava(loader, name,
                    "package lifetime; public class Live { public static class Nested {} }");
            Class<?> nested = loader.loadClass(name + "$Nested");
            assertSame(type, compiler.loadFromJava(loader, name, "invalid source must never be compiled"));
            assertSame(nested, compiler.loadFromJava(loader, name + "$Nested", "invalid source"));
            assertSame(loader, type.getClassLoader());
        }
    }

    @Test
    public void replacementLoaderCanCompileAnIndependentVersion() throws Exception {
        try (CachedCompiler compiler = new CachedCompiler(null, null);
             URLClassLoader first = new URLClassLoader(new URL[0], getClass().getClassLoader());
             URLClassLoader second = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            Class<?> oldVersion = compiler.loadFromJava(first, "lifetime.Version", source(41));
            Class<?> newVersion = compiler.loadFromJava(second, "lifetime.Version", source(42));
            assertNotSame(oldVersion, newVersion);
            assertEquals(41, oldVersion.getMethod("value").invoke(null));
            assertEquals(42, newVersion.getMethod("value").invoke(null));
            assertSame(newVersion, compiler.loadFromJava(second, "lifetime.Version", "invalid source"));
        }
    }

    private static String source(int value) {
        return "package lifetime; public class Version { public static int value() { return " + value + "; } }";
    }
}
