/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package eg;

import eg.components.BarImpl;
import eg.components.Foo;
import eg.components.TeeImpl;

public class FooBarTee {
    public final String name;
    public final TeeImpl tee;
    public final BarImpl bar;
    public final BarImpl copy;
    public Foo foo;

    public FooBarTee(String name) {
        this.name = name;

        tee = new TeeImpl("test");

        bar = new BarImpl(tee, 55);

        copy = new BarImpl(tee, 555);

        // ${generatedDate}
        // Build scripts replace the token with the current date.
        foo = new Foo(bar, copy, "generated test ${generatedDate}", 5);
    }

    public void start() {
    }

    public void stop() {
    }

    public void close() {
        stop();

    }
}
