// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.benchmark;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.compound.CompoundIndex;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.index.radix.RadixTreeIndex;
import com.googlecode.cqengine.index.radixinverted.InvertedRadixTreeIndex;
import com.googlecode.cqengine.index.radixreversed.ReversedRadixTreeIndex;
import com.googlecode.cqengine.index.standingquery.StandingQueryIndex;
import com.googlecode.cqengine.index.suffix.SuffixTreeIndex;
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

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@State(Scope.Benchmark)
public class QueryScenarioBenchmark {

    @Param({"10000"})
    public int datasetSize;

    @Param
    public QueryScenario scenario;

    private IndexedCollection<BenchmarkRecord> records;
    private Query<BenchmarkRecord> query;

    @Setup(Level.Trial)
    public void setUp() {
        if (datasetSize < 32) {
            throw new IllegalArgumentException("datasetSize must be at least 32");
        }

        records = new ConcurrentIndexedCollection<BenchmarkRecord>();
        query = configureScenario(records);
        List<BenchmarkRecord> dataset = BenchmarkRecord.createMany(datasetSize);
        if (!records.addAll(dataset) || records.size() != datasetSize) {
            throw new IllegalStateException("Query scenario dataset was not populated completely");
        }
        verifyFixture(dataset);
    }

    private Query<BenchmarkRecord> configureScenario(IndexedCollection<BenchmarkRecord> collection) {
        switch (scenario) {
            case UNIQUE_ZERO:
                collection.addIndex(UniqueIndex.onAttribute(BenchmarkRecord.ID));
                return QueryFactory.equal(BenchmarkRecord.ID, datasetSize + 17);
            case UNIQUE_ONE:
                collection.addIndex(UniqueIndex.onAttribute(BenchmarkRecord.ID));
                return QueryFactory.equal(BenchmarkRecord.ID, datasetSize / 2);
            case HASH_LARGE:
                collection.addIndex(HashIndex.onAttribute(BenchmarkRecord.MANUFACTURER));
                return QueryFactory.equal(BenchmarkRecord.MANUFACTURER, "Ford");
            case NAVIGABLE_SMALL:
                collection.addIndex(NavigableIndex.onAttribute(BenchmarkRecord.PRICE));
                return QueryFactory.between(BenchmarkRecord.PRICE, 2_500, 2_507);
            case COMPOUND_LARGE:
                collection.addIndex(
                        CompoundIndex.onAttributes(BenchmarkRecord.MANUFACTURER, BenchmarkRecord.MODEL));
                return QueryFactory.and(
                        QueryFactory.equal(BenchmarkRecord.MANUFACTURER, "Toyota"),
                        QueryFactory.equal(BenchmarkRecord.MODEL, "Prius"));
            case STANDING_MEDIUM:
                Query<BenchmarkRecord> standingQuery = QueryFactory.and(
                        QueryFactory.equal(BenchmarkRecord.MANUFACTURER, "Toyota"),
                        QueryFactory.lessThan(BenchmarkRecord.ID, datasetSize / 2));
                collection.addIndex(StandingQueryIndex.onQuery(standingQuery));
                return standingQuery;
            case RADIX_LARGE:
                collection.addIndex(RadixTreeIndex.onAttribute(BenchmarkRecord.MODEL));
                return QueryFactory.startsWith(BenchmarkRecord.MODEL, "Fo");
            case REVERSED_RADIX_LARGE:
                collection.addIndex(ReversedRadixTreeIndex.onAttribute(BenchmarkRecord.MODEL));
                return QueryFactory.endsWith(BenchmarkRecord.MODEL, "vic");
            case INVERTED_RADIX_LARGE:
                collection.addIndex(InvertedRadixTreeIndex.onAttribute(BenchmarkRecord.MODEL));
                return QueryFactory.longestPrefix(BenchmarkRecord.MODEL, "Prius-Prime");
            case SUFFIX_LARGE:
                collection.addIndex(SuffixTreeIndex.onAttribute(BenchmarkRecord.MODEL));
                return QueryFactory.contains(BenchmarkRecord.MODEL, "6");
            case FALLBACK_LARGE:
                return QueryFactory.equal(BenchmarkRecord.MODEL, "M6");
            default:
                throw new IllegalStateException("Unknown query scenario: " + scenario);
        }
    }

    private void verifyFixture(List<BenchmarkRecord> dataset) {
        Set<Integer> expectedIds = new HashSet<Integer>();
        for (BenchmarkRecord record : dataset) {
            if (expectedMatch(record)) {
                expectedIds.add(record.id);
            }
        }
        validateCardinality(expectedIds.size());

        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(query)) {
            Set<Integer> actualIds = new HashSet<Integer>();
            for (BenchmarkRecord record : resultSet) {
                if (!actualIds.add(record.id)) {
                    throw new IllegalStateException("Query scenario returned a duplicate id: " + scenario);
                }
            }
            if (!actualIds.equals(expectedIds) || resultSet.size() != expectedIds.size()) {
                throw new IllegalStateException(
                        "Query scenario returned an unexpected result set: " + scenario);
            }

            boolean fallback = scenario == QueryScenario.FALLBACK_LARGE;
            boolean usesFallbackScan = resultSet.getRetrievalCost() == Integer.MAX_VALUE;
            if (fallback != usesFallbackScan) {
                throw new IllegalStateException(
                        "Query scenario did not use its declared index/fallback path: " + scenario);
            }
        }
    }

    private boolean expectedMatch(BenchmarkRecord record) {
        switch (scenario) {
            case UNIQUE_ZERO:
                return false;
            case UNIQUE_ONE:
                return record.id == datasetSize / 2;
            case HASH_LARGE:
                return "Ford".equals(record.manufacturer);
            case NAVIGABLE_SMALL:
                return record.price >= 2_500 && record.price <= 2_507;
            case COMPOUND_LARGE:
                return "Toyota".equals(record.manufacturer) && "Prius".equals(record.model);
            case STANDING_MEDIUM:
                return "Toyota".equals(record.manufacturer) && record.id < datasetSize / 2;
            case RADIX_LARGE:
                return record.model.startsWith("Fo");
            case REVERSED_RADIX_LARGE:
                return record.model.endsWith("vic");
            case INVERTED_RADIX_LARGE:
                return "Prius".equals(record.model);
            case SUFFIX_LARGE:
                return record.model.contains("6");
            case FALLBACK_LARGE:
                return "M6".equals(record.model);
            default:
                throw new IllegalStateException("Unknown query scenario: " + scenario);
        }
    }

    private void validateCardinality(int cardinality) {
        switch (scenario) {
            case UNIQUE_ZERO:
                if (cardinality != 0) {
                    throw new IllegalStateException("Zero-cardinality scenario is not empty");
                }
                break;
            case UNIQUE_ONE:
                if (cardinality != 1) {
                    throw new IllegalStateException("One-cardinality scenario is not unique");
                }
                break;
            case NAVIGABLE_SMALL:
                if (cardinality < 2 || cardinality > 32) {
                    throw new IllegalStateException("Small-cardinality scenario is outside its declared range");
                }
                break;
            case STANDING_MEDIUM:
                if (cardinality < datasetSize / 16 || cardinality >= datasetSize / 5) {
                    throw new IllegalStateException("Medium-cardinality scenario is outside its declared range");
                }
                break;
            default:
                if (cardinality < datasetSize / 5) {
                    throw new IllegalStateException("Large-cardinality scenario is outside its declared range");
                }
        }
    }

    @Benchmark
    public int createResultSetReadCostAndClose() {
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(query)) {
            return resultSet.getRetrievalCost();
        }
    }

    @Benchmark
    public int firstResultAndClose() {
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(query)) {
            Iterator<BenchmarkRecord> iterator = resultSet.iterator();
            return iterator.hasNext() ? iterator.next().id : -1;
        }
    }

    @Benchmark
    public long fullIterationAndClose() {
        long checksum = 0;
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(query)) {
            for (BenchmarkRecord record : resultSet) {
                checksum += record.id;
            }
        }
        return checksum;
    }

    @Benchmark
    public int sizeAndClose() {
        try (ResultSet<BenchmarkRecord> resultSet = records.retrieve(query)) {
            return resultSet.size();
        }
    }
}
