/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package eg;

import eg.components.BarImpl;
import eg.components.Foo;
import eg.components.TeeImpl;

public class FooBarTee {
    public final Foo foo;

    public FooBarTee(String name) {

        TeeImpl tee = new TeeImpl("test");

        BarImpl bar = new BarImpl(tee, 55);

        BarImpl copy = new BarImpl(tee, 555);

        // ${generatedDate}
        // Build scripts replace the token with the current date.
        foo = new Foo(bar, copy, "generated test ${generatedDate}", name.length());
    }

    public void start() {
    }

    private void stop() {
    }

    public void close() {
        stop();

    }
}
