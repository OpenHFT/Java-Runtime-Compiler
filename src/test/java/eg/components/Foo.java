/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package eg.components;

/**
 * Simple data holder used to demonstrate dynamic compilation.
 */
@SuppressWarnings("QuestionableName")
public class Foo {
    @SuppressWarnings("unused")
    public final Bar bar;
    @SuppressWarnings("unused")
    public final Bar copy;
    public final String s;

    /**
     * Creates a new instance.
     *
     * @param bar  first bar dependency
     * @param copy second bar dependency
     * @param s    textual flag for the example
     * @param i    example value representing some business field
     */
    public Foo(Bar bar, Bar copy, String s, int i) {
        this.bar = bar;
        this.copy = copy;
        this.s = s;
    }
}
