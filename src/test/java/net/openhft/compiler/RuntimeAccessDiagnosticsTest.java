/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class RuntimeAccessDiagnosticsTest {
    @Test public void guidanceIsSpecificToRuntimeAndBoundary() {
        assertEquals(8, RuntimeAccessDiagnostics.featureVersion("1.8"));
        assertEquals(25, RuntimeAccessDiagnostics.featureVersion("25"));
        assertFalse(RuntimeAccessDiagnostics.guidance(8, false).contains("--add-"));
        for (int version : new int[]{11, 17, 21, 25}) {
            assertTrue(RuntimeAccessDiagnostics.guidance(version, false).contains(RuntimeAccessDiagnostics.FILE_MANAGER));
            assertFalse(RuntimeAccessDiagnostics.guidance(version, false).contains(RuntimeAccessDiagnostics.CLASS_LOADER));
            assertTrue(RuntimeAccessDiagnostics.guidance(version, true).contains(RuntimeAccessDiagnostics.CLASS_LOADER));
        }
    }
    @Test public void unrelatedFailureIsUnchanged() throws Exception {
        RuntimeException original = new IllegalArgumentException("not access");
        assertSame(original, RuntimeAccessDiagnostics.inaccessible(original, String.class.getMethod("length"), false));
    }
    @Test public void realCompilationAndClassDefinition() throws Exception {
        int version = RuntimeAccessDiagnostics.featureVersion(System.getProperty("java.specification.version"));
        if (version <= 11) run(true);
        else {
            assertTrue(run(false, RuntimeAccessDiagnostics.FILE_MANAGER).contains(RuntimeAccessDiagnostics.CLASS_LOADER));
            assertTrue(run(false, RuntimeAccessDiagnostics.CLASS_LOADER).contains(RuntimeAccessDiagnostics.FILE_MANAGER));
            run(true, RuntimeAccessDiagnostics.FILE_MANAGER, RuntimeAccessDiagnostics.CLASS_LOADER);
        }
    }
    @Test public void unavailableCompilerIsNotDescribedAsAModuleAccessFailure() throws Exception {
        int version = RuntimeAccessDiagnostics.featureVersion(System.getProperty("java.specification.version"));
        if (version < 9) {
            assertTrue(RuntimeAccessDiagnostics.missingCompiler(new ClassNotFoundException()).getMessage().contains("tools.jar"));
            return;
        }
        String out = run(false, "--limit-modules=java.base,java.compiler,java.logging,java.management,jdk.unsupported",
                RuntimeAccessDiagnostics.CLASS_LOADER);
        assertTrue(out, out.contains("no usable system Java compiler"));
        assertTrue(out, out.contains("jdk.compiler"));
    }
    private String run(boolean success, String... flags) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.addAll(Arrays.asList(flags)); command.add("-cp");
        command.add(System.getProperty("java.class.path")); command.add(RuntimeAccessProbe.class.getName());
        File output = File.createTempFile("runtime-access", ".log");
        Process child = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output);
            for (String key : Arrays.asList("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
            child = builder.start();
            assertTrue("child timeout", child.waitFor(60, TimeUnit.SECONDS));
            String text = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            assertEquals(text, success, child.exitValue() == 0);
            if (success) assertTrue(text, text.contains("compiled-loaded-42"));
            return text;
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly().waitFor();
            Files.deleteIfExists(output.toPath());
        }
    }
}
