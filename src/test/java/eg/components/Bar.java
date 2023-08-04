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

// `Bar` interface defining a contract for classes that implement it.
// It mandates the implementation of methods that retrieve a `Tee` object and an integer value.
interface Bar {
    // Abstract method that must be implemented by any class implementing this interface.
    // It is expected to return an object that implements the `Tee` interface.
    Tee getTee();

    // Abstract method that must be implemented by any class implementing this interface.
    // It is expected to return an integer value.
    int getI();
}
