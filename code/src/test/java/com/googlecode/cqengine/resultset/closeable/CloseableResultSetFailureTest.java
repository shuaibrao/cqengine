// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.resultset.closeable;

import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.googlecode.cqengine.query.QueryFactory.all;
import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;
import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;
import static com.googlecode.cqengine.testutil.TestAssertions.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class CloseableResultSetFailureTest {

    final Query<Integer> query = all(Integer.class);
    final QueryOptions queryOptions = noQueryOptions();

    @Test
    public void closeAttemptsDelegateAndAdditionalCleanupOnce() {
        ResultSet<Integer> delegate = resultSet();
        RuntimeException delegateFailure = new RuntimeException("delegate");
        RuntimeException cleanupFailure = new RuntimeException("cleanup");
        AtomicInteger cleanupCalls = new AtomicInteger();
        doThrow(delegateFailure).when(delegate).close();

        CloseableResultSet<Integer> resultSet = new CloseableResultSet<Integer>(
                delegate, query, queryOptions, () -> {
                    cleanupCalls.incrementAndGet();
                    throw cleanupFailure;
                });

        RuntimeException actual = closeExpectingFailure(resultSet);

        assertSame(delegateFailure, actual);
        assertArrayEquals(new Throwable[] { cleanupFailure }, actual.getSuppressed());
        assertTrue(resultSet.closed);
        assertEquals(1, cleanupCalls.get());
        verify(delegate, times(1)).close();

        resultSet.close();
        assertEquals(1, cleanupCalls.get());
        verify(delegate, times(1)).close();
        assertClosed(resultSet);
    }

    @Test
    public void checkedAdditionalCleanupFailureRetainsItsCause() {
        ResultSet<Integer> delegate = resultSet();
        IOException checkedFailure = new IOException("checked cleanup");
        Closeable failingCleanup = () -> {
            throw checkedFailure;
        };
        CloseableResultSet<Integer> resultSet = new CloseableResultSet<Integer>(
                delegate, query, queryOptions, failingCleanup);

        RuntimeException actual = closeExpectingFailure(resultSet);

        assertTrue(actual instanceof IllegalStateException);
        assertSame(checkedFailure, actual.getCause());
        resultSet.close();
        verify(delegate, times(1)).close();
    }

    @Test
    public void errorRemainsPrimaryWhenAdditionalCleanupAlsoFails() {
        ResultSet<Integer> delegate = resultSet();
        AssertionError delegateFailure = new AssertionError("delegate error");
        RuntimeException cleanupFailure = new RuntimeException("cleanup");
        doThrow(delegateFailure).when(delegate).close();
        CloseableResultSet<Integer> resultSet = new CloseableResultSet<Integer>(
                delegate, query, queryOptions, () -> {
                    throw cleanupFailure;
                });

        try {
            resultSet.close();
            fail("Expected close to fail");
        }
        catch (AssertionError actual) {
            assertSame(delegateFailure, actual);
            assertArrayEquals(new Throwable[] { cleanupFailure }, actual.getSuppressed());
        }
        resultSet.close();
        verify(delegate, times(1)).close();
    }

    @Test
    public void filteringResultSetIsClosedWhenDelegateCloseFails() {
        ResultSet<Integer> delegate = resultSet();
        RuntimeException delegateFailure = new RuntimeException("delegate");
        doThrow(delegateFailure).when(delegate).close();
        CloseableFilteringResultSet<Integer> resultSet = new CloseableFilteringResultSet<Integer>(
                delegate, query, queryOptions) {
            @Override
            public boolean isValid(Integer object, QueryOptions queryOptions) {
                return true;
            }
        };

        RuntimeException actual = closeExpectingFailure(resultSet);

        assertSame(delegateFailure, actual);
        assertTrue(resultSet.closed);
        resultSet.close();
        verify(delegate, times(1)).close();
        assertClosed(resultSet);
    }

    @SuppressWarnings("unchecked")
    static ResultSet<Integer> resultSet() {
        return mock(ResultSet.class);
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

    static void assertClosed(ResultSet<Integer> resultSet) {
        try {
            resultSet.iterator();
            fail("Expected closed ResultSet to reject access");
        }
        catch (IllegalStateException expected) {
            assertEquals("ResultSet is closed", expected.getMessage());
        }
    }
}
