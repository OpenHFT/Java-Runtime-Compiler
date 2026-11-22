/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package mytest;

import net.openhft.compiler.CachedCompiler;
import net.openhft.compiler.CompilerUtils;
import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RuntimeCompileTest {
    private static String code = "package mytest;\n" +
            "public class Test implements IntConsumer {\n" +
            "    public void accept(int num) {\n" +
            "        if ((byte) num != num)\n" +
            "            throw new IllegalArgumentException();\n" +
            "    }\n" +
            "}\n";

    @Test
    public void outOfBounds() throws Exception {
        ClassLoader cl = new URLClassLoader(new URL[0]);
        Class<?> aClass = CompilerUtils.CACHED_COMPILER.
                loadFromJava(cl, "mytest.Test", code);
        IntConsumer consumer = (IntConsumer) aClass.getDeclaredConstructor().newInstance();
        consumer.accept(1); // ok
        try {
            consumer.accept(128); // no ok
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test(timeout = 90_000)
    public void testMultiThread() throws Exception {
        StringBuilder largeClass = new StringBuilder("package mytest;\n" +
                "public class Test2 implements IntConsumer, java.util.function.IntSupplier {\n" +
                "    static final java.util.concurrent.atomic.AtomicInteger called = new java.util.concurrent.atomic.AtomicInteger(0);\n" +
                "    public int getAsInt() { return called.get(); }\n" +
                "    public void accept(int num) {\n" +
                "        called.incrementAndGet();\n" +
                "    }\n");
        for (int j=0; j<1_000; j++) {
            largeClass.append("    public void accept"+j+"(int num) {\n" +
                    "        if ((byte) num != num)\n" +
                    "            throw new IllegalArgumentException();\n" +
                    "    }\n");
        }
        largeClass.append("}\n");
        final String code2 = largeClass.toString();

        // JRC-TEST-015 requires 64 concurrent requests, independent of the agent's CPU count.
        final int nThreads = 64;
        final CountDownLatch ready = new CountDownLatch(nThreads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger completed = new AtomicInteger();
        final List<Thread> workers = new ArrayList<>();
        final ExecutorService executor = Executors.newFixedThreadPool(nThreads, task -> {
            Thread worker = new Thread(task, "compiler-control-" + workers.size());
            workers.add(worker);
            return worker;
        });
        final List<Future<?>> futures = new ArrayList<>();
        try (URLClassLoader cl = new URLClassLoader(new URL[0]);
             CachedCompiler cc = new CachedCompiler(null, null)) {
            Throwable primaryFailure = null;
            try {
                for (int i = 0; i < nThreads; i++) {
                    final int value = i;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        assertTrue("Compilation start gate timed out", start.await(10, TimeUnit.SECONDS));
                        Class<?> aClass = cc.loadFromJava(cl, "mytest.Test2", code2);
                        IntConsumer consumer = (IntConsumer) aClass.getDeclaredConstructor().newInstance();
                        consumer.accept(value);
                        completed.incrementAndGet();
                        return null;
                    }));
                }
                assertTrue("Compiler workers did not start", ready.await(10, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                start.countDown();
                executor.shutdown();
                for (Future<?> future : futures) {
                    try {
                        future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (TimeoutException timeout) {
                        throw new AssertionError(progress(completed.get(), nThreads, workers), timeout);
                    }
                }
                Class<?> aClass = cc.loadFromJava(cl, "mytest.Test2", code2);
                IntSupplier consumer = (IntSupplier) aClass.getDeclaredConstructor().newInstance();
                assertEquals(nThreads, consumer.getAsInt());
            } catch (Exception | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                start.countDown();
                for (Future<?> future : futures)
                    future.cancel(true);
                executor.shutdownNow();
                try {
                    assertTrue("Compiler workers did not terminate", executor.awaitTermination(10, TimeUnit.SECONDS));
                } catch (InterruptedException | AssertionError cleanupFailure) {
                    if (cleanupFailure instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    if (primaryFailure == null)
                        throw cleanupFailure;
                    primaryFailure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    private static String progress(int completed, int expected, List<Thread> workers) {
        StringBuilder message = new StringBuilder("Concurrent compilation timed out: completed=")
                .append(completed).append('/').append(expected);
        for (Thread worker : workers) {
            message.append('\n').append(worker.getName()).append(' ').append(worker.getState());
            for (StackTraceElement frame : worker.getStackTrace())
                message.append("\n  at ").append(frame);
        }
        return message.toString();
    }
}
