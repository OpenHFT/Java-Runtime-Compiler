/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package eg.components;

/** Immutable implementation of {@link Tee}. */
public class TeeImpl implements Tee {
    /** `s` is final and set via the constructor. */
    final String s;

    public TeeImpl(String s) {
        this.s = s;
    }

    public String getS() {
        return s;
    }
}
