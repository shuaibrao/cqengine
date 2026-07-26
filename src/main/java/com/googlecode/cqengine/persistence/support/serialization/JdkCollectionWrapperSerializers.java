// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

final class JdkCollectionWrapperSerializers {

    private JdkCollectionWrapperSerializers() {
    }

    static void registerWith(Kryo kryo) {
        registerWith(kryo, Integer.MAX_VALUE);
    }

    static void registerWith(Kryo kryo, int maxContainerElements) {
        kryo.register(Arrays.asList().getClass(), new ArraysAsListSerializer(maxContainerElements));

        CollectionWrapperSerializer unmodifiable =
                new CollectionWrapperSerializer(false, maxContainerElements);
        for (WrapperKind kind : WrapperKind.values()) {
            kryo.register(kind.unmodifiableType, unmodifiable);
        }

        CollectionWrapperSerializer synchronizedSerializer =
                new CollectionWrapperSerializer(true, maxContainerElements);
        for (WrapperKind kind : WrapperKind.values()) {
            kryo.register(kind.synchronizedType, synchronizedSerializer);
        }
    }

    static final class ArraysAsListSerializer extends Serializer<List<?>> {

        private final int maxContainerElements;

        ArraysAsListSerializer(int maxContainerElements) {
            this.maxContainerElements = maxContainerElements;
        }

        @Override
        public void write(Kryo kryo, Output output, List<?> value) {
            // List exposes neither the backing array nor its component type. New writes use an Object[] snapshot;
            // legacy reads still honor the component type stored in existing bytes.
            requireElementCount(value.size(), maxContainerElements);
            Object[] snapshot = value.toArray();
            output.writeInt(snapshot.length, true);
            kryo.writeClass(output, Object.class);
            for (Object element : snapshot) {
                kryo.writeClassAndObject(output, element);
            }
        }

        @Override
        public List<?> read(Kryo kryo, Input input, Class<? extends List<?>> type) {
            int size = input.readInt(true);
            requireElementCount(size, maxContainerElements);
            Registration componentRegistration = kryo.readClass(input);
            if (componentRegistration == null) {
                throw new IllegalStateException("Arrays.asList component type was null");
            }
            Class<?> componentType = boxedType(componentRegistration.getType());
            Object[] elements = (Object[]) Array.newInstance(componentType, size);
            List<?> result = Arrays.asList(elements);
            kryo.reference(result);
            for (int i = 0; i < size; i++) {
                Array.set(elements, i, kryo.readClassAndObject(input));
            }
            return result;
        }

        @Override
        public List<?> copy(Kryo kryo, List<?> original) {
            requireElementCount(original.size(), maxContainerElements);
            Object[] elements = new Object[original.size()];
            List<?> result = Arrays.asList(elements);
            kryo.reference(result);
            for (int i = 0; i < elements.length; i++) {
                elements[i] = kryo.copy(original.get(i));
            }
            return result;
        }

        private static Class<?> boxedType(Class<?> type) {
            if (!type.isPrimitive()) {
                return type;
            }
            if (type == boolean.class) return Boolean.class;
            if (type == byte.class) return Byte.class;
            if (type == char.class) return Character.class;
            if (type == short.class) return Short.class;
            if (type == int.class) return Integer.class;
            if (type == long.class) return Long.class;
            if (type == float.class) return Float.class;
            if (type == double.class) return Double.class;
            throw new IllegalArgumentException("Unsupported array component type: " + type);
        }
    }

    static final class CollectionWrapperSerializer extends Serializer<Object> {

        private final boolean synchronizedWrapper;
        private final int maxContainerElements;

        CollectionWrapperSerializer(boolean synchronizedWrapper, int maxContainerElements) {
            this.synchronizedWrapper = synchronizedWrapper;
            this.maxContainerElements = maxContainerElements;
        }

        @Override
        public void write(Kryo kryo, Output output, Object value) {
            requireContainerSize(value, maxContainerElements);
            WrapperKind kind = WrapperKind.forType(value.getClass(), synchronizedWrapper);
            output.writeInt(kind.ordinal(), true);
            if (synchronizedWrapper) {
                synchronized (value) {
                    kryo.writeClassAndObject(output, kind.snapshot(value));
                }
            }
            else {
                kryo.writeClassAndObject(output, kind.snapshot(value));
            }
        }

        @Override
        public Object read(Kryo kryo, Input input, Class<?> type) {
            int ordinal = input.readInt(true);
            WrapperKind[] kinds = WrapperKind.values();
            if (ordinal < 0 || ordinal >= kinds.length) {
                throw new IllegalStateException("Unsupported collection wrapper kind: " + ordinal);
            }
            WrapperKind kind = kinds[ordinal];
            Class<?> expectedType = synchronizedWrapper ? kind.synchronizedType : kind.unmodifiableType;
            if (type != expectedType) {
                throw new IllegalStateException("Collection wrapper kind does not match serialized type");
            }
            Object source = kryo.readClassAndObject(input);
            requireContainerSize(source, maxContainerElements);
            return synchronizedWrapper ? kind.synchronizedView(source) : kind.unmodifiableView(source);
        }

        @Override
        public Object copy(Kryo kryo, Object original) {
            requireContainerSize(original, maxContainerElements);
            WrapperKind kind = WrapperKind.forType(original.getClass(), synchronizedWrapper);
            Object source;
            if (synchronizedWrapper) {
                synchronized (original) {
                    source = kryo.copy(kind.snapshot(original));
                }
            }
            else {
                source = kryo.copy(kind.snapshot(original));
            }
            return synchronizedWrapper ? kind.synchronizedView(source) : kind.unmodifiableView(source);
        }
    }

    private static void requireContainerSize(Object value, int maxContainerElements) {
        int size;
        if (value instanceof Collection) {
            size = ((Collection<?>) value).size();
        }
        else if (value instanceof Map) {
            size = ((Map<?, ?>) value).size();
        }
        else {
            throw new KryoException("Unsupported container value: "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        requireElementCount(size, maxContainerElements);
    }

    private static void requireElementCount(int size, int maxContainerElements) {
        if (size < 0 || size > maxContainerElements) {
            throw new KryoException("Container element count " + size
                    + " exceeds configured maximum " + maxContainerElements);
        }
    }

    enum WrapperKind {
        COLLECTION(
                Collections.unmodifiableCollection(new ArrayList<Object>()).getClass(),
                Collections.synchronizedCollection(new ArrayList<Object>()).getClass()),
        RANDOM_ACCESS_LIST(
                Collections.unmodifiableList(new ArrayList<Object>()).getClass(),
                Collections.synchronizedList(new ArrayList<Object>()).getClass()),
        LIST(
                Collections.unmodifiableList(new LinkedList<Object>()).getClass(),
                Collections.synchronizedList(new LinkedList<Object>()).getClass()),
        SET(
                Collections.unmodifiableSet(new LinkedHashSet<Object>()).getClass(),
                Collections.synchronizedSet(new LinkedHashSet<Object>()).getClass()),
        SORTED_SET(
                Collections.unmodifiableSortedSet(new TreeSet<Object>()).getClass(),
                Collections.synchronizedSortedSet(new TreeSet<Object>()).getClass()),
        MAP(
                Collections.unmodifiableMap(new LinkedHashMap<Object, Object>()).getClass(),
                Collections.synchronizedMap(new LinkedHashMap<Object, Object>()).getClass()),
        SORTED_MAP(
                Collections.unmodifiableSortedMap(new TreeMap<Object, Object>()).getClass(),
                Collections.synchronizedSortedMap(new TreeMap<Object, Object>()).getClass());

        final Class<?> unmodifiableType;
        final Class<?> synchronizedType;

        WrapperKind(Class<?> unmodifiableType, Class<?> synchronizedType) {
            this.unmodifiableType = unmodifiableType;
            this.synchronizedType = synchronizedType;
        }

        static WrapperKind forType(Class<?> type, boolean synchronizedWrapper) {
            for (WrapperKind kind : values()) {
                Class<?> supportedType = synchronizedWrapper ? kind.synchronizedType : kind.unmodifiableType;
                if (type == supportedType) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unsupported collection wrapper type: " + type.getName());
        }

        Object snapshot(Object value) {
            // The wrapped collection is intentionally copied through its public view. Its identity and concrete
            // implementation are not available without opening java.util internals.
            switch (this) {
                case COLLECTION:
                case RANDOM_ACCESS_LIST:
                    return new ArrayList<Object>((Collection<?>) value);
                case LIST:
                    return new LinkedList<Object>((Collection<?>) value);
                case SET:
                    return new LinkedHashSet<Object>((Set<?>) value);
                case SORTED_SET:
                    return copySortedSet((SortedSet<?>) value);
                case MAP:
                    return new LinkedHashMap<Object, Object>((Map<?, ?>) value);
                case SORTED_MAP:
                    return copySortedMap((SortedMap<?, ?>) value);
                default:
                    throw new AssertionError(this);
            }
        }

        Object unmodifiableView(Object value) {
            switch (this) {
                case COLLECTION:
                    return Collections.unmodifiableCollection((Collection<?>) value);
                case RANDOM_ACCESS_LIST:
                case LIST:
                    return Collections.unmodifiableList((List<?>) value);
                case SET:
                    return Collections.unmodifiableSet((Set<?>) value);
                case SORTED_SET:
                    return Collections.unmodifiableSortedSet((SortedSet<?>) value);
                case MAP:
                    return Collections.unmodifiableMap((Map<?, ?>) value);
                case SORTED_MAP:
                    return Collections.unmodifiableSortedMap((SortedMap<?, ?>) value);
                default:
                    throw new AssertionError(this);
            }
        }

        Object synchronizedView(Object value) {
            switch (this) {
                case COLLECTION:
                    return Collections.synchronizedCollection((Collection<?>) value);
                case RANDOM_ACCESS_LIST:
                case LIST:
                    return Collections.synchronizedList((List<?>) value);
                case SET:
                    return Collections.synchronizedSet((Set<?>) value);
                case SORTED_SET:
                    return Collections.synchronizedSortedSet((SortedSet<?>) value);
                case MAP:
                    return Collections.synchronizedMap((Map<?, ?>) value);
                case SORTED_MAP:
                    return Collections.synchronizedSortedMap((SortedMap<?, ?>) value);
                default:
                    throw new AssertionError(this);
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static SortedSet<?> copySortedSet(SortedSet<?> value) {
            TreeSet copy = new TreeSet(value.comparator());
            copy.addAll(value);
            return copy;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static SortedMap<?, ?> copySortedMap(SortedMap<?, ?> value) {
            TreeMap copy = new TreeMap(value.comparator());
            copy.putAll(value);
            return copy;
        }
    }
}
