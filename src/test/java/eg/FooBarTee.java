//
// Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
//

/*
 * Copyright 2014-2025 chronicle.software
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
