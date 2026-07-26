// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.persistence.support.sqlite;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.sqlite.SQLiteIdentityIndex;
import com.googlecode.cqengine.index.sqlite.SQLitePersistence;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.jupiter.api.Test;

import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SQLiteObjectStoreTest {

    static final SimpleAttribute<Item, Integer> ID = new SimpleAttribute<Item, Integer>("id") {
        @Override
        public Integer getValue(Item item, QueryOptions queryOptions) {
            return item.id;
        }
    };

    @Test
    public void eagerSizeAndContainsCloseTheirResultSets() {
        Fixture fixture = fixture();
        ResultSet<Item> sizeResults = resultSetWithSize(2);
        ResultSet<Item> containsResults = resultSetWithSize(1);
        when(fixture.index.retrieve(anyQuery(), same(fixture.queryOptions)))
                .thenReturn(sizeResults)
                .thenReturn(containsResults);

        assertEquals(2, fixture.store.size(fixture.queryOptions));
        assertTrue(fixture.store.contains(new Item(1), fixture.queryOptions));

        verify(sizeResults, times(1)).close();
        verify(containsResults, times(1)).close();
    }

    @Test
    public void eagerOperationFailureRemainsPrimaryWhenCloseAlsoFails() {
        Fixture fixture = fixture();
        ResultSet<Item> results = mockResultSet();
        RuntimeException operationFailure = new RuntimeException("size");
        RuntimeException closeFailure = new RuntimeException("close");
        when(results.size()).thenThrow(operationFailure);
        doThrow(closeFailure).when(results).close();
        when(fixture.index.retrieve(anyQuery(), same(fixture.queryOptions))).thenReturn(results);

        RuntimeException actual;
        try {
            fixture.store.size(fixture.queryOptions);
            throw new AssertionError("Expected size to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(operationFailure, actual);
        assertArrayEquals(new Throwable[] {closeFailure}, actual.getSuppressed());
        verify(results, times(1)).close();
    }

    @Test
    public void iteratorCreationFailureRemainsPrimaryWhenResultCloseAlsoFails() {
        Fixture fixture = fixture();
        ResultSet<Item> results = mockResultSet();
        RuntimeException iteratorFailure = new RuntimeException("iterator");
        RuntimeException closeFailure = new RuntimeException("close");
        when(results.iterator()).thenThrow(iteratorFailure);
        doThrow(closeFailure).when(results).close();
        when(fixture.index.retrieve(anyQuery(), same(fixture.queryOptions))).thenReturn(results);

        RuntimeException actual;
        try {
            fixture.store.iterator(fixture.queryOptions);
            throw new AssertionError("Expected iterator creation to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(iteratorFailure, actual);
        assertArrayEquals(new Throwable[] {closeFailure}, actual.getSuppressed());
        verify(results, times(1)).close();
    }

    @SuppressWarnings("unchecked")
    static Fixture fixture() {
        SQLitePersistence<Item, Integer> persistence = mock(SQLitePersistence.class);
        SQLiteIdentityIndex<Integer, Item> index = mock(SQLiteIdentityIndex.class);
        when(persistence.getPrimaryKeyAttribute()).thenReturn(ID);
        when(persistence.createIdentityIndex()).thenReturn(index);
        return new Fixture(new SQLiteObjectStore<Item, Integer>(persistence), index, new QueryOptions());
    }

    static ResultSet<Item> resultSetWithSize(int size) {
        ResultSet<Item> results = mockResultSet();
        when(results.size()).thenReturn(size);
        return results;
    }

    @SuppressWarnings("unchecked")
    static ResultSet<Item> mockResultSet() {
        return mock(ResultSet.class);
    }

    @SuppressWarnings("unchecked")
    static Query<Item> anyQuery() {
        return any(Query.class);
    }

    static class Fixture {
        final SQLiteObjectStore<Item, Integer> store;
        final SQLiteIdentityIndex<Integer, Item> index;
        final QueryOptions queryOptions;

        Fixture(SQLiteObjectStore<Item, Integer> store, SQLiteIdentityIndex<Integer, Item> index,
                QueryOptions queryOptions) {
            this.store = store;
            this.index = index;
            this.queryOptions = queryOptions;
        }
    }

    static class Item {
        final int id;

        Item(int id) {
            this.id = id;
        }
    }
}
