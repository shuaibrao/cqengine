// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package io.github.shuaibrao.cqengine.consumer;

import com.googlecode.cqengine.persistence.support.serialization.KryoDeserializationMode;
import com.googlecode.cqengine.persistence.support.serialization.PersistenceConfig;

import java.util.Objects;

@PersistenceConfig(deserializationMode = KryoDeserializationMode.REGISTERED_TYPES)
public final class ConsumerItem {

    public int id;
    public String name;

    public ConsumerItem() {
    }

    ConsumerItem(int id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ConsumerItem)) {
            return false;
        }
        ConsumerItem item = (ConsumerItem) other;
        return id == item.id && Objects.equals(name, item.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }
}
