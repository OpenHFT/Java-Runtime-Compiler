/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class FileHandleReleaseTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void unchangedContentReleasesInputFile() throws Exception {
        Path file = temporary.newFile("unchanged.txt").toPath();
        Files.write(file, "unchanged".getBytes(StandardCharsets.UTF_8));
        assertFalse(CompilerUtils.writeText(file.toFile(), "unchanged"));
        assertReleased(file);
    }

    @Test
    public void newContentReleasesOutputFile() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("created.txt");
        assertTrue(CompilerUtils.writeText(file.toFile(), "created"));
        assertReleased(file);
        assertEquals("created", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    public void replacedContentLeavesNoBackupOrOpenFile() throws Exception {
        Path file = temporary.newFile("replaced.txt").toPath();
        Files.write(file, new byte[]{1});
        assertTrue(CompilerUtils.writeBytes(file.toFile(), new byte[]{2, 3}));
        assertReleased(file);
        assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(file));
        assertFalse(Files.exists(file.resolveSibling("replaced.txt.bak")));
    }

    private static void assertReleased(Path file) throws Exception {
        // Linux allows renaming an open file, so inspect only descriptors for this file.
        Path descriptors = Paths.get("/proc/self/fd");
        if (Files.isDirectory(descriptors)) {
            Path absolute = file.toRealPath();
            try (Stream<Path> entries = Files.list(descriptors)) {
                long retained = entries.filter(entry -> {
                    try {
                        return Files.readSymbolicLink(entry).equals(absolute);
                    } catch (Exception ignored) {
                        return false;
                    }
                }).count();
                assertEquals("Open descriptors for " + file, 0L, retained);
            }
        }
        // Windows refuses to rename a file while these streams retain its handle.
        Path renamed = file.resolveSibling(file.getFileName() + ".moved");
        Files.move(file, renamed);
        Files.move(renamed, file);
    }
}
