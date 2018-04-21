/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

public class FooBarTee {
    public final String name;
    public final TeeImpl tee;
    public final BarImpl bar;
    public final BarImpl copy;
    public final Foo foo;

    public FooBarTee(String name) {
        // when viewing this file, ensure it is synchronised with the copy on disk.
        System.out.println("generated test Tue Aug 11 07:09:54 BST 2015");
        this.name = name;

        tee = new TeeImpl("test");

        bar = new BarImpl(tee, 55);

        copy = new BarImpl(tee, 555);

        // you should see the current date here after synchronisation.
        foo = new Foo(bar, copy, "generated test Tue Aug 11 07:09:54 BST 2015", 5);
    }

    public void start() {
    }

    public void stop() {
    }

    public void close() {
        stop();

    }
}
