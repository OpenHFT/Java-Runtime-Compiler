/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler.anchorone;

import java.lang.invoke.MethodHandles;

public final class AnchorOne {
    private AnchorOne() {
    }

    public static MethodHandles.Lookup lookup() {
        return MethodHandles.lookup();
    }
}
