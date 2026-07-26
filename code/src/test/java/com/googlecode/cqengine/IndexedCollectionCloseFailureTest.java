// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine;

import com.googlecode.cqengine.index.standingquery.StandingQueryIndex;
import com.googlecode.cqengine.index.support.CloseableIterator;
import com.googlecode.cqengine.persistence.Persistence;
import com.googlecode.cqengine.persistence.RequestScopeTransactionOutcome;
import com.googlecode.cqengine.persistence.support.ObjectStore;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import static com.googlecode.cqengine.query.QueryFactory.all;
import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IndexedCollectionCloseFailureTest {

    @Test
    public void concurrentResultSetClosesRequestScopeAfterDelegateFailure() {
        Query<Integer> query = all(Integer.class);
        ResultSet<Integer> delegate = resultSet();
        RuntimeException delegateFailure = new RuntimeException("delegate");
        RuntimeException scopeFailure = new RuntimeException("scope");
        doThrow(delegateFailure).when(delegate).close();
        TrackingConcurrentIndexedCollection collection = new TrackingConcurrentIndexedCollection();
        collection.addIndex(indexReturning(query, delegate));
        collection.trackClose(scopeFailure);

        ResultSet<Integer> resultSet = collection.retrieve(query);
        RuntimeException actual = closeExpectingFailure(resultSet);

        assertSame(delegateFailure, actual);
        assertArrayEquals(new Throwable[] { scopeFailure }, actual.getSuppressed());
        assertEquals(1, collection.trackedCloseCalls.get());
        verify(delegate, times(1)).close();
        resultSet.close();
        assertEquals(1, collection.trackedCloseCalls.get());
        verify(delegate, times(1)).close();
    }

    @Test
    public void concurrentRetrieveFailureClosesRequestScope() {
        Query<Integer> query = all(Integer.class);
        RuntimeException retrieveFailure = new RuntimeException("retrieve");
        RuntimeException scopeFailure = new RuntimeException("scope");
        TrackingConcurrentIndexedCollection collection = new TrackingConcurrentIndexedCollection();
        collection.addIndex(indexThrowing(query, retrieveFailure));
        collection.trackClose(scopeFailure);

        RuntimeException actual;
        try {
            collection.retrieve(query);
            throw new AssertionError("Expected retrieve to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(retrieveFailure, actual);
        assertArrayEquals(new Throwable[] { scopeFailure }, actual.getSuppressed());
        assertEquals(1, collection.trackedCloseCalls.get());
    }

    @Test
    public void transactionalResultSetReleasesReadLockAfterDelegateFailure() {
        Query<Integer> query = all(Integer.class);
        ResultSet<Integer> delegate = resultSet();
        RuntimeException delegateFailure = new RuntimeException("delegate");
        doThrow(delegateFailure).when(delegate).close();
        TransactionalIndexedCollection<Integer> collection =
                new TransactionalIndexedCollection<Integer>(Integer.class);
        collection.addIndex(indexReturning(query, delegate));
        TransactionalIndexedCollection<Integer>.Version version = collection.currentVersion;

        ResultSet<Integer> resultSet = collection.retrieve(query);
        assertSame(delegateFailure, closeExpectingFailure(resultSet));

        assertWriteLockAvailable(version.lock.writeLock());
        resultSet.close();
        assertWriteLockAvailable(version.lock.writeLock());
        verify(delegate, times(1)).close();
    }

    @Test
    public void transactionalRetrieveFailureReleasesReadLock() {
        Query<Integer> query = all(Integer.class);
        RuntimeException retrieveFailure = new RuntimeException("retrieve");
        TransactionalIndexedCollection<Integer> collection =
                new TransactionalIndexedCollection<Integer>(Integer.class);
        collection.addIndex(indexThrowing(query, retrieveFailure));
        TransactionalIndexedCollection<Integer>.Version version = collection.currentVersion;

        try {
            collection.retrieve(query);
            throw new AssertionError("Expected retrieve to fail");
        }
        catch (RuntimeException actual) {
            assertSame(retrieveFailure, actual);
        }

        assertWriteLockAvailable(version.lock.writeLock());
    }

    @Test
    public void transactionalPostRetrieveFailureClosesDelegateAndReleasesReadLock() {
        Query<Integer> query = all(Integer.class);
        ResultSet<Integer> delegate = resultSet();
        RuntimeException constructionFailure = new RuntimeException("exclusion iterator");
        TransactionalIndexedCollection<Integer> collection =
                new TransactionalIndexedCollection<Integer>(Integer.class);
        collection.addIndex(indexReturning(query, delegate));
        collection.currentVersion = collection.new Version(() -> {
            throw constructionFailure;
        });
        TransactionalIndexedCollection<Integer>.Version version = collection.currentVersion;

        try {
            collection.retrieve(query);
            throw new AssertionError("Expected retrieve to fail");
        }
        catch (RuntimeException actual) {
            assertSame(constructionFailure, actual);
        }

        verify(delegate, times(1)).close();
        assertWriteLockAvailable(version.lock.writeLock());
    }

    @Test
    public void concurrentIteratorClosesScopeAfterDelegateFailure() {
        PersistenceFixture fixture = new PersistenceFixture();
        ConcurrentIndexedCollection<Integer> collection =
                new ConcurrentIndexedCollection<Integer>(fixture.persistence);
        assertIteratorCleanup(collection, fixture);
    }

    @Test
    public void objectLockingIteratorClosesScopeAfterDelegateFailure() {
        PersistenceFixture fixture = new PersistenceFixture();
        ObjectLockingIndexedCollection<Integer> collection =
                new ObjectLockingIndexedCollection<Integer>(fixture.persistence);
        assertIteratorCleanup(collection, fixture);
    }

    @Test
    public void concurrentIteratorCreationFailureClosesScope() {
        PersistenceFixture fixture = new PersistenceFixture();
        assertIteratorCreationCleanup(new ConcurrentIndexedCollection<Integer>(fixture.persistence), fixture);
    }

    @Test
    public void objectLockingIteratorCreationFailureClosesScope() {
        PersistenceFixture fixture = new PersistenceFixture();
        assertIteratorCreationCleanup(new ObjectLockingIndexedCollection<Integer>(fixture.persistence), fixture);
    }

    @Test
    public void concurrentRemoveAfterExhaustionCloseFailureUsesNewScope() {
        PersistenceFixture fixture = new PersistenceFixture();
        assertRemoveAfterExhaustionCloseFailure(
                new ConcurrentIndexedCollection<Integer>(fixture.persistence), fixture);
    }

    @Test
    public void objectLockingRemoveAfterExhaustionCloseFailureUsesNewScope() {
        PersistenceFixture fixture = new PersistenceFixture();
        assertRemoveAfterExhaustionCloseFailure(
                new ObjectLockingIndexedCollection<Integer>(fixture.persistence), fixture);
    }

    @Test
    public void failedIteratorInvokesLegacyCloseHookWithRollbackOutcome() {
        PersistenceFixture fixture = new PersistenceFixture();
        HookTrackingCollection collection = new HookTrackingCollection(fixture.persistence);
        clearInvocations(fixture.persistence, fixture.objectStore);
        CloseableIterator<Integer> delegate = iterator();
        RuntimeException delegateFailure = new RuntimeException("delegate");
        doThrow(delegateFailure).when(delegate).close();
        when(fixture.objectStore.iterator(any(QueryOptions.class))).thenReturn(delegate);
        collection.tracking = true;

        CloseableIterator<Integer> iterator = collection.iterator();
        try {
            iterator.close();
            throw new AssertionError("Expected close to fail");
        }
        catch (RuntimeException actual) {
            assertSame(delegateFailure, actual);
        }

        assertEquals(1, collection.trackedCloseCalls.get());
        verify(fixture.persistence, times(1)).closeRequestScopeResources(
                any(QueryOptions.class), eq(RequestScopeTransactionOutcome.ROLLBACK));
    }

    static void assertIteratorCleanup(ConcurrentIndexedCollection<Integer> collection,
                                      PersistenceFixture fixture) {
        clearInvocations(fixture.persistence, fixture.objectStore);
        CloseableIterator<Integer> delegate = iterator();
        RuntimeException delegateFailure = new RuntimeException("delegate");
        RuntimeException scopeFailure = new RuntimeException("scope");
        doThrow(delegateFailure).when(delegate).close();
        doThrow(scopeFailure).when(fixture.persistence).closeRequestScopeResources(
                any(QueryOptions.class), eq(RequestScopeTransactionOutcome.ROLLBACK));
        when(fixture.objectStore.iterator(any(QueryOptions.class))).thenReturn(delegate);

        CloseableIterator<Integer> iterator = collection.iterator();
        RuntimeException actual;
        try {
            iterator.close();
            throw new AssertionError("Expected close to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(delegateFailure, actual);
        assertArrayEquals(new Throwable[] { scopeFailure }, actual.getSuppressed());
        iterator.close();
        verify(delegate, times(1)).close();
        verify(fixture.persistence, times(1)).closeRequestScopeResources(
                any(QueryOptions.class), eq(RequestScopeTransactionOutcome.ROLLBACK));
    }

    static void assertIteratorCreationCleanup(ConcurrentIndexedCollection<Integer> collection,
                                              PersistenceFixture fixture) {
        clearInvocations(fixture.persistence, fixture.objectStore);
        RuntimeException creationFailure = new RuntimeException("iterator creation");
        RuntimeException scopeFailure = new RuntimeException("scope");
        when(fixture.objectStore.iterator(any(QueryOptions.class))).thenThrow(creationFailure);
        doThrow(scopeFailure).when(fixture.persistence).closeRequestScopeResources(
                any(QueryOptions.class), eq(RequestScopeTransactionOutcome.ROLLBACK));

        try {
            collection.iterator();
            throw new AssertionError("Expected iterator creation to fail");
        }
        catch (RuntimeException actual) {
            assertSame(creationFailure, actual);
            assertArrayEquals(new Throwable[] { scopeFailure }, actual.getSuppressed());
        }

        verify(fixture.persistence, times(1)).closeRequestScopeResources(
                any(QueryOptions.class), eq(RequestScopeTransactionOutcome.ROLLBACK));
    }

    static void assertRemoveAfterExhaustionCloseFailure(ConcurrentIndexedCollection<Integer> collection,
                                                        PersistenceFixture fixture) {
        clearInvocations(fixture.persistence, fixture.objectStore);
        CloseableIterator<Integer> delegate = iterator();
        RuntimeException closeFailure = new RuntimeException("delegate close");
        when(fixture.objectStore.iterator(any(QueryOptions.class))).thenReturn(delegate);
        when(delegate.next()).thenReturn(1);
        when(delegate.hasNext()).thenReturn(false);
        doThrow(closeFailure).when(delegate).close();
        when(fixture.objectStore.remove(eq(1), any(QueryOptions.class))).thenReturn(true);

        CloseableIterator<Integer> iterator = collection.iterator();
        assertEquals(Integer.valueOf(1), iterator.next());
        try {
            iterator.hasNext();
            throw new AssertionError("Expected exhaustion cleanup to fail");
        }
        catch (RuntimeException actual) {
            assertSame(closeFailure, actual);
        }

        iterator.remove();

        verify(fixture.objectStore, times(1)).remove(eq(1), any(QueryOptions.class));
        verify(delegate, times(1)).close();
    }

    static void assertWriteLockAvailable(Lock writeLock) {
        assertTrue("Write lock should be available after ResultSet cleanup", writeLock.tryLock());
        writeLock.unlock();
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

    static StandingQueryIndex<Integer> indexReturning(Query<Integer> query, final ResultSet<Integer> resultSet) {
        return new StandingQueryIndex<Integer>(query) {
            @Override
            public ResultSet<Integer> retrieve(Query<Integer> query, QueryOptions queryOptions) {
                return resultSet;
            }
        };
    }

    static StandingQueryIndex<Integer> indexThrowing(Query<Integer> query, final RuntimeException failure) {
        return new StandingQueryIndex<Integer>(query) {
            @Override
            public ResultSet<Integer> retrieve(Query<Integer> query, QueryOptions queryOptions) {
                throw failure;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static ResultSet<Integer> resultSet() {
        ResultSet<Integer> resultSet = mock(ResultSet.class);
        when(resultSet.getRetrievalCost()).thenReturn(1);
        when(resultSet.getMergeCost()).thenReturn(0);
        return resultSet;
    }

    @SuppressWarnings("unchecked")
    static CloseableIterator<Integer> iterator() {
        return mock(CloseableIterator.class);
    }

    static class TrackingConcurrentIndexedCollection extends ConcurrentIndexedCollection<Integer> {
        final AtomicInteger trackedCloseCalls = new AtomicInteger();
        boolean tracking;
        RuntimeException closeFailure;

        void trackClose(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
            this.tracking = true;
        }

        @Override
        protected void closeRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
            if (!tracking) {
                super.closeRequestScopeResourcesIfNecessary(queryOptions);
                return;
            }
            trackedCloseCalls.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    static class HookTrackingCollection extends ConcurrentIndexedCollection<Integer> {
        final AtomicInteger trackedCloseCalls = new AtomicInteger();
        boolean tracking;

        HookTrackingCollection(Persistence<Integer, Integer> persistence) {
            super(persistence);
        }

        @Override
        protected void closeRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
            if (tracking) {
                trackedCloseCalls.incrementAndGet();
            }
            super.closeRequestScopeResourcesIfNecessary(queryOptions);
        }
    }

    static class PersistenceFixture {
        final Persistence<Integer, Integer> persistence;
        final ObjectStore<Integer> objectStore;

        @SuppressWarnings("unchecked")
        PersistenceFixture() {
            persistence = mock(Persistence.class);
            objectStore = mock(ObjectStore.class);
            when(persistence.createObjectStore()).thenReturn(objectStore);
        }
    }
}
