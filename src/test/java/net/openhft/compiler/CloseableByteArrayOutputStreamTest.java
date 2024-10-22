package net.openhft.compiler;

import junit.framework.TestCase;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class CloseableByteArrayOutputStreamTest extends TestCase {

    private CloseableByteArrayOutputStream baos;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        baos = new CloseableByteArrayOutputStream();
    }

    public void testClose() {
        assertFalse(baos.closeFuture().isDone());
        baos.close();
        assertTrue(baos.closeFuture().isDone());
    }

    public void testCloseFuture() throws ExecutionException, InterruptedException {
        CompletableFuture<?> future = baos.closeFuture();
        assertFalse(future.isDone());

        baos.close();
        assertTrue(future.isDone());
        future.get(); // Ensure that future completes without exceptions
    }

    public void testWrite() throws IOException {
        String testString = "Hello, world!";
        baos.write(testString.getBytes());
        assertEquals(testString, baos.toString());
    }

    public void testSize() throws IOException {
        String testString = "Hello, world!";
        baos.write(testString.getBytes());
        assertEquals(testString.length(), baos.size());
    }
}
