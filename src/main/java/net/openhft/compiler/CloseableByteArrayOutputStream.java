/*
 * Copyright 2014 Higher Frequency Trading
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
 * This class extends ByteArrayOutputStream and provides a CompletableFuture
 * that completes when the stream is closed.
 */
public class CloseableByteArrayOutputStream extends ByteArrayOutputStream {
    // CompletableFuture that completes when the stream is closed
    private final CompletableFuture<?> closeFuture = new CompletableFuture<>();

    /**
     * Closes this output stream and completes the closeFuture.
     */
    @Override
    public void close() {
        // Complete the closeFuture to signal that the stream has been closed
        closeFuture.complete(null);
    }

    /**
     * Returns the CompletableFuture that completes when the stream is closed.
     *
     * @return The CompletableFuture that completes when the stream is closed
     */
    public CompletableFuture<?> closeFuture() {
        return closeFuture;
    }
}
