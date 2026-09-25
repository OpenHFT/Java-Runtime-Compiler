/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real product calls with Linux descriptor observations; no forced GC is used. */
public class CompilerUtilsStreamOwnershipTest {
    private static final byte[] ORIGINAL = {1, 2, 3, 4};
    private static final byte[] REPLACEMENT = {5, 6, 7};
    private static final Path DESCRIPTORS = Paths.get("/proc/self/fd");
    private static final String FULL_DEVICE = "/dev/full";

    @Rule
    public final TemporaryFolder temporary = TemporaryFolder.builder().assureDeletion().build();

    @Before
    public void requireDescriptorObservations() {
        assumeTrue("Linux descriptor observations required", Files.isDirectory(DESCRIPTORS));
    }

    @Test
    public void successfulReadClosesItsStream() throws Exception {
        File file = existingFile();
        assertArrayEquals(ORIGINAL, readBytes(file));
        assertNoOwnedDescriptors();
    }

    @Test
    public void newWriteClosesItsStream() throws Exception {
        File file = new File(temporary.getRoot(), "new.bin");
        assertTrue(CompilerUtils.writeBytes(file, ORIGINAL));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(file.toPath()));
        assertNoOwnedDescriptors();
    }

    @Test
    public void unchangedWriteClosesItsReadStream() throws Exception {
        File file = existingFile();
        assertFalse(CompilerUtils.writeBytes(file, ORIGINAL));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(file.toPath()));
        assertFalse(new File(file.getParentFile(), file.getName() + ".bak").exists());
        assertNoOwnedDescriptors();
    }

    @Test
    public void replacementClosesReadBeforeRenameAndWriteBeforeReturn() throws Exception {
        File file = existingFile();
        File observed = new File(file.getPath()) {
            @Override
            public boolean renameTo(File destination) {
                assertNoOwnedDescriptorsUnchecked();
                return super.renameTo(destination);
            }
        };
        assertTrue(CompilerUtils.writeBytes(observed, REPLACEMENT));
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(file.toPath()));
        assertFalse(new File(file.getParentFile(), file.getName() + ".bak").exists());
        assertNoOwnedDescriptors();
    }

    @Test
    public void shortReadClosesItsStreamAndPreservesTheCause() throws Exception {
        File file = existingFile();
        File shortRead = new File(file.getPath()) {
            @Override
            public long length() {
                return super.length() + 1;
            }
        };
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> readBytes(shortRead));
        assertTrue(failure.getCause() instanceof EOFException);
        assertTrue(failure.getMessage().contains(file.getPath()));
        assertArrayEquals(ORIGINAL, Files.readAllBytes(file.toPath()));
        assertNoOwnedDescriptors();
    }

    @Test
    public void failedNewWriteClosesBeforeCleanup() throws Exception {
        assertWriteFailure(false);
    }

    @Test
    public void failedReplacementClosesBeforeRestoringOriginalBytes() throws Exception {
        assertWriteFailure(true);
    }

    private void assertWriteFailure(boolean replacing) throws Exception {
        assumeTrue("A writable /dev/full is needed to fail after opening", new File(FULL_DEVICE).canWrite());
        File actual = replacing ? existingFile() : new File(temporary.getRoot(), "failed-new.bin");
        long openBefore = descriptorCount(FULL_DEVICE, false);
        FailedWriteFile failing = new FailedWriteFile(actual, !replacing, openBefore);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CompilerUtils.writeBytes(failing, REPLACEMENT));
        assertTrue("Failure must be from the opened output", failure.getCause() instanceof IOException);
        assertFalse("An open/setup failure does not exercise write cleanup",
                failure.getCause() instanceof FileNotFoundException);
        assertTrue(failure.getMessage().contains(actual.getPath()));
        assertEquals("Rollback reached the observed deletion boundary", 1, failing.deleteCalls);
        assertEquals("The failing output must be closed", openBefore, descriptorCount(FULL_DEVICE, false));
        if (replacing) {
            assertTrue(failing.backupCreated);
            assertArrayEquals(ORIGINAL, Files.readAllBytes(actual.toPath()));
        } else {
            assertFalse(actual.exists());
        }
        assertFalse(new File(actual.getParentFile(), actual.getName() + ".bak").exists());
    }

    /*
     * Redirect only the stream path to a real post-open write failure. All file
     * metadata, rename and delete operations target our owned temporary file.
     * Reset the path before rollback so the device is never renamed or deleted.
     */
    private static final class FailedWriteFile extends File {
        private final File actual;
        private final long openBefore;
        private boolean redirectOutput;
        private boolean backupCreated;
        private int deleteCalls;

        private FailedWriteFile(File actual, boolean redirectOutput, long openBefore) {
            super(actual.getPath());
            this.actual = actual;
            this.redirectOutput = redirectOutput;
            this.openBefore = openBefore;
        }

        @Override
        public String getPath() {
            return redirectOutput ? FULL_DEVICE : super.getPath();
        }

        @Override
        public boolean exists() {
            return actual.exists();
        }

        @Override
        public long length() {
            return actual.length();
        }

        @Override
        public boolean renameTo(File destination) {
            boolean renamed = actual.renameTo(destination);
            backupCreated = renamed;
            redirectOutput = renamed;
            return renamed;
        }

        @Override
        public boolean delete() {
            redirectOutput = false;
            deleteCalls++;
            try {
                assertEquals("Output must close before rollback deletes/restores files",
                        openBefore, descriptorCount(FULL_DEVICE, false));
            } catch (IOException failure) {
                throw new AssertionError("Cannot observe output ownership", failure);
            }
            return actual.delete();
        }
    }

    private File existingFile() throws IOException {
        File file = temporary.newFile("content.bin");
        Files.write(file.toPath(), ORIGINAL);
        return file;
    }

    private static byte[] readBytes(File file) throws Exception {
        Method method = CompilerUtils.class.getDeclaredMethod("readBytes", File.class);
        method.setAccessible(true);
        try {
            return (byte[]) method.invoke(null, file);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException)
                throw (RuntimeException) failure.getCause();
            throw failure;
        }
    }

    private void assertNoOwnedDescriptors() throws IOException {
        assertEquals("Product call retained a descriptor for its temporary files", 0,
                descriptorCount(temporary.getRoot().getCanonicalPath() + File.separator, true));
    }

    private void assertNoOwnedDescriptorsUnchecked() {
        try {
            assertNoOwnedDescriptors();
        } catch (IOException failure) {
            throw new AssertionError("Cannot observe file ownership", failure);
        }
    }

    private static long descriptorCount(String target, boolean prefix) throws IOException {
        try (Stream<Path> descriptors = Files.list(DESCRIPTORS)) {
            return descriptors.filter(path -> {
                try {
                    String link = Files.readSymbolicLink(path).toString();
                    return prefix ? link.startsWith(target) : link.equals(target);
                } catch (IOException changed) {
                    return false;
                }
            }).count();
        }
    }
}
