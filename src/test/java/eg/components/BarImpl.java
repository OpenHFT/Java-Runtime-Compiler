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

// `BarImpl` class that implements the `Bar` interface.
public class BarImpl implements Bar {
    // Immutable integer field `i`.
    final int i;

    // Immutable reference to an object of the `Tee` class.
    final Tee tee;

    // Constructor for `BarImpl` that initializes the `tee` and `i` fields.
    public BarImpl(Tee tee, int i) {
        this.tee = tee; // Initialize the `tee` field with the provided value.
        this.i = i;     // Initialize the `i` field with the provided value.
    }

    // Getter method to retrieve the `tee` field.
    public Tee getTee() {
        return tee;
    }

    // Getter method to retrieve the `i` field.
    public int getI() {
        return i;
    }
}
