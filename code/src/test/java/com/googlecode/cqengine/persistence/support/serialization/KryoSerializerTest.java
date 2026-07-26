/*
 * Modified by Shuaib Rao in 2026.
 */

package com.googlecode.cqengine.persistence.support.serialization;

import com.googlecode.cqengine.testutil.ExpectedException;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

/**
 * Unit tests for {@link KryoSerializer}.
 *
 * @author npgall
 */
public class KryoSerializerTest {

    @Test
    public void testPositiveSerializability() {
        KryoSerializer.validateObjectIsRoundTripSerializable(new KryoSerializablePojo(1));
    }

    @Test
    @ExpectedException(IllegalStateException.class)
    public void testNegativeSerializability() {
        KryoSerializer.validateObjectIsRoundTripSerializable(new NonKryoSerializablePojo(1));
    }

    @Test
    @ExpectedException(IllegalStateException.class)
    public void testValidateObjectEquality() {
        KryoSerializer.validateObjectEquality(1, 2);
    }

    @Test
    @ExpectedException(IllegalStateException.class)
    public void testValidateHashCodeEquality() {
        KryoSerializer.validateHashCodeEquality(1, 2);
    }

    @Test
    @ExpectedException(IllegalStateException.class)
    public void testPolymorphicSerialization_WithNonPolymorphicConfig() {
        KryoSerializer.validateObjectIsRoundTripSerializable(1, Number.class, PersistenceConfig.DEFAULT_CONFIG);
    }

    @Test
    public void testPolymorphicSerialization_WithPolymorphicConfig() {
        KryoSerializer.validateObjectIsRoundTripSerializable(1, Number.class, POLYMORPHIC_CONFIG);
    }

    @SuppressWarnings("unused")
    static class KryoSerializablePojo {
        int i;
        KryoSerializablePojo() {
        }
        KryoSerializablePojo(int i) {
            this.i = i;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof KryoSerializablePojo)) return false;

            KryoSerializablePojo that = (KryoSerializablePojo) o;

            return i == that.i;
        }

        @Override
        public int hashCode() {
            return i;
        }
    }

    @SuppressWarnings("unused")
    static class NonKryoSerializablePojo {
        int i;
        NonKryoSerializablePojo() {
            throw new IllegalStateException("Intentional exception");
        }
        NonKryoSerializablePojo(int i) {
            this.i = i;
        }
    }

    PersistenceConfig POLYMORPHIC_CONFIG = new PersistenceConfig() {

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
            return true;
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
            return PersistenceConfig.DEFAULT_CONFIG.maxSerializedBytes();
        }

        @Override
        public int maxGraphDepth() {
            return PersistenceConfig.DEFAULT_CONFIG.maxGraphDepth();
        }

        @Override
        public int maxContainerElements() {
            return PersistenceConfig.DEFAULT_CONFIG.maxContainerElements();
        }

        @Override
        public int maxStringCharacters() {
            return PersistenceConfig.DEFAULT_CONFIG.maxStringCharacters();
        }
    };
}
