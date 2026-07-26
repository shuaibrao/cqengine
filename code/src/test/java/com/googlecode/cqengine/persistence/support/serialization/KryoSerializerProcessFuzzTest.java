// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static com.googlecode.cqengine.persistence.support.serialization.KryoDeserializationMode.REGISTERED_TYPES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises CQEngine's registered framework serializers in a bounded child JVM. Application-provided serializers
 * remain outside this qualification boundary.
 */
public class KryoSerializerProcessFuzzTest {

    private static final long[] SEEDS = {
            0x4351454E47494E45L,
            0x4B52594F5F46555AL
    };
    private static final int GENERATED_CASES = 256;
    private static final long PROCESS_TIMEOUT_SECONDS = 15;

    @Test
    @Timeout(45)
    public void registeredFrameworkSerializersRemainBoundedForMutatedPayloads() throws Exception {
        for (long seed : SEEDS) {
            runProbe(seed);
        }
    }

    private static void runProbe(long seed) throws Exception {
        Path output = Files.createTempFile("cqengine-kryo-fuzz-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(
                    PathToJava.executable(),
                    "-ea",
                    "-Xms16m",
                    "-Xmx96m",
                    "-Xss512k",
                    "-XX:MaxDirectMemorySize=16m",
                    "-XX:+ExitOnOutOfMemoryError",
                    "-Dfile.encoding=UTF-8",
                    "-Dkryo.unsafe=false",
                    "-cp",
                    System.getProperty("java.class.path"),
                    Probe.class.getName(),
                    Long.toString(seed),
                    Integer.toString(GENERATED_CASES))
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminate(process);
                fail("Kryo fuzz process timed out: " + probeDescription(seed) + "\n" + tail(output));
            }

            String processOutput = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), processOutput);
            assertTrue(
                    processOutput.contains("kryo-fuzz=ok seed="
                            + seed
                            + " generated="
                            + GENERATED_CASES),
                    processOutput);
        }
        finally {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            Files.deleteIfExists(output);
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
        }
    }

    private static String tail(Path output) throws IOException {
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        return String.join(System.lineSeparator(), lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private static String probeDescription(long seed) {
        return "seed=" + seed + " generated=" + GENERATED_CASES;
    }

    public static final class Probe {

        private static final int MAX_SERIALIZED_BYTES = 16_384;
        private static final int MAX_GRAPH_DEPTH = 32;
        private static final int MAX_CONTAINER_ELEMENTS = 16;
        private static final int MAX_STRING_CHARACTERS = 64;
        private static final int MAX_VALIDATED_OBJECTS = MAX_SERIALIZED_BYTES;
        private static final Class<?> IMMUTABLE_LIST_TYPE = List.of("a", "b", "c").getClass();
        private static final Class<?> IMMUTABLE_SET_TYPE = Set.of("a", "b", "c").getClass();
        private static final Class<?> IMMUTABLE_MAP_TYPE = Map.of("a", 1, "b", 2, "c", 3).getClass();

        private Probe() {
        }

        public static void main(String[] arguments) {
            if (arguments.length != 2) {
                throw new IllegalArgumentException("Expected seed and generated-case count");
            }
            long seed = Long.parseLong(arguments[0]);
            int generatedCases = Integer.parseInt(arguments[1]);
            if (generatedCases < 1 || generatedCases > GENERATED_CASES) {
                throw new IllegalArgumentException("Generated-case count is outside the test bound: "
                        + generatedCases);
            }

            KryoSerializer<FuzzGraph> serializer = serializer(MAX_CONTAINER_ELEMENTS);
            verifyKnownOutcomes(serializer, seed);

            Generator generator = new Generator(seed);
            int acceptedMutations = 0;
            int rejectedMutations = 0;
            for (int ordinal = 0; ordinal < generatedCases; ordinal++) {
                Mutation mutation = Mutation.values()[ordinal % Mutation.values().length];
                System.out.println("kryo-fuzz=running seed="
                        + seed
                        + " ordinal="
                        + ordinal
                        + " mutation="
                        + mutation.name().toLowerCase(Locale.ROOT));
                System.out.flush();

                try {
                    FuzzGraph original = generatedGraph(generator, ordinal, 3 + generator.nextInt(8));
                    byte[] validBytes = serializer.serialize(original);
                    FuzzGraph roundTrip = serializer.deserialize(validBytes);
                    assertEquivalent(original, roundTrip);

                    byte[] mutatedBytes = mutate(validBytes, mutation, generator);
                    try {
                        FuzzGraph result = serializer.deserialize(mutatedBytes);
                        validateBoundedGraph(result);
                        acceptedMutations++;
                    }
                    catch (IllegalStateException expected) {
                        rejectedMutations++;
                    }
                }
                catch (Error error) {
                    throw error;
                }
                catch (RuntimeException failure) {
                    throw unexpected(seed, ordinal, mutation, failure);
                }
            }

            System.out.println("kryo-fuzz=ok seed="
                    + seed
                    + " generated="
                    + generatedCases
                    + " accepted-mutations="
                    + acceptedMutations
                    + " rejected-mutations="
                    + rejectedMutations);
        }

        private static void verifyKnownOutcomes(KryoSerializer<FuzzGraph> serializer, long seed) {
            Generator generator = new Generator(seed ^ 0x6A09E667F3BCC909L);
            FuzzGraph representative = generatedGraph(generator, -1, 8);
            byte[] validBytes = serializer.serialize(representative);
            assertEquivalent(representative, serializer.deserialize(validBytes));

            for (byte[] malformed : malformedEnvelopes(validBytes)) {
                expectBoundedFailure(() -> serializer.deserialize(malformed), "malformed secure envelope");
            }

            FuzzGraph oversized = generatedGraph(generator, -2, MAX_CONTAINER_ELEMENTS + 1);
            KryoSerializer<FuzzGraph> relaxedWriter = serializer(MAX_CONTAINER_ELEMENTS + 8);
            byte[] oversizedBytes = relaxedWriter.serialize(oversized);
            expectBoundedFailure(() -> serializer.serialize(oversized), "oversized framework wrapper write");
            expectBoundedFailure(() -> serializer.deserialize(oversizedBytes), "oversized framework wrapper read");
        }

        private static KryoSerializer<FuzzGraph> serializer(int maxContainerElements) {
            return new KryoSerializer<FuzzGraph>(
                    FuzzGraph.class,
                    KryoSerializerSecurityTest.config(
                            REGISTERED_TYPES,
                            true,
                            KryoSerializerSecurityTest.classes(
                                    FuzzNode.class,
                                    IMMUTABLE_LIST_TYPE,
                                    IMMUTABLE_SET_TYPE,
                                    IMMUTABLE_MAP_TYPE),
                            MAX_SERIALIZED_BYTES,
                            MAX_GRAPH_DEPTH,
                            maxContainerElements,
                            MAX_STRING_CHARACTERS));
        }

        private static List<byte[]> malformedEnvelopes(byte[] validBytes) {
            List<byte[]> malformed = new ArrayList<byte[]>();
            malformed.add(new byte[0]);
            malformed.add(Arrays.copyOf(validBytes, KryoSerializer.SECURE_ENVELOPE_HEADER_BYTES - 1));

            byte[] invalidMagic = validBytes.clone();
            invalidMagic[0] ^= 1;
            malformed.add(invalidMagic);

            byte[] invalidVersion = validBytes.clone();
            invalidVersion[4] ^= 1;
            malformed.add(invalidVersion);

            byte[] invalidFlags = validBytes.clone();
            invalidFlags[5] ^= 1;
            malformed.add(invalidFlags);

            byte[] invalidFingerprint = validBytes.clone();
            invalidFingerprint[6] ^= 1;
            malformed.add(invalidFingerprint);

            byte[] negativeLength = validBytes.clone();
            ByteBuffer.wrap(negativeLength).putInt(KryoSerializer.SECURE_ENVELOPE_HEADER_BYTES - 4, -1);
            malformed.add(negativeLength);

            byte[] wrongLength = validBytes.clone();
            ByteBuffer.wrap(wrongLength).putInt(
                    KryoSerializer.SECURE_ENVELOPE_HEADER_BYTES - 4,
                    validBytes.length - KryoSerializer.SECURE_ENVELOPE_HEADER_BYTES + 1);
            malformed.add(wrongLength);

            malformed.add(Arrays.copyOf(validBytes, validBytes.length - 1));
            malformed.add(Arrays.copyOf(validBytes, validBytes.length + 1));
            malformed.add(new byte[MAX_SERIALIZED_BYTES + 1]);
            return malformed;
        }

        private static byte[] mutate(byte[] validBytes, Mutation mutation, Generator generator) {
            switch (mutation) {
                case FLIP_PAYLOAD_BIT:
                    return flipPayloadBit(validBytes, generator);
                case ZERO_PAYLOAD_WINDOW:
                    return overwritePayloadWindow(validBytes, generator, false);
                case RANDOMIZE_PAYLOAD_WINDOW:
                    return overwritePayloadWindow(validBytes, generator, true);
                case REPLACE_PAYLOAD:
                    return replacePayload(validBytes, generator);
                case CORRUPT_HEADER:
                    return corruptHeader(validBytes, generator);
                case TRUNCATE:
                    return Arrays.copyOf(validBytes, generator.nextInt(validBytes.length));
                case APPEND:
                    return append(validBytes, generator);
                case RANDOM_BYTES:
                    return randomBytes(generator, generator.nextInt(MAX_SERIALIZED_BYTES + 1));
                case REWRITE_ROOT_CLASS:
                    return rewriteRootClass(validBytes, generator);
                default:
                    throw new AssertionError(mutation);
            }
        }

        private static byte[] flipPayloadBit(byte[] validBytes, Generator generator) {
            byte[] mutated = validBytes.clone();
            int offset = payloadOffset(validBytes, generator);
            mutated[offset] ^= (byte) (1 << generator.nextInt(8));
            return mutated;
        }

        private static byte[] overwritePayloadWindow(
                byte[] validBytes, Generator generator, boolean randomize) {
            byte[] mutated = validBytes.clone();
            int start = payloadOffset(validBytes, generator);
            int length = 1 + generator.nextInt(Math.min(32, mutated.length - start));
            for (int index = start; index < start + length; index++) {
                mutated[index] = randomize ? (byte) generator.nextInt(256) : 0;
            }
            return mutated;
        }

        private static byte[] replacePayload(byte[] validBytes, Generator generator) {
            byte[] mutated = validBytes.clone();
            for (int index = KryoSerializer.SECURE_ENVELOPE_PAYLOAD_OFFSET;
                    index < mutated.length;
                    index++) {
                mutated[index] = (byte) generator.nextInt(256);
            }
            return mutated;
        }

        private static byte[] corruptHeader(byte[] validBytes, Generator generator) {
            byte[] mutated = validBytes.clone();
            int offset = generator.nextInt(KryoSerializer.SECURE_ENVELOPE_HEADER_BYTES);
            mutated[offset] ^= (byte) (1 + generator.nextInt(255));
            return mutated;
        }

        private static byte[] append(byte[] validBytes, Generator generator) {
            int remaining = MAX_SERIALIZED_BYTES - validBytes.length;
            if (remaining < 1) {
                return Arrays.copyOf(validBytes, validBytes.length - 1);
            }
            byte[] mutated = Arrays.copyOf(validBytes, validBytes.length + 1 + generator.nextInt(remaining));
            for (int index = validBytes.length; index < mutated.length; index++) {
                mutated[index] = (byte) generator.nextInt(256);
            }
            return mutated;
        }

        private static byte[] rewriteRootClass(byte[] validBytes, Generator generator) {
            byte[] mutated = validBytes.clone();
            mutated[KryoSerializer.SECURE_ENVELOPE_PAYLOAD_OFFSET] = (byte) generator.nextInt(256);
            return mutated;
        }

        private static int payloadOffset(byte[] validBytes, Generator generator) {
            int payloadLength = validBytes.length - KryoSerializer.SECURE_ENVELOPE_PAYLOAD_OFFSET;
            if (payloadLength < 1) {
                throw new AssertionError("Valid serialization had no payload");
            }
            return KryoSerializer.SECURE_ENVELOPE_PAYLOAD_OFFSET + generator.nextInt(payloadLength);
        }

        private static byte[] randomBytes(Generator generator, int length) {
            byte[] bytes = new byte[length];
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) generator.nextInt(256);
            }
            return bytes;
        }

        private static FuzzGraph generatedGraph(Generator generator, int ordinal, int size) {
            List<String> values = new ArrayList<String>(size);
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
            for (int index = 0; index < size; index++) {
                String value = "v" + ordinal + "_" + index + "_" + generator.nextInt(1_000_000);
                values.add(value);
                counts.put(value, generator.nextInt(1_000_000));
            }

            FuzzGraph graph = new FuzzGraph();
            graph.identifier = ordinal;
            graph.label = "graph_" + ordinal + "_" + generator.nextInt(1_000_000);
            graph.arrayView = Arrays.asList(values.toArray(new String[0]));
            graph.immutableList = List.copyOf(values);
            graph.immutableSet = Set.copyOf(values);
            graph.immutableMap = Map.copyOf(counts);
            graph.readOnlyCollection = Collections.unmodifiableCollection(new ArrayList<String>(values));
            graph.readOnlyRandomAccessList = Collections.unmodifiableList(new ArrayList<String>(values));
            graph.readOnlyList = Collections.unmodifiableList(new LinkedList<String>(values));
            graph.synchronizedSet = Collections.synchronizedSet(new LinkedHashSet<String>(values));
            graph.readOnlySortedSet = Collections.unmodifiableSortedSet(new TreeSet<String>(values));
            graph.synchronizedMap = Collections.synchronizedMap(new LinkedHashMap<String, Integer>(counts));
            graph.synchronizedSortedMap = Collections.synchronizedSortedMap(new TreeMap<String, Integer>(counts));
            graph.node = FuzzNode.chain(1 + generator.nextInt(8), graph.label);
            return graph;
        }

        private static void assertEquivalent(FuzzGraph expected, FuzzGraph actual) {
            if (actual == null
                    || expected.identifier != actual.identifier
                    || !expected.label.equals(actual.label)
                    || !expected.arrayView.equals(actual.arrayView)
                    || !expected.immutableList.equals(actual.immutableList)
                    || !expected.immutableSet.equals(actual.immutableSet)
                    || !expected.immutableMap.equals(actual.immutableMap)
                    || !new ArrayList<String>(expected.readOnlyCollection)
                            .equals(new ArrayList<String>(actual.readOnlyCollection))
                    || !expected.readOnlyRandomAccessList.equals(actual.readOnlyRandomAccessList)
                    || !expected.readOnlyList.equals(actual.readOnlyList)
                    || !new LinkedHashSet<String>(expected.synchronizedSet)
                            .equals(new LinkedHashSet<String>(actual.synchronizedSet))
                    || !expected.readOnlySortedSet.equals(actual.readOnlySortedSet)
                    || !new LinkedHashMap<String, Integer>(expected.synchronizedMap)
                            .equals(new LinkedHashMap<String, Integer>(actual.synchronizedMap))
                    || !new TreeMap<String, Integer>(expected.synchronizedSortedMap)
                            .equals(new TreeMap<String, Integer>(actual.synchronizedSortedMap))
                    || !FuzzNode.equivalent(expected.node, actual.node)) {
                throw new AssertionError("Valid Kryo graph did not round-trip exactly");
            }
            assertWrapperType(expected.arrayView, actual.arrayView);
            assertWrapperType(expected.immutableList, actual.immutableList);
            assertWrapperType(expected.immutableSet, actual.immutableSet);
            assertWrapperType(expected.immutableMap, actual.immutableMap);
            assertWrapperType(expected.readOnlyCollection, actual.readOnlyCollection);
            assertWrapperType(expected.readOnlyRandomAccessList, actual.readOnlyRandomAccessList);
            assertWrapperType(expected.readOnlyList, actual.readOnlyList);
            assertWrapperType(expected.synchronizedSet, actual.synchronizedSet);
            assertWrapperType(expected.readOnlySortedSet, actual.readOnlySortedSet);
            assertWrapperType(expected.synchronizedMap, actual.synchronizedMap);
            assertWrapperType(expected.synchronizedSortedMap, actual.synchronizedSortedMap);
        }

        private static void assertWrapperType(Object expected, Object actual) {
            if (expected.getClass() != actual.getClass()) {
                throw new AssertionError("Wrapper type changed from "
                        + expected.getClass().getName()
                        + " to "
                        + actual.getClass().getName());
            }
        }

        private static void validateBoundedGraph(FuzzGraph graph) {
            Deque<PendingValue> pending = new ArrayDeque<PendingValue>();
            pending.add(new PendingValue(graph, 1));
            IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<Object, Boolean>();
            while (!pending.isEmpty()) {
                PendingValue next = pending.removeLast();
                Object value = next.value;
                if (value == null
                        || value instanceof Number
                        || value instanceof Boolean
                        || value instanceof Character) {
                    continue;
                }
                if (next.depth > MAX_GRAPH_DEPTH) {
                    throw new AssertionError("Accepted graph exceeded the configured graph depth");
                }
                if (value instanceof String) {
                    if (((String) value).length() > MAX_STRING_CHARACTERS) {
                        throw new AssertionError("Accepted graph exceeded the configured string bound");
                    }
                    continue;
                }
                if (visited.put(value, Boolean.TRUE) != null) {
                    continue;
                }
                if (visited.size() > MAX_VALIDATED_OBJECTS) {
                    throw new AssertionError("Accepted graph exceeded its serialized-byte object budget");
                }

                if (value instanceof FuzzGraph) {
                    FuzzGraph candidate = (FuzzGraph) value;
                    add(pending, candidate.label, next.depth);
                    add(pending, candidate.arrayView, next.depth);
                    add(pending, candidate.immutableList, next.depth);
                    add(pending, candidate.immutableSet, next.depth);
                    add(pending, candidate.immutableMap, next.depth);
                    add(pending, candidate.readOnlyCollection, next.depth);
                    add(pending, candidate.readOnlyRandomAccessList, next.depth);
                    add(pending, candidate.readOnlyList, next.depth);
                    add(pending, candidate.synchronizedSet, next.depth);
                    add(pending, candidate.readOnlySortedSet, next.depth);
                    add(pending, candidate.synchronizedMap, next.depth);
                    add(pending, candidate.synchronizedSortedMap, next.depth);
                    add(pending, candidate.node, next.depth);
                }
                else if (value instanceof FuzzNode) {
                    FuzzNode node = (FuzzNode) value;
                    add(pending, node.value, next.depth);
                    add(pending, node.next, next.depth);
                }
                else if (value instanceof Collection) {
                    Collection<?> collection = (Collection<?>) value;
                    requireContainerBound(collection.size());
                    for (Object element : collection) {
                        add(pending, element, next.depth);
                    }
                }
                else if (value instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) value;
                    requireContainerBound(map.size());
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        add(pending, entry.getKey(), next.depth);
                        add(pending, entry.getValue(), next.depth);
                    }
                }
                else if (value instanceof Object[]) {
                    Object[] array = (Object[]) value;
                    requireContainerBound(array.length);
                    for (Object element : array) {
                        add(pending, element, next.depth);
                    }
                }
                else {
                    throw new AssertionError("Accepted graph contained an unexpected registered type: "
                            + value.getClass().getName());
                }
            }
        }

        private static void add(Deque<PendingValue> pending, Object value, int parentDepth) {
            pending.add(new PendingValue(value, parentDepth + 1));
        }

        private static void requireContainerBound(int size) {
            if (size < 0 || size > MAX_CONTAINER_ELEMENTS) {
                throw new AssertionError("Accepted graph exceeded the configured container bound: " + size);
            }
        }

        private static void expectBoundedFailure(Runnable operation, String description) {
            try {
                operation.run();
            }
            catch (IllegalStateException expected) {
                return;
            }
            catch (Throwable failure) {
                throw new AssertionError(description + " failed with an undocumented exception type", failure);
            }
            throw new AssertionError(description + " was accepted");
        }

        private static AssertionError unexpected(
                long seed, int ordinal, Mutation mutation, RuntimeException failure) {
            return new AssertionError(
                    "Unexpected Kryo fuzz outcome: seed="
                            + seed
                            + " ordinal="
                            + ordinal
                            + " mutation="
                            + mutation.name().toLowerCase(Locale.ROOT),
                    failure);
        }
    }

    private enum Mutation {
        FLIP_PAYLOAD_BIT,
        ZERO_PAYLOAD_WINDOW,
        RANDOMIZE_PAYLOAD_WINDOW,
        REPLACE_PAYLOAD,
        CORRUPT_HEADER,
        TRUNCATE,
        APPEND,
        RANDOM_BYTES,
        REWRITE_ROOT_CLASS
    }

    static final class FuzzGraph {
        int identifier;
        String label;
        List<String> arrayView;
        List<String> immutableList;
        Set<String> immutableSet;
        Map<String, Integer> immutableMap;
        Collection<String> readOnlyCollection;
        List<String> readOnlyRandomAccessList;
        List<String> readOnlyList;
        Set<String> synchronizedSet;
        SortedSet<String> readOnlySortedSet;
        Map<String, Integer> synchronizedMap;
        SortedMap<String, Integer> synchronizedSortedMap;
        FuzzNode node;
    }

    static final class FuzzNode {
        String value;
        FuzzNode next;

        static FuzzNode chain(int depth, String prefix) {
            FuzzNode root = new FuzzNode();
            FuzzNode current = root;
            for (int index = 0; index < depth; index++) {
                current.value = prefix + "_node_" + index;
                if (index + 1 < depth) {
                    current.next = new FuzzNode();
                    current = current.next;
                }
            }
            return root;
        }

        static boolean equivalent(FuzzNode expected, FuzzNode actual) {
            FuzzNode expectedCurrent = expected;
            FuzzNode actualCurrent = actual;
            int depth = 0;
            while (expectedCurrent != null && actualCurrent != null && depth <= Probe.MAX_GRAPH_DEPTH) {
                if (!expectedCurrent.value.equals(actualCurrent.value)) {
                    return false;
                }
                expectedCurrent = expectedCurrent.next;
                actualCurrent = actualCurrent.next;
                depth++;
            }
            return expectedCurrent == null && actualCurrent == null;
        }
    }

    private static final class PendingValue {
        final Object value;
        final int depth;

        private PendingValue(Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }

    private static final class Generator {
        private long state;

        private Generator(long seed) {
            state = seed;
        }

        private long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            long value = state;
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            return value ^ (value >>> 31);
        }

        private int nextInt(int bound) {
            if (bound < 1) {
                throw new IllegalArgumentException("bound must be positive");
            }
            return (int) ((nextLong() >>> 1) % bound);
        }
    }

    private static final class PathToJava {
        private PathToJava() {
        }

        static String executable() {
            String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            return new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath();
        }
    }
}
