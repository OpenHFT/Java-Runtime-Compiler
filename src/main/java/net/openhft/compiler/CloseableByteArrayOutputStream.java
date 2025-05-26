/*
 * Copyright 2014-2025 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
