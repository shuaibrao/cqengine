// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.benchmark;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.resultset.ResultSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.Collections;

public class MutationAllocationBenchmark {

    @State(Scope.Thread)
    public static class MutationState {

        @Param({"1000"})
        public int datasetSize;

        IndexedCollection<BenchmarkRecord> records;
        BenchmarkRecord current;
        BenchmarkRecord replacement;

        @Setup(Level.Trial)
        public void setUp() {
            records = new ConcurrentIndexedCollection<BenchmarkRecord>();
            records.addIndex(HashIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
            records.addAll(BenchmarkRecord.createMany(datasetSize));
            current = BenchmarkRecord.create(datasetSize / 2);
            replacement = new BenchmarkRecord(
                    current.id, "Replacement", "Replacement", current.price + 1);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (records.size() != datasetSize) {
                throw new IllegalStateException("Mutation allocation benchmark changed collection size");
            }
            try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(
                    QueryFactory.equal(BenchmarkRecord.MANUFACTURER, current.manufacturer))) {
                int matches = 0;
                for (BenchmarkRecord record : resultSet) {
                    if (record.id == current.id) {
                        matches++;
                    }
                }
            if (matches != 1) {
                throw new IllegalStateException("Mutation allocation benchmark corrupted its index");
            }
        }
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(
                QueryFactory.equal(BenchmarkRecord.MANUFACTURER, replacement.manufacturer))) {
            for (BenchmarkRecord record : resultSet) {
                if (record.id == replacement.id) {
                    throw new IllegalStateException("Mutation allocation benchmark retained a stale index entry");
                }
            }
        }
    }
    }

    @Benchmark
    public boolean replaceWithSingletonInputs(MutationState state) {
        if (!state.records.update(
                Collections.singleton(state.current),
                Collections.singleton(state.replacement))) {
            throw new IllegalStateException("Mutation allocation benchmark did not update the collection");
        }
        BenchmarkRecord previous = state.current;
        state.current = state.replacement;
        state.replacement = previous;
        return true;
    }
}
