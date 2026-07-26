// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.AttributeIndex;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.sqlite.support.DBUtils;
import com.googlecode.cqengine.index.standingquery.StandingQueryIndex;
import com.googlecode.cqengine.index.support.indextype.OnHeapTypeIndex;
import com.googlecode.cqengine.persistence.Persistence;
import com.googlecode.cqengine.persistence.composite.CompositePersistence;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import com.googlecode.cqengine.persistence.onheap.OnHeapPersistence;
import com.googlecode.cqengine.persistence.support.ObjectSet;
import com.googlecode.cqengine.persistence.support.ObjectStore;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.resultset.common.WrappedResultSet;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.in;
import static com.googlecode.cqengine.query.QueryFactory.not;
import static com.googlecode.cqengine.query.QueryFactory.selfAttribute;
import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;
import static com.googlecode.cqengine.testutil.TestAssertions.fail;

public class TransactionalIndexedCollectionFailureRecoveryTest {

    @Test
    public void failedAddRestoresVisibilityAfterRollbackCloseFailure() {
        DiskPersistence<Item, Integer> disk = DiskPersistence.onPrimaryKey(Item.ID);
        File file = disk.getFile();
        FailingIndex failingIndex = new FailingIndex();
        CompositePersistence<Item, Integer> persistence = CompositePersistence.of(
                disk, OnHeapPersistence.onPrimaryKey(Item.ID));
        CloseFailingTransactionalCollection items =
                new CloseFailingTransactionalCollection(persistence);
        try {
            items.addIndex(failingIndex);
            items.add(new Item(1));
            failingIndex.failAdds = true;
            RuntimeException rollbackCloseFailure = new RuntimeException("injected rollback close failure");
            items.closeFailure = rollbackCloseFailure;

            RuntimeException actual = addExpectingFailure(items, new Item(1));

            assertEquals("injected add failure", actual.getMessage());
            assertArrayEquals(new Throwable[] { rollbackCloseFailure }, actual.getSuppressed());
            items.closeFailure = null;
            assertEquals(1, countFromIndependentConnection(disk));
            assertVisible(items, 1);
        }
        finally {
            disk.close();
            assertTrue(file.delete());
        }
    }

    @Test
    public void failedRemoveRestoresVisibilityOfRolledBackObject() {
        DiskPersistence<Item, Integer> disk = DiskPersistence.onPrimaryKey(Item.ID);
        File file = disk.getFile();
        FailingIndex failingIndex = new FailingIndex();
        CompositePersistence<Item, Integer> persistence = CompositePersistence.of(
                disk, OnHeapPersistence.onPrimaryKey(Item.ID));
        try {
            TransactionalIndexedCollection<Item> items =
                    new TransactionalIndexedCollection<Item>(Item.class, persistence);
            items.addIndex(failingIndex);
            Item item = new Item(1);
            items.add(item);
            failingIndex.failRemoves = true;

            try {
                items.remove(item);
                fail("Expected remove to fail");
            }
            catch (IllegalStateException expected) {
                assertEquals("injected remove failure", expected.getMessage());
            }

            assertEquals(1, countFromIndependentConnection(disk));
            assertVisible(items, 1);
        }
        finally {
            disk.close();
            assertTrue(file.delete());
        }
    }

    @Test
    public void retainAllUsesOneRequestScopeAndClosesInternalResultFirst() {
        CloseFailingTransactionalCollection items = new CloseFailingTransactionalCollection(
                OnHeapPersistence.onPrimaryKey(Item.ID));
        items.add(new Item(1));
        Query<Item> removalQuery = not(in(selfAttribute(Item.class), Collections.<Item>emptySet()));
        AtomicInteger resultCloseCalls = new AtomicInteger();
        items.addIndex(new StandingQueryIndex<Item>(removalQuery) {
            @Override
            public ResultSet<Item> retrieve(Query<Item> query, QueryOptions queryOptions) {
                ResultSet<Item> delegate = super.retrieve(query, queryOptions);
                return new WrappedResultSet<Item>(delegate) {
                    @Override
                    public void close() {
                        resultCloseCalls.incrementAndGet();
                        items.internalResultClosed = true;
                        super.close();
                    }
                };
            }
        });
        items.requestScopeCloseCalls = 0;
        items.requireInternalResultClosed = true;

        assertTrue(items.retainAll(Collections.emptySet()));

        assertEquals(1, resultCloseCalls.get());
        assertEquals(1, items.requestScopeCloseCalls);
    }

    @Test
    public void retainAllPreservesResultCloseFailureAndSuppressesScopeFailure() {
        CloseFailingTransactionalCollection items = new CloseFailingTransactionalCollection(
                OnHeapPersistence.onPrimaryKey(Item.ID));
        items.add(new Item(1));
        Query<Item> removalQuery = not(in(selfAttribute(Item.class), Collections.<Item>emptySet()));
        RuntimeException resultCloseFailure = new RuntimeException("internal result close");
        RuntimeException scopeCloseFailure = new RuntimeException("request scope close");
        AtomicInteger resultCloseCalls = new AtomicInteger();
        items.addIndex(new StandingQueryIndex<Item>(removalQuery) {
            @Override
            public ResultSet<Item> retrieve(Query<Item> query, QueryOptions queryOptions) {
                ResultSet<Item> delegate = super.retrieve(query, queryOptions);
                return new WrappedResultSet<Item>(delegate) {
                    @Override
                    public void close() {
                        resultCloseCalls.incrementAndGet();
                        items.internalResultClosed = true;
                        super.close();
                        throw resultCloseFailure;
                    }
                };
            }
        });
        items.requestScopeCloseCalls = 0;
        items.requireInternalResultClosed = true;
        items.closeFailure = scopeCloseFailure;

        try {
            items.retainAll(Collections.emptySet());
            fail("Expected retainAll cleanup to fail");
        }
        catch (RuntimeException actual) {
            assertSame(resultCloseFailure, actual);
            assertArrayEquals(new Throwable[] { scopeCloseFailure }, actual.getSuppressed());
        }

        assertEquals(1, resultCloseCalls.get());
        assertEquals(1, items.requestScopeCloseCalls);
    }

    static RuntimeException addExpectingFailure(TransactionalIndexedCollection<Item> items, Item item) {
        try {
            items.add(item);
            throw new AssertionError("Expected add to fail");
        }
        catch (RuntimeException failure) {
            return failure;
        }
    }

    static void assertVisible(TransactionalIndexedCollection<Item> items, int id) {
        ResultSet<Item> results = items.retrieve(equal(Item.ID, id));
        try {
            assertEquals(1, results.size());
        }
        finally {
            results.close();
        }
    }

    static int countFromIndependentConnection(DiskPersistence<Item, Integer> persistence) {
        Connection connection = persistence.getConnection(null, new QueryOptions());
        try {
            String table = "\"cqtbl_"
                    + DBUtils.createSQLiteIndexTableNameV2(Item.ID.getAttributeName(), "") + "\"";
            return connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table).getInt(1);
        }
        catch (Exception failure) {
            throw new AssertionError(failure);
        }
        finally {
            DBUtils.closeQuietly(connection);
        }
    }

    static class CloseFailingTransactionalCollection extends TransactionalIndexedCollection<Item> {
        RuntimeException closeFailure;
        boolean requireInternalResultClosed;
        boolean internalResultClosed;
        int requestScopeCloseCalls;

        CloseFailingTransactionalCollection(Persistence<Item, Integer> persistence) {
            super(Item.class, persistence);
        }

        @Override
        protected void closeRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
            if (requireInternalResultClosed && !internalResultClosed) {
                throw new AssertionError("Request scope closed before the internal ResultSet");
            }
            requestScopeCloseCalls++;
            super.closeRequestScopeResourcesIfNecessary(queryOptions);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    static class FailingIndex implements AttributeIndex<Integer, Item>, OnHeapTypeIndex {
        boolean failAdds;
        boolean failRemoves;

        @Override
        public Attribute<Item, Integer> getAttribute() {
            return Item.ID;
        }

        @Override
        public boolean isMutable() {
            return true;
        }

        @Override
        public boolean supportsQuery(Query<Item> query, QueryOptions queryOptions) {
            return false;
        }

        @Override
        public boolean isQuantized() {
            return false;
        }

        @Override
        public ResultSet<Item> retrieve(Query<Item> query, QueryOptions queryOptions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Index<Item> getEffectiveIndex() {
            return this;
        }

        @Override
        public boolean addAll(ObjectSet<Item> objectSet, QueryOptions queryOptions) {
            if (failAdds) {
                throw new IllegalStateException("injected add failure");
            }
            return true;
        }

        @Override
        public boolean removeAll(ObjectSet<Item> objectSet, QueryOptions queryOptions) {
            if (failRemoves) {
                throw new IllegalStateException("injected remove failure");
            }
            return true;
        }

        @Override
        public void clear(QueryOptions queryOptions) {
        }

        @Override
        public void init(ObjectStore<Item> objectStore, QueryOptions queryOptions) {
        }

        @Override
        public void destroy(QueryOptions queryOptions) {
        }
    }

    public static class Item {
        static final SimpleAttribute<Item, Integer> ID = new SimpleAttribute<Item, Integer>("id") {
            @Override
            public Integer getValue(Item item, QueryOptions queryOptions) {
                return item.id;
            }
        };

        int id;

        public Item() {
        }

        Item(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Item && ((Item) other).id == id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }
}
