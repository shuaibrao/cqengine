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
import org.openjdk.jmh.infra.BenchmarkParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MutationBenchmark {

    @State(Scope.Thread)
    public static class MutationState {

        @Param({"1000"})
        public int datasetSize;

        List<IndexedCollection<BenchmarkRecord>> samples;
        BenchmarkRecord victim;
        BenchmarkRecord replacement;
        BenchmarkRecord added;
        int nextSample;

        @Setup(Level.Trial)
        public void setUp(BenchmarkParams benchmarkParams) {
            List<BenchmarkRecord> dataset = BenchmarkRecord.createMany(datasetSize);
            int sampleCount = requiredSamples(benchmarkParams);
            samples = new ArrayList<IndexedCollection<BenchmarkRecord>>(sampleCount);
            for (int i = 0; i < sampleCount; i++) {
                IndexedCollection<BenchmarkRecord> records = new ConcurrentIndexedCollection<BenchmarkRecord>();
                records.addIndex(HashIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
                records.addAll(dataset);
                samples.add(records);
            }
            victim = dataset.get(datasetSize / 2);
            replacement = new BenchmarkRecord(
                    victim.id, "Replacement", "Replacement", victim.price + 1);
            added = BenchmarkRecord.create(datasetSize + 1);
            verifyMutations(dataset, victim, replacement, added);
        }

        IndexedCollection<BenchmarkRecord> next() {
            if (nextSample >= samples.size()) {
                throw new IllegalStateException("Mutation benchmark exceeded its configured invocation count");
            }
            return samples.get(nextSample++);
        }
    }

    @State(Scope.Thread)
    public static class IndexBuildState {

        @Param({"10000"})
        public int datasetSize;

        List<IndexedCollection<BenchmarkRecord>> samples;
        int nextSample;

        @Setup(Level.Trial)
        public void setUp(BenchmarkParams benchmarkParams) {
            List<BenchmarkRecord> dataset = BenchmarkRecord.createMany(datasetSize);
            verifyPopulatedIndex(dataset);
            int sampleCount = requiredSamples(benchmarkParams);
            samples = new ArrayList<IndexedCollection<BenchmarkRecord>>(sampleCount);
            for (int i = 0; i < sampleCount; i++) {
                IndexedCollection<BenchmarkRecord> records = new ConcurrentIndexedCollection<BenchmarkRecord>();
                records.addAll(dataset);
                samples.add(records);
            }
        }

        IndexedCollection<BenchmarkRecord> next() {
            if (nextSample >= samples.size()) {
                throw new IllegalStateException("Index benchmark exceeded its configured invocation count");
            }
            return samples.get(nextSample++);
        }
    }

    private static int requiredSamples(BenchmarkParams benchmarkParams) {
        int warmupSamples = Math.multiplyExact(
                benchmarkParams.getWarmup().getCount(),
                benchmarkParams.getWarmup().getBatchSize());
        int measurementSamples = Math.multiplyExact(
                benchmarkParams.getMeasurement().getCount(),
                benchmarkParams.getMeasurement().getBatchSize());
        return Math.addExact(warmupSamples, measurementSamples);
    }

    private static void verifyMutations(
            List<BenchmarkRecord> dataset,
            BenchmarkRecord victim,
            BenchmarkRecord replacement,
            BenchmarkRecord added) {
        IndexedCollection<BenchmarkRecord> records = new ConcurrentIndexedCollection<BenchmarkRecord>();
        records.addIndex(HashIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
        records.addAll(dataset);
        int originalSize = records.size();

        if (!records.add(added)
                || records.size() != originalSize + 1
                || findById(records, added.manufacturer, added.id) != added) {
            throw new IllegalStateException("Benchmark add verification failed");
        }
        if (!records.remove(added)
                || records.size() != originalSize
                || findById(records, added.manufacturer, added.id) != null) {
            throw new IllegalStateException("Benchmark remove-after-add verification failed");
        }

        if (!records.remove(victim)
                || records.size() != originalSize - 1
                || findById(records, victim.manufacturer, victim.id) != null) {
            throw new IllegalStateException("Benchmark remove verification failed");
        }
        if (!records.add(victim)
                || records.size() != originalSize
                || findById(records, victim.manufacturer, victim.id) != victim) {
            throw new IllegalStateException("Benchmark remove restoration failed");
        }

        if (!records.update(
                        Collections.singleton(victim),
                        Collections.singleton(replacement))
                || records.size() != originalSize
                || findById(records, victim.manufacturer, victim.id) != null
                || findById(records, replacement.manufacturer, replacement.id) != replacement) {
            throw new IllegalStateException("Benchmark update verification failed");
        }
    }

    private static void verifyPopulatedIndex(List<BenchmarkRecord> dataset) {
        IndexedCollection<BenchmarkRecord> records = new ConcurrentIndexedCollection<BenchmarkRecord>();
        records.addAll(dataset);
        records.addIndex(HashIndex.onAttribute(BenchmarkRecord.MANUFACTURER));

        int expected = 0;
        for (BenchmarkRecord record : dataset) {
            if ("Ford".equals(record.manufacturer)) {
                expected++;
            }
        }
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(
                QueryFactory.equal(BenchmarkRecord.MANUFACTURER, "Ford"))) {
            int observed = 0;
            for (BenchmarkRecord record : resultSet) {
                if (!"Ford".equals(record.manufacturer)) {
                    throw new IllegalStateException("Populated hash index returned a mismatched record");
                }
                observed++;
            }
            if (observed != expected || resultSet.size() != expected) {
                throw new IllegalStateException("Populated hash index cardinality verification failed");
            }
        }
    }

    private static BenchmarkRecord findById(
            IndexedCollection<BenchmarkRecord> records,
            String manufacturer,
            int id) {
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(
                QueryFactory.equal(BenchmarkRecord.MANUFACTURER, manufacturer))) {
            BenchmarkRecord match = null;
            for (BenchmarkRecord record : resultSet) {
                if (record.id == id) {
                    if (match != null) {
                        throw new IllegalStateException("Benchmark collection contains duplicate IDs");
                    }
                    match = record;
                }
            }
            return match;
        }
    }

    @Benchmark
    public boolean add(MutationState state) {
        if (!state.next().add(state.added)) {
            throw new IllegalStateException("Benchmark add did not modify the collection");
        }
        return true;
    }

    @Benchmark
    public boolean remove(MutationState state) {
        if (!state.next().remove(state.victim)) {
            throw new IllegalStateException("Benchmark remove did not modify the collection");
        }
        return true;
    }

    @Benchmark
    public boolean update(MutationState state) {
        if (!state.next().update(
                Collections.singleton(state.victim),
                Collections.singleton(state.replacement))) {
            throw new IllegalStateException("Benchmark update did not modify the collection");
        }
        return true;
    }

    @Benchmark
    public int buildHashIndex(IndexBuildState state) {
        IndexedCollection<BenchmarkRecord> records = state.next();
        records.addIndex(HashIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
        int size = records.size();
        if (size != state.datasetSize) {
            throw new IllegalStateException("Benchmark index build changed the collection size");
        }
        return size;
    }
}
