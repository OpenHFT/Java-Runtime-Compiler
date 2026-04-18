/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package eg.components;

/**
 * Sample implementation used for tests.
 */

public class BarImpl implements Bar {
    private final int i;
    private final Tee tee;

    public BarImpl(Tee tee, int i) {
        this.tee = tee;
        this.i = i;
    }

    @Override
    public Tee getTee() {
        return tee;
    }

    @Override
    public int getI() {
        return i;
    }
}
