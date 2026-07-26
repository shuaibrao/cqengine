/**
 * Copyright 2026 Shuaib Rao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.cqengine.persistence;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.index.sqlite.RequestScopeConnectionManager;
import com.googlecode.cqengine.persistence.composite.CompositePersistence;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import com.googlecode.cqengine.persistence.offheap.OffHeapPersistence;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RequestScopeCleanupTest {

    static final SimpleAttribute<Item, Integer> ID = new SimpleAttribute<Item, Integer>("id") {
        @Override
        public Integer getValue(Item item, QueryOptions queryOptions) {
            return item.id;
        }
    };

    @Test
    public void diskPersistenceRemovesManagerWhenCloseFails() {
        DiskPersistence<Item, Integer> persistence = DiskPersistence.onPrimaryKey(ID);
        File file = persistence.getFile();
        try {
            assertManagerRemovedAfterFailure(persistence);
        }
        finally {
            persistence.close();
            TestAssertions.assertTrue("Failed to delete temp file: " + file, file.delete());
        }
    }

    @Test
    public void offHeapPersistenceRemovesManagerWhenCloseFails() {
        OffHeapPersistence<Item, Integer> persistence = OffHeapPersistence.onPrimaryKey(ID);
        try {
            assertManagerRemovedAfterFailure(persistence);
        }
        finally {
            persistence.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void compositePersistenceRemovesManagerWhenCloseFails() {
        Persistence<Item, Integer> primary = mock(Persistence.class);
        Persistence<Item, Integer> secondary = mock(Persistence.class);
        when(primary.getPrimaryKeyAttribute()).thenReturn(ID);
        when(secondary.getPrimaryKeyAttribute()).thenReturn(ID);
        CompositePersistence<Item, Integer> persistence = CompositePersistence.of(primary, secondary);

        assertManagerRemovedAfterFailure(persistence);
    }

    private static void assertManagerRemovedAfterFailure(Persistence<Item, Integer> persistence) {
        QueryOptions queryOptions = new QueryOptions();
        ThrowingConnectionManager manager = new ThrowingConnectionManager(persistence);
        queryOptions.put(ConnectionManager.class, manager);

        try {
            persistence.closeRequestScopeResources(queryOptions, RequestScopeTransactionOutcome.ROLLBACK);
            TestAssertions.fail("Expected close to fail");
        }
        catch (IllegalStateException expected) {
            TestAssertions.assertEquals("injected close failure", expected.getMessage());
        }

        TestAssertions.assertEquals(1, manager.closeCalls);
        TestAssertions.assertNull(queryOptions.get(ConnectionManager.class));
    }

    static class ThrowingConnectionManager extends RequestScopeConnectionManager {
        int closeCalls;

        ThrowingConnectionManager(Persistence<?, ?> persistence) {
            super(persistence);
        }

        @Override
        public synchronized void close(RequestScopeTransactionOutcome outcome) {
            closeCalls++;
            throw new IllegalStateException("injected close failure");
        }
    }

    public static class Item {
        int id;
    }
}
