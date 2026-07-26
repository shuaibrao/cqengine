// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.resultset.connective;

import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.googlecode.cqengine.query.QueryFactory.all;
import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;
import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConnectiveResultSetCloseFailureTest {

    final Query<Integer> query = all(Integer.class);
    final QueryOptions queryOptions = noQueryOptions();

    @Test
    public void differenceAttemptsBothChildrenOnce() {
        ResultSet<Integer> first = resultSet(1);
        ResultSet<Integer> second = resultSet(2);
        RuntimeException firstFailure = new RuntimeException("first");
        RuntimeException secondFailure = new RuntimeException("second");
        doThrow(firstFailure).when(first).close();
        doThrow(secondFailure).when(second).close();
        ResultSet<Integer> difference = new ResultSetDifference<Integer>(
                first, second, query, queryOptions);

        assertCloseFailure(difference, firstFailure, secondFailure, Arrays.asList(first, second));
    }

    @Test
    public void intersectionAttemptsEveryChildOnce() {
        assertNaryCloseFailure(new NaryFactory() {
            @Override
            public ResultSet<Integer> create(List<ResultSet<Integer>> resultSets) {
                return new ResultSetIntersection<Integer>(resultSets, query, queryOptions, false);
            }
        });
    }

    @Test
    public void unionAttemptsEveryChildOnce() {
        assertNaryCloseFailure(new NaryFactory() {
            @Override
            public ResultSet<Integer> create(List<ResultSet<Integer>> resultSets) {
                return new ResultSetUnion<Integer>(resultSets, query, queryOptions);
            }
        });
    }

    @Test
    public void unionAllAttemptsEveryChildOnce() {
        assertNaryCloseFailure(new NaryFactory() {
            @Override
            public ResultSet<Integer> create(List<ResultSet<Integer>> resultSets) {
                return new ResultSetUnionAll<Integer>(resultSets, query, queryOptions);
            }
        });
    }

    void assertNaryCloseFailure(NaryFactory factory) {
        ResultSet<Integer> first = resultSet(1);
        ResultSet<Integer> successful = resultSet(2);
        ResultSet<Integer> third = resultSet(3);
        RuntimeException firstFailure = new RuntimeException("first");
        RuntimeException thirdFailure = new RuntimeException("third");
        doThrow(firstFailure).when(first).close();
        doThrow(thirdFailure).when(third).close();
        List<ResultSet<Integer>> children = Arrays.asList(first, successful, third);

        assertCloseFailure(factory.create(children), firstFailure, thirdFailure, children);
    }

    static void assertCloseFailure(ResultSet<Integer> resultSet, RuntimeException firstFailure,
                                   RuntimeException laterFailure, List<ResultSet<Integer>> children) {
        RuntimeException actual;
        try {
            resultSet.close();
            throw new AssertionError("Expected close to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(firstFailure, actual);
        assertArrayEquals(new Throwable[] { laterFailure }, actual.getSuppressed());
        resultSet.close();
        for (ResultSet<Integer> child : children) {
            verify(child, times(1)).close();
        }
    }

    @SuppressWarnings("unchecked")
    static ResultSet<Integer> resultSet(int mergeCost) {
        ResultSet<Integer> resultSet = mock(ResultSet.class);
        when(resultSet.getMergeCost()).thenReturn(mergeCost);
        when(resultSet.getRetrievalCost()).thenReturn(mergeCost);
        return resultSet;
    }

    interface NaryFactory {
        ResultSet<Integer> create(List<ResultSet<Integer>> resultSets);
    }
}
