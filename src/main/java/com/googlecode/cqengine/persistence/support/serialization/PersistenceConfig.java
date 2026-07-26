/*
 * Modified by Shuaib Rao in 2026.
 */

package com.googlecode.cqengine.persistence.support.serialization;

import java.lang.annotation.*;

/**
 * An annotation which can be added to POJO classes to customize persistence behavior.
 *
 * @author npgall
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PersistenceConfig {

    /**
     * The {@link PojoSerializer} implementation to use.
     * <p>
     *     The default is {@link KryoSerializer}. Note that the behaviour of that serializer
     *     is highly customizable via annotations itself, including the ability to configure
     *     it to use Java's built-in serialization. See
     *     <a href="https://github.com/EsotericSoftware/kryo">Kryo</a> for details.
     * </p>
     */
    @SuppressWarnings("rawtypes") // Retains the legacy annotation element signature stored in class files.
    Class<? extends PojoSerializer> serializer() default KryoSerializer.class;

    /**
     * If true, causes CQEngine to persist the name of the class with every object,
     * to allow the collection to contain a mix of object types within an inheritance hierarchy.
     *
     * If false, causes CQEngine to skip persisting the name of the class and to assume all objects
     * in the collection will be instances of the same class.
     * <p>
     *     The default value is false, which is commonly applicable and gives better performance
     *     and reduces the size of the serialized collection. However it will cause exceptions if
     *     different types of objects are added to the same collection, in which case applications
     *     can change this setting.
     * </p>
     */
    boolean polymorphic() default false;

    /**
     * Selects whether persisted classes are trusted by name for compatibility, or must be deterministically
     * registered. The compatibility default preserves existing CQEngine stores and must only be used when store bytes
     * are trusted.
     */
    KryoDeserializationMode deserializationMode()
            default KryoDeserializationMode.TRUSTED_STORE_COMPATIBILITY;

    /**
     * Additional concrete classes which may occur in an object graph when {@link #deserializationMode()} is
     * {@link KryoDeserializationMode#REGISTERED_TYPES}. Registration order is normalized by binary class name.
     */
    Class<?>[] allowedTypes() default {};

    /** Maximum serialized blob size, including the secure-mode envelope. */
    int maxSerializedBytes() default 16 * 1024 * 1024;

    /** Maximum Kryo object-graph depth. */
    int maxGraphDepth() default 100;

    /**
     * Maximum elements accepted by CQEngine-owned collection-wrapper serializers in both modes, and by registered
     * standard array, collection and map serializers in {@link KryoDeserializationMode#REGISTERED_TYPES} mode.
     */
    int maxContainerElements() default 1_000_000;

    /** Maximum UTF-16 characters accepted by Kryo string input. */
    int maxStringCharacters() default 1_000_000;

    PersistenceConfig DEFAULT_CONFIG = new PersistenceConfig() {

        @Override
        public Class<? extends Annotation> annotationType() {
            return PersistenceConfig.class;
        }

        @Override
        @SuppressWarnings("rawtypes") // Implements the legacy annotation element signature.
        public Class<? extends PojoSerializer> serializer() {
            return KryoSerializer.class;
        }

        @Override
        public boolean polymorphic() {
            return false;
        }

        @Override
        public KryoDeserializationMode deserializationMode() {
            return KryoDeserializationMode.TRUSTED_STORE_COMPATIBILITY;
        }

        @Override
        public Class<?>[] allowedTypes() {
            return new Class<?>[0];
        }

        @Override
        public int maxSerializedBytes() {
            return 16 * 1024 * 1024;
        }

        @Override
        public int maxGraphDepth() {
            return 100;
        }

        @Override
        public int maxContainerElements() {
            return 1_000_000;
        }

        @Override
        public int maxStringCharacters() {
            return 1_000_000;
        }
    };
}
