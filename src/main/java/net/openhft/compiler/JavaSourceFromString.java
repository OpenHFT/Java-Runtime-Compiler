/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.jetbrains.annotations.NotNull;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/*
 * An internal SimpleJavaFileObject implementation representing Java source
 * code provided as a String, allowing the Java compiler to read source
 * directly from memory. Example URI: string:///com/example/Hello.java. The
 * contents are expected to be UTF-8.
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
     *             (annotated with {@link org.jetbrains.annotations.NotNull})
     * @param code the source code for the compilation unit represented by this file object
     */
    JavaSourceFromString(@NotNull String name, String code) {
        super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension),
                Kind.SOURCE);
        this.code = code;
    }

    /** Returns the Java source code. */
    @SuppressWarnings("RefusedBequest") // Directly returns the stored code string, ignoring encoding-error handling because the source is already held in memory.
    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return code;
    }
}
