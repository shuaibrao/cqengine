// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.googlecode.cqengine.persistence.support.serialization.KryoDeserializationMode.REGISTERED_TYPES;
import static com.googlecode.cqengine.persistence.support.serialization.KryoDeserializationMode.TRUSTED_STORE_COMPATIBILITY;
import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static com.googlecode.cqengine.testutil.TestAssertions.assertThrows;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;
import static com.googlecode.cqengine.testutil.TestAssertions.fail;

public class KryoSerializerSecurityTest {

    @Test
    public void trustedCompatibilityModeStillReadsLegacyRawFixture() throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(KryoSerializerCompatibilityTest.readResource(
                "/com/googlecode/cqengine/persistence/support/serialization/"
                        + "kryo-5.0.0-rc1-java21-wrapper-fixture.b64"));

        KryoSerializerCompatibilityFixture fixture = new KryoSerializer<KryoSerializerCompatibilityFixture>(
                KryoSerializerCompatibilityFixture.class, PersistenceConfig.DEFAULT_CONFIG).deserialize(bytes);

        KryoSerializerCompatibilityFixtureTestSupport.assertFixture(fixture);
    }

    @Test
    public void registeredTypesModeRejectsLegacyRawFixture() throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(KryoSerializerCompatibilityTest.readResource(
                "/com/googlecode/cqengine/persistence/support/serialization/"
                        + "kryo-5.0.0-rc1-java21-wrapper-fixture.b64"));
        KryoSerializer<KryoSerializerCompatibilityFixture> serializer = new KryoSerializer<
                KryoSerializerCompatibilityFixture>(
                KryoSerializerCompatibilityFixture.class, secureConfig(false));

        assertFailureContains(() -> serializer.deserialize(bytes), "unframed Kryo bytes");
    }

    @Test
    public void rejectsOversizedInputBeforeKryoReadsIt() {
        KryoSerializer<String> serializer = new KryoSerializer<String>(
                String.class, config(TRUSTED_STORE_COMPATIBILITY, false, classes(), 16, 100, 100, 100));

        assertFailureContains(() -> serializer.deserialize(new byte[17]), "input size 17");
    }

    @Test
    public void rejectsOversizedOutput() {
        KryoSerializer<String> serializer = new KryoSerializer<String>(
                String.class, config(TRUSTED_STORE_COMPATIBILITY, false, classes(), 16, 100, 100, 100));

        assertFailureContains(() -> serializer.serialize("this value is longer than the configured output"),
                "maximum serialized bytes: 16");
    }

    @Test
    public void rejectsTruncatedAndTrailingRawInput() {
        KryoSerializer<SecurityPojo> serializer = new KryoSerializer<SecurityPojo>(
                SecurityPojo.class, PersistenceConfig.DEFAULT_CONFIG);
        byte[] bytes = serializer.serialize(new SecurityPojo(7));

        assertFailureContains(() -> serializer.deserialize(Arrays.copyOf(bytes, bytes.length - 1)),
                "Buffer underflow");
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertFailureContains(() -> serializer.deserialize(trailing), "Trailing bytes");
    }

    @Test
    public void rejectsCorruptTruncatedAndTrailingSecureEnvelope() {
        KryoSerializer<SecurityPojo> serializer = new KryoSerializer<SecurityPojo>(
                SecurityPojo.class, secureConfig(false));
        byte[] bytes = serializer.serialize(new SecurityPojo(7));

        byte[] corruptVersion = bytes.clone();
        corruptVersion[4]++;
        assertFailureContains(() -> serializer.deserialize(corruptVersion), "envelope version");

        byte[] corruptFingerprint = bytes.clone();
        corruptFingerprint[6] ^= 1;
        assertFailureContains(() -> serializer.deserialize(corruptFingerprint), "fingerprint");

        assertFailureContains(() -> serializer.deserialize(Arrays.copyOf(bytes, bytes.length - 1)),
                "payload length");
        assertFailureContains(() -> serializer.deserialize(Arrays.copyOf(bytes, bytes.length + 1)),
                "payload length");
        assertEquals(7, serializer.deserialize(bytes).value);
    }

    @Test
    public void enforcesGraphDepthOnWriteAndRead() {
        Node graph = Node.chain(8);
        KryoSerializer<Node> writer = new KryoSerializer<Node>(
                Node.class, config(REGISTERED_TYPES, false, classes(), 4096, 100, 100, 100));
        KryoSerializer<Node> shallowReader = new KryoSerializer<Node>(
                Node.class, config(REGISTERED_TYPES, false, classes(), 4096, 3, 100, 100));
        byte[] bytes = writer.serialize(graph);

        assertFailureContains(() -> shallowReader.deserialize(bytes), "Max depth exceeded");

        KryoSerializer<Node> shallowWriter = new KryoSerializer<Node>(
                Node.class, config(REGISTERED_TYPES, false, classes(), 4096, 3, 100, 100));
        assertFailureContains(() -> shallowWriter.serialize(graph), "Max depth exceeded");
    }

    @Test
    public void registeredPolymorphismAllowsOnlyConfiguredTypes() {
        PersistenceConfig allowedDog = config(
                REGISTERED_TYPES, true, classes(Dog.class), 4096, 100, 100, 100);
        KryoSerializer<Animal> serializer = new KryoSerializer<Animal>(Animal.class, allowedDog);

        Animal dog = serializer.deserialize(serializer.serialize(new Dog("Fido")));
        assertEquals(new Dog("Fido"), dog);
        assertFailureContains(() -> serializer.serialize(new Cat("Mog")), "Class is not registered");
        assertEquals(new Dog("Fido"), serializer.deserialize(serializer.serialize(new Dog("Fido"))));
    }

    @Test
    public void registeredTypesModeSupportsBoundedStandardContainersAndWrappers() {
        SecureContainerPojo original = new SecureContainerPojo();
        original.values = new ArrayList<String>(Arrays.asList("a", "b"));
        original.counts = new LinkedHashMap<String, Integer>();
        original.counts.put("a", 1);
        original.readOnly = Collections.unmodifiableList(new ArrayList<String>(original.values));
        KryoSerializer<SecureContainerPojo> serializer = new KryoSerializer<SecureContainerPojo>(
                SecureContainerPojo.class, secureConfig(false));

        SecureContainerPojo result = serializer.deserialize(serializer.serialize(original));

        assertEquals(original.values, result.values);
        assertEquals(original.counts, result.counts);
        assertEquals(original.readOnly, result.readOnly);
        assertThrows(UnsupportedOperationException.class, () -> result.readOnly.add("rejected"));
    }

    @Test
    public void persistenceAnnotationSelectsRegisteredTypesMode() {
        PersistenceConfig config = AnnotatedSecurityPojo.class.getAnnotation(PersistenceConfig.class);
        KryoSerializer<AnnotatedSecurityPojo> serializer = new KryoSerializer<AnnotatedSecurityPojo>(
                AnnotatedSecurityPojo.class, config);

        AnnotatedSecurityPojo result = serializer.deserialize(
                serializer.serialize(new AnnotatedSecurityPojo("value")));

        assertEquals("value", result.value);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void rejectsOversizedOwnedCollectionWrapper() {
        Class<List<String>> listType = (Class<List<String>>) (Class<?>) List.class;
        KryoSerializer<List<String>> serializer = new KryoSerializer<List<String>>(listType,
                config(REGISTERED_TYPES, true, classes(), 4096, 100, 2, 100));

        assertFailureContains(() -> serializer.serialize(Arrays.asList("a", "b", "c")),
                "Container element count 3");
    }

    @Test
    public void rejectsOversizedJdkImmutableContainersOnWriteAndRead() {
        assertContainerLimit(List.of("a", "b", "c"));
        assertContainerLimit(Set.of("a", "b", "c"));
        assertContainerLimit(Map.of("a", 1, "b", 2, "c", 3));
    }

    @Test
    public void rejectsOversizedJdkUnmodifiableContainersOnWriteAndRead() {
        assertContainerLimit(Collections.unmodifiableList(
                new ArrayList<String>(List.of("a", "b", "c"))));
        assertContainerLimit(Collections.unmodifiableSet(
                new LinkedHashSet<String>(List.of("a", "b", "c"))));
        LinkedHashMap<String, Integer> values = new LinkedHashMap<String, Integer>();
        values.put("a", 1);
        values.put("b", 2);
        values.put("c", 3);
        assertContainerLimit(Collections.unmodifiableMap(values));
    }

    @Test
    public void rejectsUnregisteredAndWrongRootClassIdsBeforeInstantiation() {
        PersistenceConfig config = config(
                REGISTERED_TYPES, true, classes(Dog.class, SideEffectValue.class), 4096, 100, 100, 100);
        KryoSerializer<Animal> serializer = new KryoSerializer<Animal>(Animal.class, config);
        byte[] valid = serializer.serialize(new Dog("Fido"));

        byte[] unknownClassId = valid.clone();
        unknownClassId[KryoSerializer.SECURE_ENVELOPE_PAYLOAD_OFFSET] = 0x7f;
        assertFailureContains(() -> serializer.deserialize(unknownClassId), "unregistered class ID");

        int unrelatedClassId = serializer.kryoCache.get().getRegistration(SideEffectValue.class).getId();
        assertTrue("Test requires a one-byte class ID", unrelatedClassId + 2 < 128);
        byte[] wrongRoot = valid.clone();
        wrongRoot[KryoSerializer.SECURE_ENVELOPE_PAYLOAD_OFFSET] = (byte) (unrelatedClassId + 2);
        SideEffectValue.constructorCalls = 0;
        assertFailureContains(() -> serializer.deserialize(wrongRoot), "is not assignable");
        assertEquals(0, SideEffectValue.constructorCalls);
    }

    @Test
    public void registrationOrderIsDeterministic() {
        PersistenceConfig first = config(
                REGISTERED_TYPES, true, classes(Dog.class, Cat.class), 4096, 100, 100, 100);
        PersistenceConfig reversed = config(
                REGISTERED_TYPES, true, classes(Cat.class, Dog.class), 4096, 100, 100, 100);

        byte[] firstBytes = new KryoSerializer<Animal>(Animal.class, first).serialize(new Dog("Fido"));
        byte[] reversedBytes = new KryoSerializer<Animal>(Animal.class, reversed).serialize(new Dog("Fido"));

        assertArrayEquals(firstBytes, reversedBytes);
    }

    @Test
    public void registrationFingerprintRejectsDifferentAllowlist() {
        KryoSerializer<Animal> dogWriter = new KryoSerializer<Animal>(Animal.class,
                config(REGISTERED_TYPES, true, classes(Dog.class), 4096, 100, 100, 100));
        KryoSerializer<Animal> dogAndCatReader = new KryoSerializer<Animal>(Animal.class,
                config(REGISTERED_TYPES, true, classes(Dog.class, Cat.class), 4096, 100, 100, 100));
        byte[] bytes = dogWriter.serialize(new Dog("Fido"));

        assertFailureContains(() -> dogAndCatReader.deserialize(bytes), "fingerprint");
    }

    @Test
    public void rejectsOversizedArrayLengthBeforeAllocation() {
        KryoSerializer<int[]> writer = new KryoSerializer<int[]>(int[].class,
                config(REGISTERED_TYPES, false, classes(), 4096, 100, 100, 100));
        KryoSerializer<int[]> boundedReader = new KryoSerializer<int[]>(int[].class,
                config(REGISTERED_TYPES, false, classes(), 4096, 100, 4, 100));
        byte[] valid = writer.serialize(new int[] { 1 });
        byte[] bytes = valid.clone();

        int lengthOffset = KryoSerializer.SECURE_ENVELOPE_PAYLOAD_OFFSET + 1;
        bytes[lengthOffset] = 10;

        assertFailureContains(() -> boundedReader.deserialize(bytes), "Container element count 9");
        assertArrayEquals(new int[] { 1 }, boundedReader.deserialize(valid));
    }

    @Test
    public void rejectsOversizedStringLengthBeforeAllocation() {
        KryoSerializer<String> serializer = new KryoSerializer<String>(String.class,
                config(TRUSTED_STORE_COMPATIBILITY, false, classes(), 4096, 100, 100, 4));
        byte[] hostileLength;
        try (Output output = new Output(16, 16)) {
            output.writeByte(Kryo.NOT_NULL);
            output.writeVarIntFlag(true, 100, true);
            hostileLength = output.toBytes();
        }

        assertFailureContains(() -> serializer.deserialize(hostileLength), "String character count 99");
    }

    @Test
    public void rejectsOversizedAsciiStringBeforeBufferGrowth() {
        KryoSerializer<String> serializer = new KryoSerializer<String>(String.class,
                config(TRUSTED_STORE_COMPATIBILITY, false, classes(), 4096, 100, 100, 4));
        byte[] hostileAscii = {
                Kryo.NOT_NULL, 'a', 'a', 'a', 'a', (byte) ('a' | 0x80)
        };

        assertFailureContains(() -> serializer.deserialize(hostileAscii),
                "String character count exceeds configured maximum 4");
    }

    @Test
    public void fatalSerializerErrorsRetainTypeAndIdentity() {
        AssertionError writeFailure = new AssertionError("fatal write");
        KryoSerializer<SecurityPojo> failingWriter = serializerWith(new Serializer<SecurityPojo>() {
            @Override
            public void write(Kryo kryo, Output output, SecurityPojo value) {
                throw writeFailure;
            }

            @Override
            public SecurityPojo read(Kryo kryo, Input input, Class<? extends SecurityPojo> type) {
                return new SecurityPojo();
            }
        });
        assertSame(writeFailure, assertThrows(
                AssertionError.class,
                () -> failingWriter.serialize(new SecurityPojo(7))));

        AssertionError readFailure = new AssertionError("fatal read");
        KryoSerializer<SecurityPojo> failingReader = serializerWith(new Serializer<SecurityPojo>() {
            @Override
            public void write(Kryo kryo, Output output, SecurityPojo value) {
            }

            @Override
            public SecurityPojo read(Kryo kryo, Input input, Class<? extends SecurityPojo> type) {
                throw readFailure;
            }
        });
        byte[] bytes = failingReader.serialize(new SecurityPojo(7));
        assertSame(readFailure, assertThrows(AssertionError.class, () -> failingReader.deserialize(bytes)));
    }

    static KryoSerializer<SecurityPojo> serializerWith(final Serializer<SecurityPojo> serializer) {
        return new KryoSerializer<SecurityPojo>(SecurityPojo.class, PersistenceConfig.DEFAULT_CONFIG) {
            @Override
            protected Kryo createKryo(Class<?> objectType) {
                Kryo kryo = super.createKryo(objectType);
                kryo.getRegistration(SecurityPojo.class).setSerializer(serializer);
                return kryo;
            }
        };
    }

    @Test
    public void validatesConfigurationBounds() {
        try {
            new KryoSerializer<SecurityPojo>(SecurityPojo.class,
                    config(REGISTERED_TYPES, false, classes(), 1, 100, 100, 100));
            fail("Expected invalid secure envelope bound");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("secure envelope size"));
        }

        Runnable lambda = () -> { };
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            KryoSerializer<?> ignored = new KryoSerializer(
                    lambda.getClass(), secureConfig(false));
            fail("Expected unstable root type to be rejected: " + ignored);
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("stable binary identity"));
        }
    }

    static PersistenceConfig secureConfig(boolean polymorphic, Class<?>... allowedTypes) {
        return config(REGISTERED_TYPES, polymorphic, allowedTypes, 4096, 100, 100, 100);
    }

    static PersistenceConfig config(
            KryoDeserializationMode mode,
            boolean polymorphic,
            Class<?>[] allowedTypes,
            int maxBytes,
            int maxDepth,
            int maxContainerElements,
            int maxStringCharacters) {
        return new TestPersistenceConfig(
                mode, polymorphic, allowedTypes, maxBytes, maxDepth, maxContainerElements, maxStringCharacters);
    }

    static Class<?>[] classes(Class<?>... classes) {
        return classes;
    }

    @SuppressWarnings("unchecked")
    private static <T> void assertContainerLimit(T value) {
        Class<T> type = (Class<T>) value.getClass();
        KryoSerializer<T> writer = new KryoSerializer<T>(type,
                config(REGISTERED_TYPES, false, classes(), 4096, 100, 100, 100));
        KryoSerializer<T> bounded = new KryoSerializer<T>(type,
                config(REGISTERED_TYPES, false, classes(), 4096, 100, 2, 100));
        byte[] bytes = writer.serialize(value);

        assertEquals(value, writer.deserialize(bytes));
        assertFailureContains(() -> bounded.serialize(value), "Container element count 3");
        assertFailureContains(() -> bounded.deserialize(bytes), "Container element count 3");
    }

    static void assertFailureContains(ThrowingRunnable runnable, String expectedText) {
        try {
            runnable.run();
            fail("Expected operation to fail with: " + expectedText);
        }
        catch (Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (String.valueOf(current.getMessage()).contains(expectedText)) {
                    return;
                }
                current = current.getCause();
            }
            throw new AssertionError("Failure did not contain '" + expectedText + "'", failure);
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static final class TestPersistenceConfig implements PersistenceConfig {
        final KryoDeserializationMode mode;
        final boolean polymorphic;
        final Class<?>[] allowedTypes;
        final int maxBytes;
        final int maxDepth;
        final int maxContainerElements;
        final int maxStringCharacters;

        TestPersistenceConfig(
                KryoDeserializationMode mode,
                boolean polymorphic,
                Class<?>[] allowedTypes,
                int maxBytes,
                int maxDepth,
                int maxContainerElements,
                int maxStringCharacters) {
            this.mode = mode;
            this.polymorphic = polymorphic;
            this.allowedTypes = allowedTypes;
            this.maxBytes = maxBytes;
            this.maxDepth = maxDepth;
            this.maxContainerElements = maxContainerElements;
            this.maxStringCharacters = maxStringCharacters;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return PersistenceConfig.class;
        }

        @Override
        @SuppressWarnings("rawtypes") // Implements PersistenceConfig's intentional legacy serializer signature.
        public Class<? extends PojoSerializer> serializer() {
            return KryoSerializer.class;
        }

        @Override
        public boolean polymorphic() {
            return polymorphic;
        }

        @Override
        public KryoDeserializationMode deserializationMode() {
            return mode;
        }

        @Override
        public Class<?>[] allowedTypes() {
            return allowedTypes.clone();
        }

        @Override
        public int maxSerializedBytes() {
            return maxBytes;
        }

        @Override
        public int maxGraphDepth() {
            return maxDepth;
        }

        @Override
        public int maxContainerElements() {
            return maxContainerElements;
        }

        @Override
        public int maxStringCharacters() {
            return maxStringCharacters;
        }
    }

    static class SecurityPojo {
        int value;

        SecurityPojo() {
        }

        SecurityPojo(int value) {
            this.value = value;
        }
    }

    static class SecureContainerPojo {
        List<String> values;
        Map<String, Integer> counts;
        List<String> readOnly;
    }

    @PersistenceConfig(
            deserializationMode = REGISTERED_TYPES,
            maxSerializedBytes = 4096,
            maxContainerElements = 100,
            maxStringCharacters = 100)
    static class AnnotatedSecurityPojo {
        String value;

        AnnotatedSecurityPojo() {
        }

        AnnotatedSecurityPojo(String value) {
            this.value = value;
        }
    }

    static class Node {
        Node next;

        static Node chain(int depth) {
            Node root = new Node();
            Node current = root;
            for (int i = 1; i < depth; i++) {
                current.next = new Node();
                current = current.next;
            }
            return root;
        }
    }

    abstract static class Animal {
        String name;

        Animal() {
        }

        Animal(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object object) {
            return object != null && object.getClass() == getClass()
                    && name.equals(((Animal) object).name);
        }

        @Override
        public int hashCode() {
            return 31 * getClass().hashCode() + name.hashCode();
        }
    }

    static class Dog extends Animal {
        Dog() {
        }

        Dog(String name) {
            super(name);
        }
    }

    static class Cat extends Animal {
        Cat() {
        }

        Cat(String name) {
            super(name);
        }
    }

    static class SideEffectValue {
        static int constructorCalls;

        SideEffectValue() {
            constructorCalls++;
        }
    }
}
