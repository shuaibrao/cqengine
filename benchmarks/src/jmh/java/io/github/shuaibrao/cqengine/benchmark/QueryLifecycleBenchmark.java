// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.benchmark;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.index.unique.UniqueIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.resultset.ResultSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Iterator;
import java.util.List;

@State(Scope.Benchmark)
public class QueryLifecycleBenchmark {

    @Param({"10000"})
    public int datasetSize;

    private IndexedCollection<BenchmarkRecord> records;
    private Query<BenchmarkRecord> indexedQuery;
    private Query<BenchmarkRecord> unindexedQuery;

    @Setup(Level.Trial)
    public void setUp() {
        records = new ConcurrentIndexedCollection<BenchmarkRecord>();
        List<BenchmarkRecord> dataset = BenchmarkRecord.createMany(datasetSize);
        records.addIndex(UniqueIndex.onAttribute(BenchmarkRecord.ID));
        records.addIndex(HashIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
        records.addIndex(NavigableIndex.onAttribute(BenchmarkRecord.PRICE));
        records.addAll(dataset);
        indexedQuery = QueryFactory.and(
                QueryFactory.equal(BenchmarkRecord.MANUFACTURER, "Ford"),
                QueryFactory.between(BenchmarkRecord.PRICE, 4_000, 6_000));
        unindexedQuery = QueryFactory.equal(BenchmarkRecord.MODEL, "Focus");

        int expectedIndexed = 0;
        int expectedUnindexed = 0;
        for (BenchmarkRecord record : dataset) {
            if ("Ford".equals(record.manufacturer) && record.price >= 4_000 && record.price <= 6_000) {
                expectedIndexed++;
            }
            if ("Focus".equals(record.model)) {
                expectedUnindexed++;
            }
        }
        try (ResultSet<BenchmarkRecord> indexed = records.retrieve(indexedQuery);
                ResultSet<BenchmarkRecord> unindexed = records.retrieve(unindexedQuery)) {
            if (indexed.size() != expectedIndexed || unindexed.size() != expectedUnindexed) {
                throw new IllegalStateException("Benchmark query cardinality does not match its dataset");
            }
        }
    }

    @Benchmark
    public Query<BenchmarkRecord> constructCompoundQuery() {
        return QueryFactory.and(
                QueryFactory.equal(BenchmarkRecord.MANUFACTURER, "Ford"),
                QueryFactory.between(BenchmarkRecord.PRICE, 4_000, 6_000));
    }

    @Benchmark
    public int createResultSetReadCostAndClose() {
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(indexedQuery)) {
            return resultSet.getRetrievalCost();
        }
    }

    @Benchmark
    public int firstResultAndClose() {
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(indexedQuery)) {
            Iterator<BenchmarkRecord> iterator = resultSet.iterator();
            return iterator.hasNext() ? iterator.next().id : -1;
        }
    }

    @Benchmark
    public long fullIterationAndClose() {
        long checksum = 0;
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(indexedQuery)) {
            for (BenchmarkRecord record : resultSet) {
                checksum += record.id;
            }
        }
        return checksum;
    }

    @Benchmark
    public int sizeAndClose() {
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(indexedQuery)) {
            return resultSet.size();
        }
    }

    @Benchmark
    public long unindexedFullIterationAndClose() {
        long checksum = 0;
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(unindexedQuery)) {
            for (BenchmarkRecord record : resultSet) {
                checksum += record.id;
            }
        }
        return checksum;
    }
}
