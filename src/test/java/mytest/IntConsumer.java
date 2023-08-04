/*
 * Copyright 2016-2022 chronicle.software
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

package mytest;

// `IntConsumer` interface defining a contract for classes that implement it.
// This functional interface represents an operation that accepts a single integer input argument
// and returns no result. It is used to perform an action on the given integer value.
public interface IntConsumer {
    // Abstract method that must be implemented by any class implementing this interface.
    // It takes an integer value and performs an operation defined by the implementing class.
    void accept(int num);
}
