// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.benchmark;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.disk.DiskIndex;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.offheap.OffHeapIndex;
import com.googlecode.cqengine.index.unique.UniqueIndex;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import com.googlecode.cqengine.persistence.offheap.OffHeapPersistence;
import com.googlecode.cqengine.resultset.ResultSet;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.googlecode.cqengine.query.QueryFactory.equal;

final class PersistenceBenchmarkFixture implements AutoCloseable {

    final IndexedCollection<BenchmarkRecord> records;
    final int expectedFordCount;
    final Set<Integer> expectedFordIds;

    private final Closeable persistence;
    private final Path temporaryDirectory;

    private PersistenceBenchmarkFixture(
            IndexedCollection<BenchmarkRecord> records,
            int expectedFordCount,
            Set<Integer> expectedFordIds,
            Closeable persistence,
            Path temporaryDirectory) {
        this.records = records;
        this.expectedFordCount = expectedFordCount;
        this.expectedFordIds = expectedFordIds;
        this.persistence = persistence;
        this.temporaryDirectory = temporaryDirectory;
    }

    static PersistenceBenchmarkFixture create(PersistenceMode mode, int datasetSize) {
        Closeable persistence = null;
        Path temporaryDirectory = null;
        try {
            IndexedCollection<BenchmarkRecord> records;
            switch (mode) {
                case ON_HEAP:
                    records = new ConcurrentIndexedCollection<BenchmarkRecord>();
                    records.addIndex(UniqueIndex.onAttribute(BenchmarkRecord.ID));
                    records.addIndex(HashIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
                    break;
                case OFF_HEAP:
                    OffHeapPersistence<BenchmarkRecord, Integer> offHeap =
                            OffHeapPersistence.onPrimaryKey(BenchmarkRecord.ID);
                    persistence = offHeap;
                    records = new ConcurrentIndexedCollection<BenchmarkRecord>(offHeap);
                    records.addIndex(OffHeapIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
                    break;
                case DISK_WAL:
                    temporaryDirectory = Files.createTempDirectory("cqengine-jmh-");
                    DiskPersistence<BenchmarkRecord, Integer> disk = DiskPersistence.onPrimaryKeyInFile(
                            BenchmarkRecord.ID, temporaryDirectory.resolve("store.db").toFile());
                    persistence = disk;
                    records = new ConcurrentIndexedCollection<BenchmarkRecord>(disk);
                    records.addIndex(DiskIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown persistence mode: " + mode);
            }

            List<BenchmarkRecord> dataset = BenchmarkRecord.createMany(datasetSize);
            if (!records.addAll(dataset)) {
                throw new IllegalStateException("Benchmark dataset was not added");
            }
            int expectedFordCount = 0;
            Set<Integer> expectedFordIds = new HashSet<Integer>();
            for (BenchmarkRecord record : dataset) {
                if ("Ford".equals(record.manufacturer)) {
                    expectedFordCount++;
                    expectedFordIds.add(record.id);
                }
            }
            PersistenceBenchmarkFixture fixture = new PersistenceBenchmarkFixture(
                    records, expectedFordCount, expectedFordIds, persistence, temporaryDirectory);
            fixture.verify(datasetSize);
            return fixture;
        }
        catch (Throwable failure) {
            try {
                new PersistenceBenchmarkFixture(
                        null, 0, new HashSet<Integer>(), persistence, temporaryDirectory).close();
            }
            catch (Throwable cleanupFailure) {
                if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            throw new IllegalStateException("Failed to create persistence benchmark fixture", failure);
        }
    }

    void verify(int expectedSize) {
        if (records.size() != expectedSize) {
            throw new IllegalStateException("Persistence benchmark collection has the wrong size");
        }
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(
                equal(BenchmarkRecord.MANUFACTURER, "Ford"))) {
            Set<Integer> actualFordIds = new HashSet<Integer>();
            for (BenchmarkRecord record : resultSet) {
                if (!"Ford".equals(record.manufacturer) || !actualFordIds.add(record.id)) {
                    throw new IllegalStateException("Persistence benchmark query returned invalid results");
                }
            }
            if (!actualFordIds.equals(expectedFordIds) || resultSet.size() != expectedFordCount) {
                throw new IllegalStateException("Persistence benchmark query has the wrong cardinality");
            }
        }
    }

    void verifyAndClose(int expectedSize) {
        Throwable failure = null;
        try {
            verify(expectedSize);
        }
        catch (Throwable verificationFailure) {
            failure = verificationFailure;
        }
        try {
            close();
        }
        catch (Throwable closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            }
            else if (failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to verify and close persistence benchmark fixture", failure);
        }
    }

    @Override
    public void close() {
        Throwable failure = null;
        try {
            if (persistence != null) {
                persistence.close();
            }
        }
        catch (Throwable closeFailure) {
            failure = closeFailure;
        }
        try {
            if (temporaryDirectory != null && Files.exists(temporaryDirectory)) {
                try (Stream<Path> paths = Files.walk(temporaryDirectory)) {
                    for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
        catch (Throwable deleteFailure) {
            if (failure == null) {
                failure = deleteFailure;
            }
            else if (failure != deleteFailure) {
                failure.addSuppressed(deleteFailure);
            }
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to close persistence benchmark fixture", failure);
        }
    }
}
