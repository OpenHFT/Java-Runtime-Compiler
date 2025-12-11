/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * ByteArrayOutputStream that completes a {@link CompletableFuture} when closed.
 * The future ties into the JDK compiler's asynchronous behaviour so callers can
 * wait for compiler output.
 */
public class CloseableByteArrayOutputStream extends ByteArrayOutputStream {
    /**
     * Future completed once the stream is closed, signalling closure.
     */
    private final CompletableFuture<?> closeFuture = new CompletableFuture<>();

    /**
     * Creates an empty stream that completes {@link #closeFuture()} on close.
     */
    public CloseableByteArrayOutputStream() {
        super();
    }

    @Override
    public void close() {
        closeFuture.complete(null);
    }

    /**
     * Return the future that completes when {@link #close()} is called.  Callers
     * may block on this to synchronise with the compiler's asynchronous
     * behaviour.
     *
     * @return future signalling stream closure
     */
    public CompletableFuture<?> closeFuture() {
        return closeFuture;
    }
}
