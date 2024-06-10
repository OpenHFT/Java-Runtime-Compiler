package net.openhft.compiler;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

public class ClassUnloadingTest extends TestCase {

    public static void main(String[] args) throws Exception {
        new ClassUnloadingTest().testClassUnloading();
    }

    /**
     * To observe unloading in real time you can start the JVM with {@code -Xlog:class+unload=info}.
     *
     * @throws Exception if test fails
     */
    public void testClassUnloading() throws Exception {

        final ReferenceQueue<Class<Callable<Integer>>> queue = new ReferenceQueue<>();
        final WeakReference<Class<Callable<Integer>>> wr;

        // Create a new child class loader which will allow us to trigger class unloading manually.
        URLClassLoader cl = new URLClassLoader(new URL[0], ClassUnloadingTest.class.getClassLoader());
        @SuppressWarnings("unchecked")
        Class<Callable<Integer>> clazz = (Class<Callable<Integer>>) CompilerUtils.CACHED_COMPILER.loadFromJava(
            cl, "unload.TestCallable",
            "package unload;\n" +
            "\n" +
            "import java.util.concurrent.Callable;\n" +
            "\n" +
            "public class TestCallable implements Callable<Integer> {\n" +
            "    @Override\n" +
            "    public Integer call() {\n" +
            "        return 42;\n" +
            "    }\n" +
            "}"
        );
        // We need to retain a strong reference to the WeakReference otherwise the garbage collected clazz won't be passed to the queue.
        wr = new WeakReference<>(clazz, queue);

        assertEquals("Was expecting 42.", 42, (int) clazz.newInstance().call());

        { // Class unloading section
            // There is a circular dependency between clazz and the class loader through which it was loaded.
            // To trigger class unloading of clazz, we need to do three things:
            clazz = null; // 1. unset the strong reference to clazz in this test
            cl.close();   // 2. close() the class loader (optional)
            cl = null;    // 3. unset the strong reference to the class loader in this test
            // At this point there are only weak references to clazz and the class loader in this test and in CACHED_COMPILER.
            // In few collection cycles GC will invalidate the entries in CACHED_COMPILER's internal datastructures.
            // We will observe this indirectly via our WeakReference and the corresponding queue.

            System.gc(); // Trigger the 1st GC cycle
            System.runFinalization(); // Trigger finalizers
        }

        // Assume unloading will take place within 5 seconds.
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        // Wait for clazz to get unloaded. Trigger GC cycle every 100ms if clazz is still reachable.
        while (queue.remove(100L) == null) {
            assertTrue(
                "Class unloading should have completed within 5 seconds but haven't.",
                System.nanoTime() < deadline
            );
            System.gc(); // Trigger GC
            System.runFinalization(); // Trigger finalizers
        }

        assertNull("Class should have been unloaded at this point and is not reachable from WeakReference.", wr.get());
    }
}
