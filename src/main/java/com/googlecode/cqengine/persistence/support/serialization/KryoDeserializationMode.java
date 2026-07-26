// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

/**
 * Selects the trust boundary used by {@link KryoSerializer}.
 */
public enum KryoDeserializationMode {

    /**
     * Reads and writes the historical unframed Kryo format. Class names in a polymorphic graph are trusted, so the
     * persisted bytes must be protected from untrusted modification.
     */
    TRUSTED_STORE_COMPATIBILITY,

    /**
     * Requires deterministic class registration and a CQEngine-owned version envelope. Historical unframed bytes are
     * deliberately rejected so an allowlisted reader cannot silently fall back to class-name deserialization.
     */
    REGISTERED_TYPES
}
