/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package eg.components;

interface Bar {
    /**
     * The {@code Tee} component injected into the bar.
     */
    Tee getTee();

    /**
     * The integer value supplied when the bar was constructed.
     */
    int getI();
}
