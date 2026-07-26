// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.persistence.support;

import com.googlecode.cqengine.index.support.CloseableIterator;
import com.googlecode.cqengine.query.option.QueryOptions;
import org.junit.jupiter.api.Test;

import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;
import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;
import static com.googlecode.cqengine.testutil.TestAssertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ObjectSetCloseFailureTest {

    @Test
    public void manuallyClosedIteratorIsRemovedFromTracking() {
        ObjectStore<Integer> objectStore = objectStore();
        CloseableIterator<Integer> delegate = iterator();
        when(objectStore.iterator(any(QueryOptions.class))).thenReturn(delegate);
        ObjectSet<Integer> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());

        CloseableIterator<Integer> managedIterator = objectSet.iterator();
        managedIterator.close();
        managedIterator.close();
        objectSet.close();

        verify(delegate, times(1)).close();
    }

    @Test
    public void failedManualCloseIsNotRetriedByObjectSet() {
        ObjectStore<Integer> objectStore = objectStore();
        CloseableIterator<Integer> delegate = iterator();
        RuntimeException failure = new RuntimeException("delegate");
        doThrow(failure).when(delegate).close();
        when(objectStore.iterator(any(QueryOptions.class))).thenReturn(delegate);
        ObjectSet<Integer> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());

        try {
            objectSet.iterator().close();
            fail("Expected close to fail");
        }
        catch (RuntimeException actual) {
            assertSame(failure, actual);
        }
        objectSet.close();

        verify(delegate, times(1)).close();
    }

    @Test
    public void objectSetAttemptsEveryIteratorAndSuppressesLaterFailures() {
        ObjectStore<Integer> objectStore = objectStore();
        CloseableIterator<Integer> first = iterator();
        CloseableIterator<Integer> successful = iterator();
        CloseableIterator<Integer> third = iterator();
        RuntimeException firstFailure = new RuntimeException("first");
        RuntimeException thirdFailure = new RuntimeException("third");
        doThrow(firstFailure).when(first).close();
        doThrow(thirdFailure).when(third).close();
        when(objectStore.iterator(any(QueryOptions.class)))
                .thenReturn(first)
                .thenReturn(successful)
                .thenReturn(third);
        ObjectSet<Integer> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        objectSet.iterator();
        objectSet.iterator();
        objectSet.iterator();

        RuntimeException actual;
        try {
            objectSet.close();
            throw new AssertionError("Expected close to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertTrue(actual == firstFailure || actual == thirdFailure);
        RuntimeException suppressed = actual == firstFailure ? thirdFailure : firstFailure;
        assertArrayEquals(new Throwable[] { suppressed }, actual.getSuppressed());
        objectSet.close();
        verify(first, times(1)).close();
        verify(successful, times(1)).close();
        verify(third, times(1)).close();
    }

    @Test
    public void isEmptyPreservesIterationFailureWhenCloseAlsoFails() {
        ObjectStore<Integer> objectStore = objectStore();
        CloseableIterator<Integer> delegate = iterator();
        RuntimeException iterationFailure = new RuntimeException("hasNext");
        RuntimeException closeFailure = new RuntimeException("close");
        when(delegate.hasNext()).thenThrow(iterationFailure);
        doThrow(closeFailure).when(delegate).close();
        when(objectStore.iterator(any(QueryOptions.class))).thenReturn(delegate);
        ObjectSet<Integer> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());

        RuntimeException actual;
        try {
            objectSet.isEmpty();
            throw new AssertionError("Expected isEmpty to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(iterationFailure, actual);
        assertArrayEquals(new Throwable[] { closeFailure }, actual.getSuppressed());
        verify(delegate, times(1)).close();
    }

    @Test
    public void iteratorCannotBeOpenedAfterObjectSetClose() {
        ObjectStore<Integer> objectStore = objectStore();
        ObjectSet<Integer> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        objectSet.close();

        try {
            objectSet.iterator();
            fail("Expected closed ObjectSet to reject a new iterator");
        }
        catch (IllegalStateException expected) {
            assertEquals("ObjectSet is closed", expected.getMessage());
        }
        verify(objectStore, never()).iterator(any(QueryOptions.class));
    }

    @SuppressWarnings("unchecked")
    static ObjectStore<Integer> objectStore() {
        return mock(ObjectStore.class);
    }

    @SuppressWarnings("unchecked")
    static CloseableIterator<Integer> iterator() {
        return mock(CloseableIterator.class);
    }
}
