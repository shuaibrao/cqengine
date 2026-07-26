// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.benchmark;

import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.resultset.ResultSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.Collections;
import java.util.Set;

import static com.googlecode.cqengine.query.QueryFactory.equal;

public class PersistenceLifecycleBenchmark {

    @State(Scope.Thread)
    public static class PersistenceState {

        @Param({"ON_HEAP", "OFF_HEAP", "DISK_WAL"})
        public PersistenceMode persistenceMode;

        @Param({"10000"})
        public int datasetSize;

        PersistenceBenchmarkFixture fixture;
        Query<BenchmarkRecord> pointQuery;
        Query<BenchmarkRecord> secondaryQuery;
        Set<BenchmarkRecord> current;
        Set<BenchmarkRecord> replacement;

        @Setup(Level.Trial)
        public void setUp() {
            fixture = PersistenceBenchmarkFixture.create(persistenceMode, datasetSize);
            int id = datasetSize / 2;
            BenchmarkRecord original = BenchmarkRecord.create(id);
            BenchmarkRecord changed = new BenchmarkRecord(
                    original.id, original.manufacturer, original.model + " changed", original.price);
            pointQuery = equal(BenchmarkRecord.ID, id);
            secondaryQuery = equal(BenchmarkRecord.MANUFACTURER, "Ford");
            current = Collections.singleton(original);
            replacement = Collections.singleton(changed);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            fixture.verifyAndClose(datasetSize);
        }
    }

    @Benchmark
    public int pointLookupAndClose(PersistenceState state) {
        try (ResultSet<BenchmarkRecord> resultSet = state.fixture.records.retrieve(state.pointQuery)) {
            int count = 0;
            int id = -1;
            for (BenchmarkRecord record : resultSet) {
                count++;
                id = record.id;
            }
            if (count != 1 || id != state.datasetSize / 2) {
                throw new IllegalStateException("Point lookup did not return the expected record");
            }
            return id;
        }
    }

    @Benchmark
    public long secondaryIndexFullIterationAndClose(PersistenceState state) {
        long checksum = 0;
        int count = 0;
        try (ResultSet<BenchmarkRecord> resultSet = state.fixture.records.retrieve(state.secondaryQuery)) {
            for (BenchmarkRecord record : resultSet) {
                checksum += record.id;
                count++;
            }
        }
        if (count != state.fixture.expectedFordCount) {
            throw new IllegalStateException("Secondary query has the wrong cardinality");
        }
        return checksum;
    }

    @Benchmark
    public int secondaryIndexSizeAndClose(PersistenceState state) {
        try (ResultSet<BenchmarkRecord> resultSet = state.fixture.records.retrieve(state.secondaryQuery)) {
            int size = resultSet.size();
            if (size != state.fixture.expectedFordCount) {
                throw new IllegalStateException("Secondary query has the wrong size");
            }
            return size;
        }
    }

    @Benchmark
    public boolean replace(PersistenceState state) {
        if (!state.fixture.records.update(state.current, state.replacement)) {
            throw new IllegalStateException("Persistence replacement did not modify the collection");
        }
        Set<BenchmarkRecord> previous = state.current;
        state.current = state.replacement;
        state.replacement = previous;
        return true;
    }
}
