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
 * A closeable extension of ByteArrayOutputStream that encapsulates a CompletableFuture
 * to be completed when the stream is closed. This class provides additional functionality
 * to monitor and handle the closure of the ByteArrayOutputStream.
 *
 * @since 2023-08-04
 */
public class CloseableByteArrayOutputStream extends ByteArrayOutputStream {
    private final CompletableFuture<?> closeFuture = new CompletableFuture<>();

    /**
     * Closes the ByteArrayOutputStream and completes the close future.
     */
    @Override
    public void close() {
        closeFuture.complete(null);
    }

    /**
     * Returns the CompletableFuture associated with the close operation of the stream.
     *
     * @return The CompletableFuture representing the close operation
     */
    public CompletableFuture<?> closeFuture() {
        return closeFuture;
    }
}
