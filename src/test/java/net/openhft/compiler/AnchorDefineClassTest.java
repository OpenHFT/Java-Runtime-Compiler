/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import net.openhft.compiler.anchorone.AnchorOne;
import net.openhft.compiler.anchortwo.AnchorTwo;
import org.junit.Test;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Anchor/lookup class-definition strategy (issue #91): the compiler can define a generated
 * class in the caller's package and class loader through a {@link MethodHandles.Lookup} the
 * caller supplies, using no {@code sun.misc.Unsafe} and no {@code setAccessible}.
 * <p>
 * This test lives in {@code net.openhft.compiler} so the lookup it hands over
 * ({@link MethodHandles#lookup()}) has full privileges in that package, and the generated class
 * is declared in the same package - the constraint the JDK enforces on
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
     * in a different package than the anchor is rejected - a corrupted cross-loader definition
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

    @Test
    public void repeatedLoadReturnsCachedClass() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        final String className = "net.openhft.compiler.AnchorRepeated";
        final String source = "package net.openhft.compiler; public class AnchorRepeated {}";

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            Class<?> first = cc.loadFromJava(MethodHandles.lookup(), className, source);
            Class<?> second = cc.loadFromJava(MethodHandles.lookup(), className, source);
            assertSame(first, second);
        }
    }

    @Test
    public void consecutiveClassesDoNotRedefineRetainedOutput() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            Class<?> first = cc.loadFromJava(MethodHandles.lookup(),
                    "net.openhft.compiler.AnchorFirst",
                    "package net.openhft.compiler; public class AnchorFirst {}");
            Class<?> second = cc.loadFromJava(MethodHandles.lookup(),
                    "net.openhft.compiler.AnchorSecond",
                    "package net.openhft.compiler; public class AnchorSecond {}");

            assertEquals("net.openhft.compiler.AnchorFirst", first.getName());
            assertEquals("net.openhft.compiler.AnchorSecond", second.getName());
        }
    }

    @Test
    public void lookupsForTwoPackagesMayShareOneLoader() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        assertSame(AnchorOne.class.getClassLoader(), AnchorTwo.class.getClassLoader());

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            Class<?> first = cc.loadFromJava(AnchorOne.lookup(),
                    "net.openhft.compiler.anchorone.GeneratedOne",
                    "package net.openhft.compiler.anchorone; public class GeneratedOne {}");
            Class<?> second = cc.loadFromJava(AnchorTwo.lookup(),
                    "net.openhft.compiler.anchortwo.GeneratedTwo",
                    "package net.openhft.compiler.anchortwo; public class GeneratedTwo {}");

            assertEquals(AnchorOne.class.getPackage(), first.getPackage());
            assertEquals(AnchorTwo.class.getPackage(), second.getPackage());
        }
    }

    @Test
    public void nestedAndAnonymousClassesAreDefinedWithPrimary() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        final String source =
                "package net.openhft.compiler;\n" +
                "public class AnchorNested {\n" +
                "  public static class Nested { public int value() { return 7; } }\n" +
                "  public Runnable anonymous() { return new Runnable() { public void run() {} }; }\n" +
                "}\n";

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            Class<?> outer = cc.loadFromJava(MethodHandles.lookup(),
                    "net.openhft.compiler.AnchorNested", source);
            Object instance = outer.getDeclaredConstructor().newInstance();
            Runnable anonymous = (Runnable) outer.getMethod("anonymous").invoke(instance);

            assertEquals("net.openhft.compiler.AnchorNested$1", anonymous.getClass().getName());
            assertEquals("net.openhft.compiler.AnchorNested$Nested", outer.getDeclaredClasses()[0].getName());
        }
    }

    @Test
    public void sameSourceSuperclassBeforePrimaryIsDefinedFirst() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        final String source =
                "package net.openhft.compiler;\n" +
                "class AnchorBaseBefore {}\n" +
                "public class AnchorDerivedBefore extends AnchorBaseBefore {}\n";

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            Class<?> derived = cc.loadFromJava(MethodHandles.lookup(),
                    "net.openhft.compiler.AnchorDerivedBefore", source);
            assertEquals("net.openhft.compiler.AnchorBaseBefore", derived.getSuperclass().getName());
        }
    }

    @Test
    public void sameSourceSuperclassAfterPrimaryIsRetried() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        final String source =
                "package net.openhft.compiler;\n" +
                "public class AnchorDerivedAfter extends AnchorBaseAfter {}\n" +
                "class AnchorBaseAfter {}\n";

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            Class<?> derived = cc.loadFromJava(MethodHandles.lookup(),
                    "net.openhft.compiler.AnchorDerivedAfter", source);
            assertEquals("net.openhft.compiler.AnchorBaseAfter", derived.getSuperclass().getName());
        }
    }

    @Test
    public void failedAuxiliaryDefinitionDoesNotCompletePrimaryCache() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        final String primaryName = "net.openhft.compiler.AnchorPartialRetry";
        final String helperName = primaryName + "Helper";
        final String source =
                "package net.openhft.compiler;\n" +
                "public class AnchorPartialRetry {\n" +
                "  public Object helper() { return new AnchorPartialRetryHelper(); }\n" +
                "}\n" +
                "class AnchorPartialRetryHelper {}\n";
        final AtomicBoolean corruptFirstHelper = new AtomicBoolean(true);

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            cc.setFileManagerOverride(delegate -> new MyJavaFileManager(delegate) {
                @Override
                Map<String, byte[]> getBuffersForSources(Iterable<? extends javax.tools.JavaFileObject> sources) {
                    Map<String, byte[]> compiled = super.getBuffersForSources(sources);
                    if (!corruptFirstHelper.compareAndSet(true, false))
                        return compiled;

                    Map<String, byte[]> ordered = new LinkedHashMap<>();
                    ordered.put(primaryName, compiled.get(primaryName));
                    byte[] helper = compiled.get(helperName);
                    ordered.put(helperName, Arrays.copyOf(helper, helper.length - 1));
                    return ordered;
                }
            });

            try {
                cc.loadFromJava(MethodHandles.lookup(), primaryName, source);
                fail("corrupt auxiliary class must fail definition");
            } catch (ClassFormatError expected) {
                // The primary was defined first, but the batch did not complete.
            }
            assertEquals(primaryName,
                    Class.forName(primaryName, false, getClass().getClassLoader()).getName());

            Class<?> primary = cc.loadFromJava(MethodHandles.lookup(), primaryName, source);
            Object helper = primary.getMethod("helper").invoke(primary.getDeclaredConstructor().newInstance());
            assertEquals(helperName, helper.getClass().getName());
        }
    }

    @Test
    public void loadingDoesNotInitialisePrimaryClass() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        final String source =
                "package net.openhft.compiler;\n" +
                "public class AnchorInitialisation {\n" +
                "  static { if (true) throw new RuntimeException(\"initialised\"); }\n" +
                "}\n";

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            Class<?> generated = cc.loadFromJava(MethodHandles.lookup(),
                    "net.openhft.compiler.AnchorInitialisation", source);
            assertEquals("net.openhft.compiler.AnchorInitialisation", generated.getName());
            try {
                generated.getDeclaredConstructor().newInstance();
                fail("construction should initialise the class");
            } catch (ExceptionInInitializerError expected) {
                assertEquals("initialised", expected.getCause().getMessage());
            }
        }
    }

    @Test
    public void lookupWithoutPackageAccessIsCallerError() {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        try {
            CompilerUtils.defineClass(MethodHandles.publicLookup(), new byte[0]);
            fail("publicLookup must not be allowed to define a class");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("PACKAGE"));
        }
    }

    @Test
    public void concurrentSameClassCompilationDefinesOnce() throws Exception {
        assumeTrue("anchor mode needs Java 9+", CompilerUtils.isAnchorDefineClassSupported());
        final int count = 8;
        final ExecutorService executor = Executors.newFixedThreadPool(count);
        final CountDownLatch start = new CountDownLatch(1);
        final String source = "package net.openhft.compiler; public class AnchorConcurrent {}";

        try (CachedCompiler cc = new CachedCompiler(null, null)) {
            List<Future<Class<?>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return cc.loadFromJava(MethodHandles.lookup(),
                            "net.openhft.compiler.AnchorConcurrent", source);
                }));
            }
            start.countDown();
            Class<?> expected = futures.get(0).get();
            for (Future<Class<?>> future : futures)
                assertSame(expected, future.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
