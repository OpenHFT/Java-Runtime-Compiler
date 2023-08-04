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

package eg.components;

@SuppressWarnings({"QuestionableName"})
// `Foo` class that encapsulates several properties including objects that implement the `Bar` interface.
public class Foo {
    // Immutable reference to an object that implements the `Bar` interface.
    public final Bar bar;

    // Immutable reference to another object that implements the `Bar` interface, which may be a copy or another instance.
    public final Bar copy;

    // Immutable string field.
    public final String s;

    // Immutable integer field.
    public final int i;

    // Constructor for `Foo` that initializes the `bar`, `copy`, `s`, and `i` fields.
    public Foo(Bar bar, Bar copy, String s, int i) {
        this.bar = bar; // Initialize the `bar` field with the provided value.
        this.copy = copy; // Initialize the `copy` field with the provided value.
        this.s = s; // Initialize the `s` (string) field with the provided value.
        this.i = i; // Initialize the `i` (integer) field with the provided value.
    }
}
