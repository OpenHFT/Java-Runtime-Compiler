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
package eg;

import eg.components.BarImpl;
import eg.components.Foo;
import eg.components.TeeImpl;

// `FooBarTee` class that encapsulates properties related to `TeeImpl`, `BarImpl`, and `Foo`.
public class FooBarTee {
    // Immutable string field for storing the name.
    public final String name;

    // Immutable reference to an object of the `TeeImpl` class.
    public final TeeImpl tee;

    // Immutable reference to an object of the `BarImpl` class.
    public final BarImpl bar;

    // Immutable reference to another object of the `BarImpl` class, which may be a copy or another instance.
    public final BarImpl copy;

    // Mutable reference to a `Foo` object.
    public Foo foo;

    // Constructor for `FooBarTee` that initializes the fields and creates objects for `TeeImpl`, `BarImpl`, and `Foo`.
    public FooBarTee(String name) {
        this.name = name; // Initialize the `name` field with the provided value.

        // Initialize the `tee` field with a new `TeeImpl` object and a test value.
        tee = new TeeImpl("test");

        // Initialize the `bar` field with a new `BarImpl` object and specific values.
        bar = new BarImpl(tee, 55);

        // Initialize the `copy` field with another new `BarImpl` object and specific values.
        copy = new BarImpl(tee, 555);

        // Initialize the `foo` field with a new `Foo` object and specific values.
        // The current date should be synchronized here.
        foo = new Foo(bar, copy, "generated test Tue Aug 11 07:09:54 BST 2015", 5);
    }

    // Method to start the `FooBarTee` object; implementation details to be added.
    public void start() {
    }

    // Method to stop the `FooBarTee` object; implementation details to be added.
    public void stop() {
    }

    // Method to close the `FooBarTee` object; it calls the `stop` method.
    public void close() {
        stop(); // Call to the `stop` method to perform the closing operation.
    }
}
