// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.benchmark;

import com.googlecode.cqengine.index.sqlite.SQLiteBusyException;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.resultset.ResultSet;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.ThreadParams;

import java.util.Collections;
import java.util.Set;

import static com.googlecode.cqengine.query.QueryFactory.equal;

public class ConcurrentReadWriteBenchmark {

    @State(Scope.Group)
    public static class CollectionState {

        @Param({"ON_HEAP", "OFF_HEAP", "DISK_WAL"})
        public PersistenceMode persistenceMode;

        @Param({"10000"})
        public int datasetSize;

        PersistenceBenchmarkFixture fixture;
        Query<BenchmarkRecord> readQuery;

        @Setup(Level.Trial)
        public void setUp() {
            fixture = PersistenceBenchmarkFixture.create(persistenceMode, datasetSize);
            readQuery = equal(BenchmarkRecord.MANUFACTURER, "Ford");
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            fixture.verifyAndClose(datasetSize);
        }
    }

    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class WriterCounters {

        public long successfulWrites;
        public long busyFailures;
    }

    @State(Scope.Thread)
    public static class WriterState {

        Set<BenchmarkRecord> current;
        Set<BenchmarkRecord> replacement;

        @Setup(Level.Trial)
        public void setUp(ThreadParams threadParams) {
            int id = threadParams.getSubgroupThreadIndex();
            BenchmarkRecord original = BenchmarkRecord.create(id);
            BenchmarkRecord changed = new BenchmarkRecord(
                    original.id, original.manufacturer, original.model + " changed", original.price);
            current = Collections.singleton(original);
            replacement = Collections.singleton(changed);
        }

        void swap() {
            Set<BenchmarkRecord> previous = current;
            current = replacement;
            replacement = previous;
        }
    }

    static long read(CollectionState state) {
        long checksum = 0;
        try (ResultSet<BenchmarkRecord> resultSet = state.fixture.records.retrieve(state.readQuery)) {
            for (BenchmarkRecord record : resultSet) {
                checksum += record.id;
            }
        }
        return checksum;
    }

    static boolean write(CollectionState state, WriterState writer, WriterCounters counters) {
        try {
            if (!state.fixture.records.update(writer.current, writer.replacement)) {
                throw new IllegalStateException("Concurrent replacement did not modify the collection");
            }
        }
        catch (SQLiteBusyException expectedUnderBoundedWriterContention) {
            counters.busyFailures++;
            return false;
        }
        writer.swap();
        counters.successfulWrites++;
        return true;
    }

    @Benchmark
    @Group("readOnly")
    @GroupThreads(4)
    public long readOnly(CollectionState state) {
        return read(state);
    }

    @Benchmark
    @Group("readHeavy")
    @GroupThreads(3)
    public long readHeavyRead(CollectionState state) {
        return read(state);
    }

    @Benchmark
    @Group("readHeavy")
    @GroupThreads(1)
    public boolean readHeavyWrite(
            CollectionState state,
            WriterState writer,
            WriterCounters counters) {
        return write(state, writer, counters);
    }

    @Benchmark
    @Group("writeHeavy")
    @GroupThreads(1)
    public long writeHeavyRead(CollectionState state) {
        return read(state);
    }

    @Benchmark
    @Group("writeHeavy")
    @GroupThreads(3)
    public boolean writeHeavyWrite(
            CollectionState state,
            WriterState writer,
            WriterCounters counters) {
        return write(state, writer, counters);
    }
}
