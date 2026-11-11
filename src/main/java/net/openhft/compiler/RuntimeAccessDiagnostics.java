/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import java.lang.reflect.Method;

/** Failure-specific classpath guidance; no direct dependency on Java 9 exception types. */
final class RuntimeAccessDiagnostics {
    static final String FILE_MANAGER = "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED";
    static final String CLASS_LOADER = "--add-opens=java.base/java.lang=ALL-UNNAMED";
    private RuntimeAccessDiagnostics() { }

    static int featureVersion(String version) {
        String number = version.startsWith("1.") ? version.substring(2) : version;
        int dot = number.indexOf('.');
        return Integer.parseInt(dot < 0 ? number : number.substring(0, dot));
    }
    static String guidance(int version, boolean classLoader) {
        if (version < 9) return "Use a full JDK with its compiler/tools.jar; Java 8 does not accept module options";
        return "For classpath deployment, add " + (classLoader ? CLASS_LOADER : FILE_MANAGER)
                + "; named modules need a target appropriate to their module name";
    }
    // InaccessibleObjectException does not exist on Java 8, which must still load this class.
    @SuppressWarnings("java:S1872")
    static RuntimeException inaccessible(RuntimeException failure, Method method, boolean classLoader) {
        if (!"java.lang.reflect.InaccessibleObjectException".equals(failure.getClass().getName())) return failure;
        String runtime = System.getProperty("java.specification.version");
        String message = "Java " + runtime + ": unable to access " + method.getDeclaringClass().getName()
                + "." + method.getName() + ". " + guidance(featureVersion(runtime), classLoader)
                + " (" + failure.getMessage() + ")";
        try {
            RuntimeException hinted = failure.getClass().asSubclass(RuntimeException.class)
                    .getConstructor(String.class).newInstance(message);
            hinted.initCause(failure);
            return hinted;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return new IllegalStateException(message, failure);
        }
    }
    static AssertionError missingCompiler(Throwable cause) {
        String version = System.getProperty("java.specification.version");
        return new AssertionError("Java " + version + ": no usable system Java compiler. "
                + (featureVersion(version) < 9 ? "Use a full JDK with tools.jar on the compiler classpath."
                : "Use a full JDK/runtime image containing jdk.compiler; module access flags cannot supply a missing compiler."), cause);
    }
}
