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

package eg.components;

@SuppressWarnings({"QuestionableName"})
public class Foo {
    public final Bar bar;
    public final Bar copy;
    public final String s;
    public final int i;

    public Foo(Bar bar, Bar copy, String s, int i) {
        this.bar = bar;
        this.copy = copy;
        this.s = s;
        this.i = i;
    }
}
