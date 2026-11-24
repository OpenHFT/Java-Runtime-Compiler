/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Test;

import javax.tools.JavaFileManager;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class FileManagerModuleDispatchTest {
    @Test
    public void moduleDispatchWorksWithoutOpeningJdkImplementationPackages() throws Exception {
        Path output = Files.createTempFile("file-manager-modules-", ".log");
        Process process = null;
        try {
            String java = new File(System.getProperty("java.home"), "bin/java").getPath();
            String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            process = new ProcessBuilder(java, "-cp", classPath, Probe.class.getName())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue("Module probe did not finish", process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(new String(Files.readAllBytes(output), StandardCharsets.UTF_8), 0, process.exitValue());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                assertTrue("Module probe did not terminate", process.waitFor(5, TimeUnit.SECONDS));
            }
            Files.deleteIfExists(output);
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            boolean modules;
            try {
                JavaFileManager.class.getMethod("inferModuleName", JavaFileManager.Location.class);
                modules = true;
            } catch (NoSuchMethodException absentOnJava8) {
                modules = false;
            }
            try (MyJavaFileManager manager = new MyJavaFileManager(
                    ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null))) {
                Iterable<Set<JavaFileManager.Location>> locations = manager.listLocationsForModules(
                        StandardLocation.locationFor("SYSTEM_MODULES"));
                if (modules) {
                    boolean foundJavaBase = false;
                    for (Set<JavaFileManager.Location> group : locations)
                        for (JavaFileManager.Location location : group)
                            foundJavaBase |= "java.base".equals(manager.inferModuleName(location));
                    assertTrue("System modules must contain java.base", foundJavaBase);
                } else {
                    assertFalse(locations.iterator().hasNext());
                    assertNull(manager.inferModuleName(StandardLocation.CLASS_PATH));
                }
            }
            if (modules) {
                checkOriginalFailure(new IllegalStateException("delegate exception"));
                checkOriginalFailure(new AssertionError("delegate error"));
            }
        }

        private static void checkOriginalFailure(Throwable expected) {
            StandardJavaFileManager delegate = (StandardJavaFileManager) Proxy.newProxyInstance(
                    Probe.class.getClassLoader(), new Class<?>[]{StandardJavaFileManager.class},
                    (proxy, method, args) -> { throw expected; });
            try {
                new MyJavaFileManager(delegate).inferModuleName(StandardLocation.CLASS_PATH);
                fail("Expected delegate failure");
            } catch (Throwable actual) {
                assertSame(expected, actual);
            }
        }
    }
}
