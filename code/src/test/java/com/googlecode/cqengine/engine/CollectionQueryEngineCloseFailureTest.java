// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.engine;

import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.standingquery.StandingQueryIndex;
import com.googlecode.cqengine.index.support.CloseableRequestResources;
import com.googlecode.cqengine.persistence.Persistence;
import com.googlecode.cqengine.persistence.onheap.OnHeapPersistence;
import com.googlecode.cqengine.persistence.support.ConcurrentOnHeapObjectStore;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.query.option.EngineFlags;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static com.googlecode.cqengine.query.QueryFactory.all;
import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertNull;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CollectionQueryEngineCloseFailureTest {

    @Test
    public void lowestCostSelectionClosesSupersededAndLosingResults() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = queryOptions();
        ResultSet<Integer> first = resultSet(20);
        ResultSet<Integer> best = resultSet(10);
        ResultSet<Integer> last = resultSet(30);
        CollectionQueryEngine<Integer> engine = engine(queryOptions);

        ResultSet<Integer> selected = engine.retrieveFromLowestCostIndex(
                query,
                queryOptions,
                Arrays.asList(
                        candidateIndex(query, queryOptions, first),
                        candidateIndex(query, queryOptions, best),
                        candidateIndex(query, queryOptions, last)));

        assertSame(best, selected);
        verify(first, times(1)).close();
        verify(last, times(1)).close();
        verify(best, never()).close();
        selected.close();
        verify(best, times(1)).close();
    }

    @Test
    public void lowestCostSelectionClosesOwnedResultsWhenCostEvaluationFails() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = queryOptions();
        ResultSet<Integer> first = resultSet(20);
        ResultSet<Integer> failing = resultSet(10);
        RuntimeException costFailure = new RuntimeException("cost");
        RuntimeException candidateCloseFailure = new RuntimeException("candidate close");
        RuntimeException firstCloseFailure = new RuntimeException("first close");
        when(failing.getRetrievalCost()).thenThrow(costFailure);
        doThrow(candidateCloseFailure).when(failing).close();
        doThrow(firstCloseFailure).when(first).close();
        CollectionQueryEngine<Integer> engine = engine(queryOptions);

        RuntimeException actual;
        try {
            engine.retrieveFromLowestCostIndex(
                    query,
                    queryOptions,
                    Arrays.asList(
                            candidateIndex(query, queryOptions, first),
                            candidateIndex(query, queryOptions, failing)));
            throw new AssertionError("Expected cost evaluation to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(costFailure, actual);
        assertArrayEquals(
                new Throwable[] {candidateCloseFailure, firstCloseFailure}, actual.getSuppressed());
        verify(first, times(1)).close();
        verify(failing, times(1)).close();
    }

    @Test
    public void lowestCostSelectionClosesCandidateWhenSupersededCloseFails() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = queryOptions();
        ResultSet<Integer> first = resultSet(20);
        ResultSet<Integer> candidate = resultSet(10);
        RuntimeException firstCloseFailure = new RuntimeException("first close");
        RuntimeException candidateCloseFailure = new RuntimeException("candidate close");
        doThrow(firstCloseFailure).when(first).close();
        doThrow(candidateCloseFailure).when(candidate).close();
        CollectionQueryEngine<Integer> engine = engine(queryOptions);

        RuntimeException actual;
        try {
            engine.retrieveFromLowestCostIndex(
                    query,
                    queryOptions,
                    Arrays.asList(
                            candidateIndex(query, queryOptions, first),
                            candidateIndex(query, queryOptions, candidate)));
            throw new AssertionError("Expected displaced result close to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(firstCloseFailure, actual);
        assertArrayEquals(new Throwable[] {candidateCloseFailure}, actual.getSuppressed());
        verify(first, times(1)).close();
        verify(candidate, times(1)).close();
    }

    @Test
    public void lowestCostSelectionClosesWinnerWhenLosingResultCloseFails() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = queryOptions();
        ResultSet<Integer> winner = resultSet(10);
        ResultSet<Integer> loser = resultSet(20);
        RuntimeException loserCloseFailure = new RuntimeException("loser close");
        RuntimeException winnerCloseFailure = new RuntimeException("winner close");
        doThrow(loserCloseFailure).when(loser).close();
        doThrow(winnerCloseFailure).when(winner).close();
        CollectionQueryEngine<Integer> engine = engine(queryOptions);

        RuntimeException actual;
        try {
            engine.retrieveFromLowestCostIndex(
                    query,
                    queryOptions,
                    Arrays.asList(
                            candidateIndex(query, queryOptions, winner),
                            candidateIndex(query, queryOptions, loser)));
            throw new AssertionError("Expected losing result close to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(loserCloseFailure, actual);
        assertArrayEquals(new Throwable[] {winnerCloseFailure}, actual.getSuppressed());
        verify(winner, times(1)).close();
        verify(loser, times(1)).close();
    }

    @Test
    public void lowestCostSelectionClosesRetainedResultWhenLaterRetrieveFails() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = queryOptions();
        ResultSet<Integer> first = resultSet(20);
        RuntimeException retrieveFailure = new RuntimeException("retrieve");
        Index<Integer> failingIndex = mockIndex();
        when(failingIndex.supportsQuery(same(query), same(queryOptions))).thenReturn(true);
        when(failingIndex.retrieve(same(query), same(queryOptions))).thenThrow(retrieveFailure);
        CollectionQueryEngine<Integer> engine = engine(queryOptions);

        RuntimeException actual;
        try {
            engine.retrieveFromLowestCostIndex(
                    query,
                    queryOptions,
                    Arrays.asList(candidateIndex(query, queryOptions, first), failingIndex));
            throw new AssertionError("Expected retrieval to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(retrieveFailure, actual);
        verify(first, times(1)).close();
    }

    @Test
    public void lowestCostSelectionClosesRetainedResultWhenLaterSupportCheckFails() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = queryOptions();
        ResultSet<Integer> first = resultSet(20);
        RuntimeException supportFailure = new RuntimeException("supports");
        RuntimeException closeFailure = new RuntimeException("close");
        doThrow(closeFailure).when(first).close();
        Index<Integer> failingIndex = mockIndex();
        when(failingIndex.supportsQuery(same(query), same(queryOptions))).thenThrow(supportFailure);
        CollectionQueryEngine<Integer> engine = engine(queryOptions);

        RuntimeException actual;
        try {
            engine.retrieveFromLowestCostIndex(
                    query,
                    queryOptions,
                    Arrays.asList(candidateIndex(query, queryOptions, first), failingIndex));
            throw new AssertionError("Expected support check to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(supportFailure, actual);
        assertArrayEquals(new Throwable[] {closeFailure}, actual.getSuppressed());
        verify(first, times(1)).close();
    }

    @Test
    public void cardinalityProbeClosesOnlyItsResourceGroups() {
        QueryOptions queryOptions = queryOptions();
        CloseableRequestResources resources = CloseableRequestResources.forQueryOptions(queryOptions);
        AtomicInteger sentinelCloseCalls = new AtomicInteger();
        AtomicInteger firstProbeCloseCalls = new AtomicInteger();
        AtomicInteger secondProbeCloseCalls = new AtomicInteger();
        resources.add(sentinelCloseCalls::incrementAndGet);
        ResultSet<Integer> first = groupBackedResultSet(resources, firstProbeCloseCalls, 3);
        ResultSet<Integer> second = groupBackedResultSet(resources, secondProbeCloseCalls, 5);

        assertEquals(3, CollectionQueryEngine.getMergeCostAndClose(first));
        assertEquals(5, CollectionQueryEngine.getMergeCostAndClose(second));

        assertEquals(1, firstProbeCloseCalls.get());
        assertEquals(1, secondProbeCloseCalls.get());
        assertEquals(0, sentinelCloseCalls.get());
        resources.close();
        assertEquals(1, sentinelCloseCalls.get());
    }

    @Test
    public void indexOrderingFilterClosesUnregisteredResultWhenCostEvaluationFails() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = QueryFactory.queryOptions(
                QueryFactory.enableFlags(EngineFlags.PREFER_INDEX_MERGE_STRATEGY));
        ResultSet<Integer> results = resultSet();
        RuntimeException costFailure = new RuntimeException("cost");
        RuntimeException closeFailure = new RuntimeException("close");
        when(results.getRetrievalCost()).thenThrow(costFailure);
        doThrow(closeFailure).when(results).close();
        CollectionQueryEngine<Integer> engine = new CollectionQueryEngine<Integer>() {
            @Override
            ResultSet<Integer> retrieveWithoutIndexOrdering(
                    Query<Integer> query, QueryOptions queryOptions,
                    com.googlecode.cqengine.query.option.OrderByOption<Integer> orderByOption) {
                return results;
            }
        };

        RuntimeException actual;
        try {
            engine.filterIndexOrderingCandidateResults(
                    java.util.Collections.<Integer>emptyList().iterator(), query, queryOptions);
            throw new AssertionError("Expected cost evaluation to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(costFailure, actual);
        assertArrayEquals(new Throwable[] {closeFailure}, actual.getSuppressed());
        verify(results, times(1)).close();
    }

    @Test
    public void resultCloseAttemptsRequestResourcesAfterDelegateFailure() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = queryOptions();
        ResultSet<Integer> delegate = resultSet();
        RuntimeException delegateFailure = new RuntimeException("delegate");
        RuntimeException resourceFailure = new RuntimeException("resource");
        AtomicInteger resourceCloseCalls = new AtomicInteger();
        doThrow(delegateFailure).when(delegate).close();
        Closeable resource = () -> {
            resourceCloseCalls.incrementAndGet();
            throw resourceFailure;
        };
        CollectionQueryEngine<Integer> engine = engine(queryOptions);
        engine.addIndex(indexReturning(query, delegate, resource), queryOptions);

        ResultSet<Integer> resultSet = engine.retrieve(query, queryOptions);
        RuntimeException actual = closeExpectingFailure(resultSet);

        assertSame(delegateFailure, actual);
        assertArrayEquals(new Throwable[] { resourceFailure }, actual.getSuppressed());
        assertEquals(1, resourceCloseCalls.get());
        assertNull(queryOptions.get(CloseableRequestResources.class));
        resultSet.close();
        assertEquals(1, resourceCloseCalls.get());
        verify(delegate, times(1)).close();
    }

    @Test
    public void retrieveFailureClosesRequestResourcesAndPreservesPrimaryFailure() {
        Query<Integer> query = all(Integer.class);
        QueryOptions queryOptions = queryOptions();
        RuntimeException retrieveFailure = new RuntimeException("retrieve");
        RuntimeException resourceFailure = new RuntimeException("resource");
        AtomicInteger resourceCloseCalls = new AtomicInteger();
        Closeable resource = () -> {
            resourceCloseCalls.incrementAndGet();
            throw resourceFailure;
        };
        CollectionQueryEngine<Integer> engine = engine(queryOptions);
        engine.addIndex(indexThrowing(query, retrieveFailure, resource), queryOptions);

        RuntimeException actual;
        try {
            engine.retrieve(query, queryOptions);
            throw new AssertionError("Expected retrieve to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(retrieveFailure, actual);
        assertArrayEquals(new Throwable[] { resourceFailure }, actual.getSuppressed());
        assertEquals(1, resourceCloseCalls.get());
        assertNull(queryOptions.get(CloseableRequestResources.class));
    }

    static CollectionQueryEngine<Integer> engine(QueryOptions queryOptions) {
        CollectionQueryEngine<Integer> engine = new CollectionQueryEngine<Integer>();
        engine.init(new ConcurrentOnHeapObjectStore<Integer>(), queryOptions);
        return engine;
    }

    static QueryOptions queryOptions() {
        QueryOptions queryOptions = new QueryOptions();
        queryOptions.put(Persistence.class, OnHeapPersistence.withoutPrimaryKey());
        return queryOptions;
    }

    static StandingQueryIndex<Integer> indexReturning(Query<Integer> query, final ResultSet<Integer> resultSet,
                                                       final Closeable resource) {
        return new StandingQueryIndex<Integer>(query) {
            @Override
            public ResultSet<Integer> retrieve(Query<Integer> query, QueryOptions queryOptions) {
                CloseableRequestResources.forQueryOptions(queryOptions).add(resource);
                return resultSet;
            }
        };
    }

    static StandingQueryIndex<Integer> indexThrowing(Query<Integer> query, final RuntimeException failure,
                                                      final Closeable resource) {
        return new StandingQueryIndex<Integer>(query) {
            @Override
            public ResultSet<Integer> retrieve(Query<Integer> query, QueryOptions queryOptions) {
                CloseableRequestResources.forQueryOptions(queryOptions).add(resource);
                throw failure;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static ResultSet<Integer> resultSet() {
        return resultSet(1);
    }

    @SuppressWarnings("unchecked")
    static ResultSet<Integer> resultSet(int retrievalCost) {
        ResultSet<Integer> resultSet = mock(ResultSet.class);
        when(resultSet.getRetrievalCost()).thenReturn(retrievalCost);
        when(resultSet.getMergeCost()).thenReturn(0);
        return resultSet;
    }

    static Index<Integer> candidateIndex(
            Query<Integer> query, QueryOptions queryOptions, ResultSet<Integer> resultSet) {
        Index<Integer> index = mockIndex();
        when(index.supportsQuery(same(query), same(queryOptions))).thenReturn(true);
        when(index.retrieve(same(query), same(queryOptions))).thenReturn(resultSet);
        return index;
    }

    @SuppressWarnings("unchecked")
    static Index<Integer> mockIndex() {
        return mock(Index.class);
    }

    @SuppressWarnings("unchecked")
    static ResultSet<Integer> groupBackedResultSet(
            CloseableRequestResources resources, AtomicInteger groupResourceCloseCalls, int mergeCost) {
        CloseableRequestResources.CloseableResourceGroup group = resources.addGroup();
        group.add(groupResourceCloseCalls::incrementAndGet);
        ResultSet<Integer> resultSet = mock(ResultSet.class);
        when(resultSet.getMergeCost()).thenReturn(mergeCost);
        doAnswer(invocation -> {
            group.close();
            return null;
        }).when(resultSet).close();
        return resultSet;
    }

    static RuntimeException closeExpectingFailure(ResultSet<?> resultSet) {
        try {
            resultSet.close();
            throw new AssertionError("Expected close to fail");
        }
        catch (RuntimeException failure) {
            return failure;
        }
    }
}
