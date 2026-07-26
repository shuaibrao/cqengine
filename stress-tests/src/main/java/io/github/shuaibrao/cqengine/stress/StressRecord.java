// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.stress;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;

public record StressRecord(int id, int group, long version) {

    public static final SimpleAttribute<StressRecord, Integer> ID =
            new SimpleAttribute<>(StressRecord.class, Integer.class, "id") {
                @Override
                public Integer getValue(StressRecord object, QueryOptions queryOptions) {
                    return object.id();
                }
            };

    public static final SimpleAttribute<StressRecord, Integer> GROUP =
            new SimpleAttribute<>(StressRecord.class, Integer.class, "group") {
                @Override
                public Integer getValue(StressRecord object, QueryOptions queryOptions) {
                    return object.group();
                }
            };
}
