/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.jetbrains.annotations.NotNull;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/**
 * {@link javax.tools.JavaFileObject} backed by a String of source code.
 * <p>
 * Allows the JDK compiler to consume in-memory source via a synthetic URI such as
 * {@code string:///com/example/Hello.java}, avoiding the need for temporary files.
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
