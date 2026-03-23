/*
 * Copyright 2013-2025 chronicle.software; SPDX-License-Identifier: Apache-2.0
 */
package net.openhft.compiler;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AiRuntimeGuardrailsTest {

    @Test
    public void validatorStopsCompilationAndRecordsFailure() {
        AtomicInteger compileInvocations = new AtomicInteger();
        TelemetryProbe telemetry = new TelemetryProbe();
        GuardrailedCompilerPipeline pipeline = new GuardrailedCompilerPipeline(
                Arrays.asList(
                        source -> {
                            // basic guard: ban java.lang.System exit calls
                            if (source.contains("System.exit")) {
                                throw new ValidationException("System.exit is not allowed");
                            }
                        },
                        source -> {
                            if (source.contains("java.io.File")) {
                                throw new ValidationException("File IO is not permitted");
                            }
                        }
                ),
                telemetry,
                (className, source) -> {
                    compileInvocations.incrementAndGet();
                    try {
                        return Class.forName("java.lang.Object");
                    } catch (ClassNotFoundException e) {
                        throw new AssertionError("JDK runtime missing java.lang.Object", e);
                    }
                }
        );

        try {
            pipeline.compile("agent-A", "BadClass", "class BadClass { void x() { System.exit(0); } }");
            fail("Expected validation failure");
        } catch (ValidationException expected) {
            // expected
        } catch (Exception unexpected) {
            fail("Unexpected checked exception: " + unexpected.getMessage());
        }

        assertEquals(0, compileInvocations.get(), "Compilation must not run after validation rejection");
        assertEquals(1, telemetry.compileAttempts("agent-A"));
        assertEquals(1, telemetry.validationFailures("agent-A"));
        assertEquals(0, telemetry.successes("agent-A"));
        assertEquals(0, telemetry.compileFailures("agent-A"));
        assertFalse(telemetry.hasLatency("agent-A"), "Latency should not be recorded for rejected source");
    }

    @Test
    public void successfulCompilationRecordsMetrics() throws Exception {
        TelemetryProbe telemetry = new TelemetryProbe();
        GuardrailedCompilerPipeline pipeline = new GuardrailedCompilerPipeline(
                Collections.singletonList(source -> {
                    if (!source.contains("class")) {
                        throw new ValidationException("Missing class keyword");
                    }
                }),
                telemetry,
                new CachedCompilerInvoker()
        );

        Class<?> clazz = pipeline.compile("agent-B", "OkClass",
                "public class OkClass { public int add(int a, int b) { return a + b; } }");

        assertEquals(1, telemetry.compileAttempts("agent-B"), "agent-B should see exactly one attempt");
        assertEquals(0, telemetry.validationFailures("agent-B"));
        assertEquals(1, telemetry.successes("agent-B"));
        assertEquals(0, telemetry.compileFailures("agent-B"));
        assertTrue(telemetry.hasLatency("agent-B"), "Latency must be captured for successful compilation");

        Object instance = clazz.getDeclaredConstructor().newInstance();
        int sum = (int) clazz.getMethod("add", int.class, int.class).invoke(instance, 2, 3);
        assertEquals(5, sum);
    }

    @Test
    public void cacheHitDoesNotRecompileButRecordsMetric() throws Exception {
        AtomicInteger rawCompileCount = new AtomicInteger();
        TelemetryProbe telemetry = new TelemetryProbe();
        GuardrailedCompilerPipeline pipeline = new GuardrailedCompilerPipeline(
                Collections.emptyList(),
                telemetry,
                (className, source) -> {
                    rawCompileCount.incrementAndGet();
                    return CompilerUtils.CACHED_COMPILER.loadFromJava(className, source);
                }
        );

        String source = "public class CacheCandidate { public String id() { return \"ok\"; } }";
        Class<?> first = pipeline.compile("agent-C", "CacheCandidate", source);
        Class<?> second = pipeline.compile("agent-C", "CacheCandidate", source);

        assertEquals(1, rawCompileCount.get(), "Underlying compiler should only run once thanks to caching");
        assertEquals(2, telemetry.compileAttempts("agent-C"));
        assertEquals(0, telemetry.validationFailures("agent-C"));
        assertEquals(1, telemetry.successes("agent-C"));
        assertEquals(0, telemetry.compileFailures("agent-C"));
        assertEquals(1, telemetry.cacheHits("agent-C"), "Cache hit count should be tracked");
        assertTrue(first == second);
    }

    @Test
    public void compilerFailureRecordedSeparately() {
        TelemetryProbe telemetry = new TelemetryProbe();
        GuardrailedCompilerPipeline pipeline = new GuardrailedCompilerPipeline(
                Collections.singletonList(source -> {
                    if (source.contains("forbidden")) {
                        throw new ValidationException("Forbidden token");
                    }
                }),
                telemetry,
                (className, source) -> {
                    throw new ClassNotFoundException("Simulated compiler failure");
                }
        );

        try {
            pipeline.compile("agent-D", "Broken", "public class Broken { }");
            fail("Expected compiler failure");
        } catch (ClassNotFoundException expected) {
            // expected
        } catch (Exception unexpected) {
            fail("Unexpected exception: " + unexpected.getMessage());
        }

        assertEquals(1, telemetry.compileAttempts("agent-D"));
        assertEquals(0, telemetry.validationFailures("agent-D"));
        assertEquals(0, telemetry.successes("agent-D"));
        assertEquals(1, telemetry.compileFailures("agent-D"));
        assertFalse(telemetry.hasCacheHits("agent-D"), "Failure should not record cache hits");
    }

    private static final class GuardrailedCompilerPipeline {
        private final List<SourceValidator> validators;
        private final TelemetryProbe telemetry;
        private final CompilerInvoker compilerInvoker;
        private final Map<String, Class<?>> cache = new ConcurrentHashMap<>();

        GuardrailedCompilerPipeline(List<SourceValidator> validators,
                                    TelemetryProbe telemetry,
                                    CompilerInvoker compilerInvoker) {
            this.validators = new ArrayList<>(validators);
            this.telemetry = telemetry;
            this.compilerInvoker = compilerInvoker;
        }

        Class<?> compile(String agentId, String className, String source) throws Exception {
            telemetry.recordAttempt(agentId);
            for (SourceValidator validator : validators) {
                try {
                    validator.validate(source);
                } catch (ValidationException e) {
                    telemetry.recordValidationFailure(agentId);
                    throw e;
                }
            }

            Class<?> cached = cache.get(className);
            if (cached != null) {
                telemetry.recordCacheHit(agentId);
                return cached;
            }

            long start = System.nanoTime();
            try {
                Class<?> compiled = compilerInvoker.compile(className, source);
                cache.put(className, compiled);
                telemetry.recordSuccess(agentId, System.nanoTime() - start);
                return compiled;
            } catch (ClassNotFoundException | RuntimeException e) {
                telemetry.recordCompileFailure(agentId);
                throw e;
            }
        }
    }

    @FunctionalInterface
    private interface CompilerInvoker {
        Class<?> compile(String className, String source) throws Exception;
    }

    @FunctionalInterface
    private interface SourceValidator {
        void validate(String source);
    }

    private static final class ValidationException extends RuntimeException {
        ValidationException(String message) {
            super(message);
        }
    }

    private static final class TelemetryProbe {
        private final Map<String, AtomicInteger> attempts = new HashMap<>();
        private final Map<String, AtomicInteger> successes = new HashMap<>();
        private final Map<String, AtomicInteger> validationFailures = new HashMap<>();
        private final Map<String, AtomicInteger> compileFailures = new HashMap<>();
        private final Map<String, AtomicInteger> cacheHits = new HashMap<>();
        private final Map<String, Long> latencyNanos = new HashMap<>();

        void recordAttempt(String agentId) {
            increment(attempts, agentId);
        }

        void recordSuccess(String agentId, long durationNanos) {
            increment(successes, agentId);
            latencyNanos.put(agentId, durationNanos);
        }

        void recordValidationFailure(String agentId) {
            increment(validationFailures, agentId);
        }

        void recordCompileFailure(String agentId) {
            increment(compileFailures, agentId);
        }

        void recordCacheHit(String agentId) {
            increment(cacheHits, agentId);
        }

        int compileAttempts(String agentId) {
            return read(attempts, agentId);
        }

        int successes(String agentId) {
            return read(successes, agentId);
        }

        int validationFailures(String agentId) {
            return read(validationFailures, agentId);
        }

        int compileFailures(String agentId) {
            return read(compileFailures, agentId);
        }

        int cacheHits(String agentId) {
            return read(cacheHits, agentId);
        }

        boolean hasLatency(String agentId) {
            return latencyNanos.containsKey(agentId) && latencyNanos.get(agentId) > 0;
        }

        boolean hasCacheHits(String agentId) {
            return cacheHits.containsKey(agentId) && cacheHits.get(agentId).get() > 0;
        }

        private void increment(Map<String, AtomicInteger> map, String agentId) {
            map.computeIfAbsent(agentId, key -> new AtomicInteger()).incrementAndGet();
        }

        private int read(Map<String, AtomicInteger> map, String agentId) {
            AtomicInteger value = map.get(agentId);
            return value == null ? 0 : value.get();
        }
    }

    private static final class CachedCompilerInvoker implements CompilerInvoker {
        @Override
        public Class<?> compile(String className, String source) throws Exception {
            return CompilerUtils.CACHED_COMPILER.loadFromJava(className, source);
        }
    }
}
