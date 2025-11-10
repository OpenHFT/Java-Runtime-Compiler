//
// Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
//
package eg;

import eg.components.BarImpl;
import eg.components.Foo;
import eg.components.TeeImpl;

public class FooBarTee {
    private final String name;
    private final TeeImpl tee;
    private final BarImpl bar;
    private final BarImpl copy;
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

    private void stop() {
    }

    public void close() {
        stop();

    }
}
