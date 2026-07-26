/*
 * Modified by Shuaib Rao in 2026.
 */

package com.googlecode.cqengine.persistence.support.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CollectionSerializer;
import com.esotericsoftware.kryo.serializers.DefaultSerializers.TreeMapSerializer;
import com.esotericsoftware.kryo.serializers.DefaultSerializers.TreeSetSerializer;
import com.esotericsoftware.kryo.serializers.ImmutableCollectionsSerializers.JdkImmutableListSerializer;
import com.esotericsoftware.kryo.serializers.ImmutableCollectionsSerializers.JdkImmutableMapSerializer;
import com.esotericsoftware.kryo.serializers.ImmutableCollectionsSerializers.JdkImmutableSetSerializer;
import com.esotericsoftware.kryo.serializers.MapSerializer;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.esotericsoftware.kryo.util.Util;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Uses <a href="https://github.com/EsotericSoftware/kryo">Kryo</a> to serialize and deserialize objects;
 * for use with CQEngine's disk and off-heap indexes and persistence.
 * <p>
 * A {@link #validateObjectIsRoundTripSerializable(Object)} method is also provided, to validate
 * the compatibility of end-user POJOs with this serializer.
 *
 * @author npgall
 */
public class KryoSerializer<O> implements PojoSerializer<O> {

    private static final String KRYO_UNSAFE_PROPERTY = "kryo.unsafe";
    private static final int SECURE_ENVELOPE_MAGIC = 0x43514B59;
    private static final byte SECURE_ENVELOPE_VERSION = 1;
    private static final int REGISTRY_FINGERPRINT_BYTES = 32;
    static final int SECURE_ENVELOPE_HEADER_BYTES = 4 + 1 + 1 + REGISTRY_FINGERPRINT_BYTES + 4;
    static final int SECURE_ENVELOPE_PAYLOAD_OFFSET = SECURE_ENVELOPE_HEADER_BYTES;
    static final int KRYO_POOL_CAPACITY = Math.max(
            2, Math.min(16, Runtime.getRuntime().availableProcessors()));

    private static final Class<?>[] SECURE_FRAMEWORK_TYPES = {
            Object.class,
            byte[].class,
            char[].class,
            short[].class,
            int[].class,
            long[].class,
            float[].class,
            double[].class,
            boolean[].class,
            String[].class,
            Object[].class,
            ArrayList.class,
            LinkedList.class,
            HashSet.class,
            LinkedHashSet.class,
            TreeSet.class,
            HashMap.class,
            LinkedHashMap.class,
            TreeMap.class
    };

    private static volatile boolean kryoSafeRuntimeInitialized;

    protected final Class<O> objectType;
    protected final boolean polymorphic;
    /** Retained as the platform-thread cache and protected extension point; virtual threads use the bounded pool. */
    protected final ThreadLocal<Kryo> kryoCache;
    private final KryoPool kryoPool;
    private final KryoDeserializationMode deserializationMode;
    private final List<Class<?>> allowedTypes;
    private final int maxSerializedBytes;
    private final int maxGraphDepth;
    private final int maxContainerElements;
    private final int maxStringCharacters;
    private final byte[] registryFingerprint;

    /**
     * Creates a new Kryo serializer which is configured to serialize objects of the given type.
     *
     * @param objectType The type of the object
     * @param persistenceConfig Configuration for the serializer, in particular the polymorphic parameter which
     *                          if true, causes Kryo to persist the name of the class with every object, to allow
     *                          the collection to contain a mix of object types within an inheritance hierarchy;
     *                          if false causes Kryo to skip persisting the name of the class and to assume all objects
     *                          in the collection will be instances of the same class.
     *
     */
    public KryoSerializer(Class<O> objectType, PersistenceConfig persistenceConfig) {
        if (objectType == null) {
            throw new NullPointerException("Object type was null");
        }
        if (persistenceConfig == null) {
            throw new NullPointerException("Persistence config was null");
        }
        this.objectType = objectType;
        this.polymorphic = persistenceConfig.polymorphic();
        this.deserializationMode = requireMode(persistenceConfig.deserializationMode());
        this.allowedTypes = normalizeAllowedTypes(objectType, persistenceConfig.allowedTypes());
        this.maxSerializedBytes = requirePositive(
                persistenceConfig.maxSerializedBytes(), "maxSerializedBytes");
        this.maxGraphDepth = requirePositive(persistenceConfig.maxGraphDepth(), "maxGraphDepth");
        this.maxContainerElements = requirePositive(
                persistenceConfig.maxContainerElements(), "maxContainerElements");
        this.maxStringCharacters = requirePositive(
                persistenceConfig.maxStringCharacters(), "maxStringCharacters");
        if (deserializationMode == KryoDeserializationMode.REGISTERED_TYPES
                && maxSerializedBytes <= SECURE_ENVELOPE_HEADER_BYTES) {
            throw new IllegalArgumentException("maxSerializedBytes must exceed secure envelope size "
                    + SECURE_ENVELOPE_HEADER_BYTES);
        }
        if (deserializationMode == KryoDeserializationMode.REGISTERED_TYPES) {
            requireStableType(objectType, "Root type");
        }
        this.registryFingerprint = deserializationMode == KryoDeserializationMode.REGISTERED_TYPES
                ? createRegistryFingerprint(objectType, polymorphic, allowedTypes)
                : null;
        this.kryoCache = new ThreadLocal<Kryo>() {
            @Override
            protected Kryo initialValue() {
                return createKryo(KryoSerializer.this.objectType);
            }
        };
        this.kryoPool = new KryoPool();
    }

    /**
     * Creates a new instance of Kryo serializer, for use with the given object type.
     * <p>
     * Note: this method is public to allow end-users to validate compatibility of their POJOs,
     * with the Kryo serializer as used by CQEngine.
     *
     * @param objectType The type of object which the instance of Kryo will serialize
     * @return a new instance of Kryo serializer
     */
    @SuppressWarnings({"ArraysAsListWithZeroOrOneArgument", "WeakerAccess"})
    protected Kryo createKryo(Class<?> objectType) {
        initializeKryoSafeRuntime();
        Kryo kryo = new Kryo();
        // Kryo 5 changed the default from enabled to disabled. This is part of CQEngine's persisted format.
        kryo.setReferences(true);
        kryo.setOptimizedGenerics(true);
        kryo.setMaxDepth(maxGraphDepth);
        // Instantiate serialized objects via a no-arg constructor when possible, falling back to Objenesis...
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        if (deserializationMode == KryoDeserializationMode.TRUSTED_STORE_COMPATIBILITY) {
            // Preserve the historical registration order and class-name fallback used by existing stores.
            kryo.register(objectType);
            kryo.setRegistrationRequired(false);
            JdkCollectionWrapperSerializers.registerWith(kryo, maxContainerElements);
        }
        else {
            registerSecureType(kryo, objectType);
            JdkCollectionWrapperSerializers.registerWith(kryo, maxContainerElements);
            for (Class<?> frameworkType : SECURE_FRAMEWORK_TYPES) {
                registerSecureType(kryo, frameworkType);
            }
            for (Class<?> allowedType : allowedTypes) {
                registerSecureType(kryo, allowedType);
            }
            kryo.setRegistrationRequired(true);
        }
        return kryo;
    }

    private static void initializeKryoSafeRuntime() {
        if (kryoSafeRuntimeInitialized) {
            return;
        }
        synchronized (KryoSerializer.class) {
            if (kryoSafeRuntimeInitialized) {
                return;
            }
            String configuredValue = System.getProperty(KRYO_UNSAFE_PROPERTY);
            if (configuredValue != null && !"false".equals(configuredValue)) {
                throw new IllegalStateException(
                        "CQEngine persistence requires -D" + KRYO_UNSAFE_PROPERTY + "=false");
            }

            boolean restoreProperty = configuredValue == null;
            try {
                if (restoreProperty) {
                    System.setProperty(KRYO_UNSAFE_PROPERTY, "false");
                }
                Class.forName(Util.class.getName(), true, Util.class.getClassLoader());
            }
            catch (ClassNotFoundException e) {
                throw new IllegalStateException("Kryo runtime is incomplete", e);
            }
            finally {
                if (restoreProperty) {
                    System.clearProperty(KRYO_UNSAFE_PROPERTY);
                }
            }
            if (Util.isUnsafeAvailable()) {
                throw new IllegalStateException(
                        "Kryo Unsafe was initialized before CQEngine; start with -D"
                                + KRYO_UNSAFE_PROPERTY + "=false");
            }
            kryoSafeRuntimeInitialized = true;
        }
    }

    /**
     * Serializes the given object, using the given instance of Kryo serializer.
     *
     * @param object The object to serialize
     * @return The serialized form of the object as a byte array
     */
    @Override
    public byte[] serialize(O object) {
        if (object == null) {
            throw new NullPointerException("Object was null");
        }
        if (deserializationMode == KryoDeserializationMode.REGISTERED_TYPES
                && !objectType.isInstance(object)) {
            throw new IllegalArgumentException("Object type " + object.getClass().getName()
                    + " is not assignable to " + objectType.getName());
        }
        try {
            int payloadLimit = deserializationMode == KryoDeserializationMode.REGISTERED_TYPES
                    ? maxSerializedBytes - SECURE_ENVELOPE_HEADER_BYTES
                    : maxSerializedBytes;
            byte[] payload;
            boolean pooled = Thread.currentThread().isVirtual();
            Kryo kryo = pooled ? kryoPool.acquire() : kryoCache.get();
            try {
                try (Output output = new Output(Math.min(4096, payloadLimit), payloadLimit)) {
                    if (polymorphic) {
                        kryo.writeClassAndObject(output, object);
                    }
                    else {
                        kryo.writeObject(output, object);
                    }
                    payload = output.toBytes();
                }
            }
            finally {
                if (pooled) {
                    kryoPool.release(kryo);
                }
            }
            if (deserializationMode == KryoDeserializationMode.REGISTERED_TYPES) {
                return addSecureEnvelope(payload);
            }
            return payload;
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to serialize object, object type: " + objectType + ". " +
                    "Configured maximum serialized bytes: " + maxSerializedBytes + ". " +
                    "Configure @PersistenceConfig.polymorphic if the collection will contain a mix of object types. " +
                    "Use the KryoSerializer.validateObjectIsRoundTripSerializable() method " +
                    "to test your object is compatible with CQEngine.", e);
        }
    }

    /**
     * Deserializes the given bytes, into an object of the given type, using the given instance of Kryo serializer.
     *
     * @param bytes The serialized form of the object as a byte array
     * @return The deserialized object
     */
    @Override
    @SuppressWarnings("unchecked")
    public O deserialize(byte[] bytes) {
        try {
            if (bytes == null) {
                throw new NullPointerException("Serialized bytes were null");
            }
            if (bytes.length > maxSerializedBytes) {
                throw new KryoException("Serialized input size " + bytes.length
                        + " exceeds configured maximum " + maxSerializedBytes);
            }
            PayloadRange payloadRange = deserializationMode == KryoDeserializationMode.REGISTERED_TYPES
                    ? readSecureEnvelope(bytes)
                    : new PayloadRange(0, bytes.length);
            boolean pooled = Thread.currentThread().isVirtual();
            Kryo kryo = pooled ? kryoPool.acquire() : kryoCache.get();
            O object;
            try {
                try (Input input = new BoundedInput(
                        bytes, payloadRange.offset, payloadRange.length, maxStringCharacters)) {
                    if (polymorphic) {
                        if (deserializationMode == KryoDeserializationMode.REGISTERED_TYPES) {
                            requireAssignableRootRegistration(kryo, input);
                        }
                        object = (O) kryo.readClassAndObject(input);
                    }
                    else {
                        object = kryo.readObject(input, objectType);
                    }
                    if (input.position() != payloadRange.offset + payloadRange.length) {
                        throw new KryoException("Trailing bytes after serialized object: "
                                + (payloadRange.offset + payloadRange.length - input.position()));
                    }
                }
            }
            finally {
                if (pooled) {
                    kryoPool.release(kryo);
                }
            }
            if (object == null || !objectType.isInstance(object)) {
                throw new KryoException("Deserialized root type "
                        + (object == null ? "null" : object.getClass().getName())
                        + " is not assignable to " + objectType.getName());
            }
            return object;
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize object, object type: " + objectType + ". " +
                    "Configure @PersistenceConfig.polymorphic if the collection will contain a mix of object types. " +
                    "Use the KryoSerializer.validateObjectIsRoundTripSerializable() method " +
                    "to test your object is compatible with CQEngine.", e);
        }
    }

    private byte[] addSecureEnvelope(byte[] payload) {
        int totalLength = SECURE_ENVELOPE_HEADER_BYTES + payload.length;
        if (totalLength > maxSerializedBytes) {
            throw new KryoException("Serialized output size " + totalLength
                    + " exceeds configured maximum " + maxSerializedBytes);
        }
        ByteBuffer envelope = ByteBuffer.allocate(totalLength);
        envelope.putInt(SECURE_ENVELOPE_MAGIC);
        envelope.put(SECURE_ENVELOPE_VERSION);
        envelope.put((byte) (polymorphic ? 1 : 0));
        envelope.put(registryFingerprint);
        envelope.putInt(payload.length);
        envelope.put(payload);
        return envelope.array();
    }

    private PayloadRange readSecureEnvelope(byte[] bytes) {
        if (bytes.length < SECURE_ENVELOPE_HEADER_BYTES) {
            throw new KryoException("Registered-types mode requires a CQEngine Kryo envelope");
        }
        ByteBuffer envelope = ByteBuffer.wrap(bytes);
        if (envelope.getInt() != SECURE_ENVELOPE_MAGIC) {
            throw new KryoException("Registered-types mode rejects historical unframed Kryo bytes");
        }
        byte version = envelope.get();
        if (version != SECURE_ENVELOPE_VERSION) {
            throw new KryoException("Unsupported CQEngine Kryo envelope version: " + version);
        }
        byte flags = envelope.get();
        byte expectedFlags = (byte) (polymorphic ? 1 : 0);
        if (flags != expectedFlags) {
            throw new KryoException("Kryo envelope polymorphism does not match reader configuration");
        }
        byte[] actualFingerprint = new byte[REGISTRY_FINGERPRINT_BYTES];
        envelope.get(actualFingerprint);
        if (!MessageDigest.isEqual(registryFingerprint, actualFingerprint)) {
            throw new KryoException("Kryo envelope registration fingerprint does not match reader configuration");
        }
        int payloadLength = envelope.getInt();
        if (payloadLength < 0 || payloadLength != bytes.length - SECURE_ENVELOPE_HEADER_BYTES) {
            throw new KryoException("Kryo envelope payload length does not match input size");
        }
        return new PayloadRange(SECURE_ENVELOPE_PAYLOAD_OFFSET, payloadLength);
    }

    private void requireAssignableRootRegistration(Kryo kryo, Input input) {
        int start = input.position();
        Registration registration = kryo.readClass(input);
        input.setPosition(start);
        if (registration == null || !objectType.isAssignableFrom(registration.getType())) {
            throw new KryoException("Registered root type "
                    + (registration == null ? "null" : registration.getType().getName())
                    + " is not assignable to " + objectType.getName());
        }
    }

    private void registerSecureType(Kryo kryo, Class<?> type) {
        if (kryo.getClassResolver().getRegistration(type) != null) {
            return;
        }
        Serializer<?> serializer = kryo.getDefaultSerializer(type);
        if (type.isArray()) {
            serializer = new BoundedArraySerializer(serializer, maxContainerElements);
        }
        else if (serializer instanceof TreeSetSerializer) {
            serializer = new BoundedTreeSetSerializer(maxContainerElements);
        }
        else if (serializer instanceof TreeMapSerializer) {
            serializer = new BoundedTreeMapSerializer(maxContainerElements);
        }
        else if (serializer instanceof JdkImmutableListSerializer) {
            serializer = new BoundedImmutableListSerializer(maxContainerElements);
        }
        else if (serializer instanceof JdkImmutableSetSerializer) {
            serializer = new BoundedImmutableSetSerializer(maxContainerElements);
        }
        else if (serializer instanceof JdkImmutableMapSerializer) {
            serializer = new BoundedImmutableMapSerializer(maxContainerElements);
        }
        else if (serializer.getClass() == CollectionSerializer.class) {
            serializer = new BoundedCollectionSerializer(maxContainerElements);
        }
        else if (serializer.getClass() == MapSerializer.class) {
            serializer = new BoundedMapSerializer(maxContainerElements);
        }
        kryo.register(type, serializer);
    }

    private static KryoDeserializationMode requireMode(KryoDeserializationMode mode) {
        if (mode == null) {
            throw new NullPointerException("Deserialization mode was null");
        }
        return mode;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero: " + value);
        }
        return value;
    }

    private static List<Class<?>> normalizeAllowedTypes(Class<?> objectType, Class<?>[] configuredTypes) {
        if (configuredTypes == null) {
            throw new NullPointerException("Allowed types were null");
        }
        TreeMap<String, Class<?>> byName = new TreeMap<String, Class<?>>();
        for (Class<?> type : configuredTypes.clone()) {
            if (type == null) {
                throw new NullPointerException("Allowed type was null");
            }
            requireStableType(type, "Allowed type");
            if (type == objectType) {
                continue;
            }
            Class<?> previous = byName.put(type.getName(), type);
            if (previous != null && previous != type) {
                throw new IllegalArgumentException("Allowed types contain duplicate binary name: "
                        + type.getName());
            }
        }
        return Collections.unmodifiableList(new ArrayList<Class<?>>(byName.values()));
    }

    private static void requireStableType(Class<?> type, String description) {
        if (type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic()) {
            throw new IllegalArgumentException(description + " does not have a stable binary identity: "
                    + type.getName());
        }
    }

    private static byte[] createRegistryFingerprint(
            Class<?> objectType, boolean polymorphic, List<Class<?>> allowedTypes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "cqengine-kryo-registered-types-v1");
            updateDigest(digest, objectType.getName());
            digest.update((byte) (polymorphic ? 1 : 0));
            for (Class<?> frameworkType : SECURE_FRAMEWORK_TYPES) {
                updateDigest(digest, frameworkType.getName());
            }
            for (Class<?> allowedType : allowedTypes) {
                updateDigest(digest, allowedType.getName());
            }
            for (JdkCollectionWrapperSerializers.WrapperKind wrapperKind
                    : JdkCollectionWrapperSerializers.WrapperKind.values()) {
                updateDigest(digest, wrapperKind.unmodifiableType.getName());
                updateDigest(digest, wrapperKind.synchronizedType.getName());
            }
            return digest.digest();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static final class PayloadRange {
        final int offset;
        final int length;

        PayloadRange(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    private final class KryoPool {
        private final ArrayBlockingQueue<Kryo> available =
                new ArrayBlockingQueue<Kryo>(KRYO_POOL_CAPACITY);
        private final Semaphore permits = new Semaphore(KRYO_POOL_CAPACITY, true);

        Kryo acquire() {
            try {
                permits.acquire();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KryoException("Interrupted while waiting for a Kryo serializer", e);
            }

            Kryo kryo = available.poll();
            if (kryo != null) {
                return kryo;
            }
            try {
                return createKryo(objectType);
            }
            catch (RuntimeException | Error failure) {
                permits.release();
                throw failure;
            }
        }

        void release(Kryo kryo) {
            boolean reusable = false;
            try {
                kryo.reset();
                reusable = true;
            }
            finally {
                try {
                    if (reusable && !available.offer(kryo)) {
                        throw new IllegalStateException("Kryo pool capacity invariant was violated");
                    }
                }
                finally {
                    permits.release();
                }
            }
        }
    }

    private static final class BoundedInput extends Input {
        private final int maxStringCharacters;

        BoundedInput(byte[] bytes, int offset, int length, int maxStringCharacters) {
            super(bytes, offset, length);
            this.maxStringCharacters = maxStringCharacters;
        }

        @Override
        public String readString() {
            validateUtf8CharacterCount();
            return super.readString();
        }

        @Override
        public StringBuilder readStringBuilder() {
            validateUtf8CharacterCount();
            return super.readStringBuilder();
        }

        private void validateUtf8CharacterCount() {
            int start = position();
            if (readVarIntFlag()) {
                int encodedCharacterCount = readVarIntFlag(true);
                int characterCount = encodedCharacterCount <= 1 ? 0 : encodedCharacterCount - 1;
                if (characterCount > maxStringCharacters) {
                    throw new KryoException("String character count " + characterCount
                            + " exceeds configured maximum " + maxStringCharacters);
                }
                setPosition(start);
            }
            else {
                int characterCount = 0;
                for (int index = start; index < limit; index++) {
                    characterCount++;
                    if (characterCount > maxStringCharacters) {
                        throw new KryoException("String character count exceeds configured maximum "
                                + maxStringCharacters);
                    }
                    if ((buffer[index] & 0x80) != 0) {
                        return;
                    }
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class BoundedArraySerializer extends Serializer<Object> {
        private final Serializer delegate;
        private final int maxContainerElements;

        BoundedArraySerializer(Serializer<?> delegate, int maxContainerElements) {
            this.delegate = delegate;
            this.maxContainerElements = maxContainerElements;
            setAcceptsNull(delegate.getAcceptsNull());
            setImmutable(delegate.isImmutable());
        }

        @Override
        public void write(Kryo kryo, Output output, Object value) {
            if (value != null) {
                requireContainerElements(Array.getLength(value), maxContainerElements);
            }
            delegate.write(kryo, output, value);
        }

        @Override
        public Object read(Kryo kryo, Input input, Class<?> type) {
            int start = input.position();
            int encodedLength = input.readVarInt(true);
            if (encodedLength != Kryo.NULL) {
                requireContainerElements(encodedLength - 1, maxContainerElements);
            }
            input.setPosition(start);
            return delegate.read(kryo, input, type);
        }

        @Override
        public Object copy(Kryo kryo, Object original) {
            return delegate.copy(kryo, original);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class BoundedCollectionSerializer extends CollectionSerializer<Collection> {
        final int maxContainerElements;

        BoundedCollectionSerializer(int maxContainerElements) {
            this.maxContainerElements = maxContainerElements;
        }

        @Override
        public void write(Kryo kryo, Output output, Collection collection) {
            if (collection != null) {
                requireContainerElements(collection.size(), maxContainerElements);
            }
            super.write(kryo, output, collection);
        }

        @Override
        protected Collection create(Kryo kryo, Input input, Class<? extends Collection> type, int size) {
            requireContainerElements(size, maxContainerElements);
            return super.create(kryo, input, type, size);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class BoundedMapSerializer extends MapSerializer<Map> {
        final int maxContainerElements;

        BoundedMapSerializer(int maxContainerElements) {
            this.maxContainerElements = maxContainerElements;
        }

        @Override
        public void write(Kryo kryo, Output output, Map map) {
            if (map != null) {
                requireContainerElements(map.size(), maxContainerElements);
            }
            super.write(kryo, output, map);
        }

        @Override
        protected Map create(Kryo kryo, Input input, Class<? extends Map> type, int size) {
            requireContainerElements(size, maxContainerElements);
            return super.create(kryo, input, type, size);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class BoundedImmutableListSerializer extends BoundedCollectionSerializer {

        BoundedImmutableListSerializer(int maxContainerElements) {
            super(maxContainerElements);
            setElementsCanBeNull(false);
        }

        @Override
        protected Collection create(Kryo kryo, Input input, Class<? extends Collection> type, int size) {
            requireContainerElements(size, maxContainerElements);
            return new ArrayList<Object>(size);
        }

        @Override
        protected Collection createCopy(Kryo kryo, Collection original) {
            requireContainerElements(original.size(), maxContainerElements);
            return new ArrayList<Object>(original.size());
        }

        @Override
        public Collection read(Kryo kryo, Input input, Class<? extends Collection> type) {
            Collection values = super.read(kryo, input, type);
            return values == null ? null : List.of(values.toArray());
        }

        @Override
        public Collection copy(Kryo kryo, Collection original) {
            return List.copyOf(super.copy(kryo, original));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class BoundedImmutableSetSerializer extends BoundedCollectionSerializer {

        BoundedImmutableSetSerializer(int maxContainerElements) {
            super(maxContainerElements);
            setElementsCanBeNull(false);
        }

        @Override
        protected Collection create(Kryo kryo, Input input, Class<? extends Collection> type, int size) {
            requireContainerElements(size, maxContainerElements);
            return new HashSet<Object>(Math.max((int) (size / 0.75f) + 1, 16));
        }

        @Override
        protected Collection createCopy(Kryo kryo, Collection original) {
            requireContainerElements(original.size(), maxContainerElements);
            return new HashSet<Object>(Math.max((int) (original.size() / 0.75f) + 1, 16));
        }

        @Override
        public Collection read(Kryo kryo, Input input, Class<? extends Collection> type) {
            Collection values = super.read(kryo, input, type);
            return values == null ? null : Set.of(values.toArray());
        }

        @Override
        public Collection copy(Kryo kryo, Collection original) {
            return Set.copyOf(super.copy(kryo, original));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class BoundedImmutableMapSerializer extends BoundedMapSerializer {

        BoundedImmutableMapSerializer(int maxContainerElements) {
            super(maxContainerElements);
            setKeysCanBeNull(false);
            setValuesCanBeNull(false);
        }

        @Override
        protected Map create(Kryo kryo, Input input, Class<? extends Map> type, int size) {
            requireContainerElements(size, maxContainerElements);
            return new HashMap<Object, Object>();
        }

        @Override
        protected Map createCopy(Kryo kryo, Map original) {
            requireContainerElements(original.size(), maxContainerElements);
            return new HashMap<Object, Object>();
        }

        @Override
        public Map read(Kryo kryo, Input input, Class<? extends Map> type) {
            Map values = super.read(kryo, input, type);
            return values == null ? null : Map.copyOf(values);
        }

        @Override
        public Map copy(Kryo kryo, Map original) {
            return Map.copyOf(super.copy(kryo, original));
        }
    }

    @SuppressWarnings("rawtypes") // Overrides Kryo's raw TreeSetSerializer method contract.
    private static final class BoundedTreeSetSerializer extends TreeSetSerializer {
        private final int maxContainerElements;

        BoundedTreeSetSerializer(int maxContainerElements) {
            this.maxContainerElements = maxContainerElements;
        }

        @Override
        public void write(Kryo kryo, Output output, TreeSet collection) {
            requireContainerElements(collection.size(), maxContainerElements);
            super.write(kryo, output, collection);
        }

        @Override
        protected TreeSet create(Kryo kryo, Input input, Class<? extends TreeSet> type, int size) {
            requireContainerElements(size, maxContainerElements);
            return super.create(kryo, input, type, size);
        }
    }

    @SuppressWarnings("rawtypes") // Overrides Kryo's raw TreeMapSerializer method contract.
    private static final class BoundedTreeMapSerializer extends TreeMapSerializer {
        private final int maxContainerElements;

        BoundedTreeMapSerializer(int maxContainerElements) {
            this.maxContainerElements = maxContainerElements;
        }

        @Override
        public void write(Kryo kryo, Output output, TreeMap map) {
            requireContainerElements(map.size(), maxContainerElements);
            super.write(kryo, output, map);
        }

        @Override
        protected TreeMap create(Kryo kryo, Input input, Class<? extends TreeMap> type, int size) {
            requireContainerElements(size, maxContainerElements);
            return super.create(kryo, input, type, size);
        }
    }

    private static void requireContainerElements(int size, int maxContainerElements) {
        if (size < 0 || size > maxContainerElements) {
            throw new KryoException("Container element count " + size
                    + " exceeds configured maximum " + maxContainerElements);
        }
    }

    /**
     * Performs sanity tests on the given POJO object, to check if it can be serialized and deserialized with Kryo
     * serialzier as used by CQEngine.
     * <p>
     * If a POJO fails this test, then it typically means CQEngine will be unable to serialize or deserialize
     * it, and thus the POJO can't be used with CQEngine's off-heap or disk indexes or persistence.
     * <p>
     * Failing the test typically means the data structures or data types within the POJO are too complex. Simplifying
     * the POJO will usually improve compatibility.
     * <p>
     * This method will return normally if the POJO passes the tests, or will throw an exception if it fails.
     *
     * @param candidatePojo The POJO to test
     */
    @SuppressWarnings("unchecked")
    public static <O> void validateObjectIsRoundTripSerializable(O candidatePojo) {
        Class<O> objectType = (Class<O>) candidatePojo.getClass();
        KryoSerializer.validateObjectIsRoundTripSerializable(candidatePojo, objectType, PersistenceConfig.DEFAULT_CONFIG);
    }

    static <O> void validateObjectIsRoundTripSerializable(O candidatePojo, Class<O> objectType, PersistenceConfig persistenceConfig) {
        try {
            KryoSerializer<O> serializer = new KryoSerializer<O>(
                    objectType,
                    persistenceConfig
            );
            byte[] serialized = serializer.serialize(candidatePojo);
            O deserializedPojo = serializer.deserialize(serialized);
            serializer.kryoCache.remove();  // clear cached Kryo instance
            validateObjectEquality(candidatePojo, deserializedPojo);
            validateHashCodeEquality(candidatePojo, deserializedPojo);
        }
        catch (Exception e) {
            throw new IllegalStateException("POJO object failed round trip serialization-deserialization test, object type: " + objectType + ", object: " + candidatePojo, e);
        }
    }

    static void validateObjectEquality(Object candidate, Object deserializedPojo) {
        if (!(deserializedPojo.equals(candidate))) {
            throw new IllegalStateException("The POJO after round trip serialization is not equal to the original POJO");
        }
    }

    static void validateHashCodeEquality(Object candidate, Object deserializedPojo) {
        if (!(deserializedPojo.hashCode() == candidate.hashCode())) {
            throw new IllegalStateException("The POJO's hashCode after round trip serialization differs from its original hashCode");
        }
    }
}
