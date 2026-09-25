/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import javax.tools.StandardJavaFileManager;
import java.lang.management.ManagementFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/** A separately launched, bounded GC experiment, not a collection-time guarantee. */
public final class CompilerCacheCollectionProbe {
    private static CachedCompiler retainedCompiler;
    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    private static final int SAMPLE_COUNT = 6;

    public static void main(String[] args) throws Exception {
        boolean customManager = args.length != 0 && "custom-manager".equals(args[0]);
        retainedCompiler = new CachedCompiler(null, null);
        if (customManager)
            retainedCompiler.setFileManagerOverride(RetainingManager::new);
        List<WeakReference<?>> references = new ArrayList<>();
        for (int index = 0; index < SAMPLE_COUNT; index++)
            createAndRelease(index, customManager, references);
        WeakReference<Object> progress = new WeakReference<>(new Object());
        long before = collectionCount();
        int attempts = 0;
        int enqueued = 0;
        long started = System.nanoTime();
        for (; attempts < 20; attempts++) {
            System.gc();
            Thread.sleep(25);
            while (QUEUE.poll() != null)
                enqueued++;
            if (!customManager && cleared(references) == SAMPLE_COUNT * 2 && progress.get() == null) {
                attempts++;
                break;
            }
        }
        int cleared = cleared(references);
        long collections = collectionCount() - before;
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        System.out.println("{\"custom_manager\":" + customManager + ",\"samples\":" + SAMPLE_COUNT
                + ",\"weak_references\":" + references.size() + ",\"cleared\":" + cleared
                + ",\"enqueued\":" + enqueued + ",\"gc_requests\":" + attempts
                + ",\"gc_collections\":" + collections + ",\"sentinel_collected\":" + (progress.get() == null)
                + ",\"elapsed_ms\":" + elapsed + "}");
        try {
            if (progress.get() != null || collections == 0)
                throw new IllegalStateException("INCONCLUSIVE: no observed GC progress");
            if (customManager ? cleared != 0 : cleared != SAMPLE_COUNT * 2)
                throw new AssertionError("Observed lifetime differs from the selected graph");
            // Keep the compiler observably reachable through the complete collection interval.
            System.out.println("retainedCompiler=" + System.identityHashCode(retainedCompiler));
        } finally {
            retainedCompiler.close();
        }
    }

    private static void createAndRelease(int index, boolean customManager,
                                         List<WeakReference<?>> references) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[0], CompilerCacheCollectionProbe.class.getClassLoader())) {
            String name = "disposable.Generated" + index;
            Class<?> type = retainedCompiler.loadFromJava(loader, name,
                    "package disposable; public class Generated" + index + " { public static int value() { return 42; } }");
            if (!Integer.valueOf(42).equals(type.getMethod("value").invoke(null)))
                throw new AssertionError("Generated class was not usable");
            if (type != retainedCompiler.loadFromJava(loader, name, "invalid source"))
                throw new AssertionError("Live class was not reused");
            if (customManager)
                retainedCompiler.updateFileManagerForClassLoader(loader, manager -> ((RetainingManager) manager).retainedClass = type);
            references.add(new WeakReference<>(loader, QUEUE));
            references.add(new WeakReference<>(type, QUEUE));
        }
    }

    private static int cleared(List<WeakReference<?>> references) {
        int result = 0;
        for (WeakReference<?> reference : references)
            if (reference.get() == null)
                result++;
        return result;
    }

    private static long collectionCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static final class RetainingManager extends MyJavaFileManager {
        private Class<?> retainedClass;

        private RetainingManager(StandardJavaFileManager delegate) {
            super(delegate);
        }
    }
}
