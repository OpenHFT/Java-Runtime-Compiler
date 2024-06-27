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

package net.openhft.compiler;

import org.jetbrains.annotations.NotNull;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/**
 * A file object used to represent source coming from a string.
 */
class JavaSourceFromString extends SimpleJavaFileObject {
    /**
     * The source code of this "file".
     */
    private final String code;

    /**
     * Constructs a new JavaSourceFromString.
     *
     * @param name the name of the compilation unit represented by this file object
     * @param code the source code for the compilation unit represented by this file object
     */
    JavaSourceFromString(@NotNull String name, String code) {
        // Create a URI for the source file. The Kind.SOURCE indicates that this is a source file.
        super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension),
                Kind.SOURCE);
        this.code = code;
    }

    /**
     * Returns the source code content of this file object.
     *
     * @param ignoreEncodingErrors If true, encoding errors are ignored
     * @return The source code as a CharSequence
     */
    @SuppressWarnings("RefusedBequest")
    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        // Return the source code content
        return code;
    }
}
