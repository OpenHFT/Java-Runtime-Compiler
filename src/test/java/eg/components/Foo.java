/*
 * Copyright 2013-2026 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package eg.components;

@SuppressWarnings("QuestionableName")
/**
 * Simple data holder used to demonstrate dynamic compilation.
 */
public class Foo {
    private final Bar bar;
    private final Bar copy;
    public final String s;
    private final int i;

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
        this.i = i;
    }
}
