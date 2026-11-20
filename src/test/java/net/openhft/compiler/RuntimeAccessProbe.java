/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

/** Separate process to exercise real compile, class definition and compiler availability. */
public final class RuntimeAccessProbe {
    public static void main(String[] args) throws Exception {
        try (CachedCompiler compiler = new CachedCompiler(null, null)) {
            Class<?> result = compiler.loadFromJava("AccessHello",
                    "public class AccessHello { public static int value() { return 42; } }");
            if (!Integer.valueOf(42).equals(result.getMethod("value").invoke(null))) throw new AssertionError();
            System.out.println("compiled-loaded-42");
        }
    }
}
