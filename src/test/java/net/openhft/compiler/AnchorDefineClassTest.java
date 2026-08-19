/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Test;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Anchor/lookup class-definition strategy (issue #91): the compiler can define a generated
 * class in the caller's package and class loader through a {@link MethodHandles.Lookup} the
 * caller supplies, using no {@code sun.misc.Unsafe} and no {@code setAccessible}.
 * <p>
 * This test lives in {@code net.openhft.compiler} so the lookup it hands over
 * ({@link MethodHandles#lookup()}) has full privileges in that package, and the generated class
 * is declared in the same package — the constraint the JDK enforces on
 * {@link java.lang.invoke.MethodHandles.Lookup#defineClass(byte[])}.
 */
public class AnchorDefineClassTest {

    @Test
    public void anchorModeDefinesInCallerLoaderWithoutUnsafe() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());

        final MethodHandles.Lookup anchor = MethodHandles.lookup();
        final String className = "net.openhft.compiler.AnchorGenerated";
        final String source =
                "package net.openhft.compiler;\n" +
                "import java.util.concurrent.Callable;\n" +
                "public class AnchorGenerated implements Callable<String> {\n" +
                "    public String call() { return \"anchored\"; }\n" +
                "}\n";

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            final Class<?> generated = cc.loadFromJava(anchor, className, source);

            // Pass-after: the class is defined in the anchor's own loader (not a fresh child),
            // which is exactly what the non-Unsafe Lookup#defineClass path guarantees.
            assertEquals(className, generated.getName());
            assertSame("class must be defined in the anchor's class loader",
                    anchor.lookupClass().getClassLoader(), generated.getClassLoader());
            assertEquals("net.openhft.compiler", generated.getPackage().getName());

            @SuppressWarnings("unchecked")
            final Callable<String> instance = (Callable<String>) generated.getDeclaredConstructor().newInstance();
            assertEquals("anchored", instance.call());
        }
    }

    /**
     * Negative control: the anchor strategy inherits the JDK's package check. A class declared
     * in a different package than the anchor is rejected — a corrupted cross-loader definition
     * cannot slip through, unlike a raw {@code ClassLoader.defineClass} via Unsafe.
     */
    @Test
    public void anchorModeRejectsForeignPackage() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());

        final MethodHandles.Lookup anchor = MethodHandles.lookup();
        final String className = "net.openhft.compiler.foreign.ForeignGenerated";
        final String source =
                "package net.openhft.compiler.foreign;\n" +
                "public class ForeignGenerated {\n" +
                "    public int value() { return 42; }\n" +
                "}\n";

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            try {
                cc.loadFromJava(anchor, className, source);
                fail("expected the JDK to reject a class outside the anchor's package");
            } catch (IllegalArgumentException expected) {
                // MethodHandles.Lookup#defineClass throws IllegalArgumentException for a class
                // that is not in the same package as the lookup class.
            }
        }
    }

    @Test
    public void nullArgumentsRejected() {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        try {
            CompilerUtils.defineClass((MethodHandles.Lookup) null, new byte[0]);
            fail("null anchor must be rejected");
        } catch (NullPointerException expected) {
            // expected
        }
    }
}
