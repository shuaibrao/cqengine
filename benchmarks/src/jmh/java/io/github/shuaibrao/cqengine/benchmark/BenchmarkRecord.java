// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.benchmark;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.util.ArrayList;
import java.util.List;

final class BenchmarkRecord {

    static final SimpleAttribute<BenchmarkRecord, Integer> ID =
            new SimpleAttribute<BenchmarkRecord, Integer>("id") {
                @Override
                public Integer getValue(BenchmarkRecord record, QueryOptions queryOptions) {
                    return record.id;
                }
            };

    static final SimpleAttribute<BenchmarkRecord, String> MANUFACTURER =
            new SimpleAttribute<BenchmarkRecord, String>("manufacturer") {
                @Override
                public String getValue(BenchmarkRecord record, QueryOptions queryOptions) {
                    return record.manufacturer;
                }
            };

    static final SimpleAttribute<BenchmarkRecord, String> MODEL =
            new SimpleAttribute<BenchmarkRecord, String>("model") {
                @Override
                public String getValue(BenchmarkRecord record, QueryOptions queryOptions) {
                    return record.model;
                }
            };

    static final SimpleAttribute<BenchmarkRecord, Integer> PRICE =
            new SimpleAttribute<BenchmarkRecord, Integer>("price") {
                @Override
                public Integer getValue(BenchmarkRecord record, QueryOptions queryOptions) {
                    return record.price;
                }
            };

    final int id;
    final String manufacturer;
    final String model;
    final int price;

    BenchmarkRecord(int id, String manufacturer, String model, int price) {
        this.id = id;
        this.manufacturer = manufacturer;
        this.model = model;
        this.price = price;
    }

    static BenchmarkRecord create(int id) {
        int bucket = id & 3;
        return new BenchmarkRecord(
                id,
                bucket == 0 ? "Ford" : bucket == 1 ? "Honda" : bucket == 2 ? "Toyota" : "BMW",
                bucket == 0 ? "Focus" : bucket == 1 ? "Civic" : bucket == 2 ? "Prius" : "M6",
                2_500 + id % 7_500);
    }

    static List<BenchmarkRecord> createMany(int count) {
        List<BenchmarkRecord> records = new ArrayList<BenchmarkRecord>(count);
        for (int id = 0; id < count; id++) {
            records.add(create(id));
        }
        return records;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof BenchmarkRecord && id == ((BenchmarkRecord) other).id;
    }

    @Override
    public int hashCode() {
        return id;
    }
}
