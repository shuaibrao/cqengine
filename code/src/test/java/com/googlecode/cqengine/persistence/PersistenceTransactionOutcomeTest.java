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

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.ObjectLockingIndexedCollection;
import com.googlecode.cqengine.TransactionalIndexedCollection;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.unique.UniqueIndex;
import com.googlecode.cqengine.index.unique.UniqueIndex.UniqueConstraintViolatedException;
import com.googlecode.cqengine.persistence.composite.CompositePersistence;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import com.googlecode.cqengine.persistence.offheap.OffHeapPersistence;
import com.googlecode.cqengine.persistence.onheap.OnHeapPersistence;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;

import java.io.File;

public class PersistenceTransactionOutcomeTest {

    @Test
    public void objectLockingCollectionRollsBackFailedOffHeapAdd() {
        OffHeapPersistence<Item, Integer> persistence = OffHeapPersistence.onPrimaryKey(Item.ID);
        try {
            IndexedCollection<Item> items = new ObjectLockingIndexedCollection<Item>(persistence);
            assertFailedUniqueAddLeavesOneItem(items);
        }
        finally {
            persistence.close();
        }
    }

    @Test
    public void transactionalCollectionRollsBackFailedCompositeAdd() {
        DiskPersistence<Item, Integer> diskPersistence = DiskPersistence.onPrimaryKey(Item.ID);
        File file = diskPersistence.getFile();
        try {
            CompositePersistence<Item, Integer> compositePersistence = CompositePersistence.of(
                    diskPersistence,
                    OnHeapPersistence.onPrimaryKey(Item.ID));
            IndexedCollection<Item> items = new TransactionalIndexedCollection<Item>(Item.class, compositePersistence);
            assertFailedUniqueAddLeavesOneItem(items);
            diskPersistence.close();

            DiskPersistence<Item, Integer> reopenedPersistence = DiskPersistence.onPrimaryKeyInFile(Item.ID, file);
            try {
                TestAssertions.assertEquals(1, new ObjectLockingIndexedCollection<Item>(reopenedPersistence).size());
            }
            finally {
                reopenedPersistence.close();
            }
        }
        finally {
            diskPersistence.close();
            TestAssertions.assertTrue("Failed to delete temp file: " + file, file.delete());
        }
    }

    private static void assertFailedUniqueAddLeavesOneItem(IndexedCollection<Item> items) {
        items.addIndex(UniqueIndex.onAttribute(Item.MANUFACTURER));
        TestAssertions.assertTrue(items.add(new Item(1, "Ford")));
        try {
            items.add(new Item(2, "Ford"));
            TestAssertions.fail("Expected the unique index to reject the duplicate manufacturer");
        }
        catch (UniqueConstraintViolatedException expected) {
        }
        TestAssertions.assertEquals(1, items.size());
    }

    public static class Item {
        static final SimpleAttribute<Item, Integer> ID = new SimpleAttribute<Item, Integer>("id") {
            @Override
            public Integer getValue(Item item, QueryOptions queryOptions) {
                return item.id;
            }
        };

        static final SimpleAttribute<Item, String> MANUFACTURER = new SimpleAttribute<Item, String>("manufacturer") {
            @Override
            public String getValue(Item item, QueryOptions queryOptions) {
                return item.manufacturer;
            }
        };

        int id;
        String manufacturer;

        public Item() {
        }

        Item(int id, String manufacturer) {
            this.id = id;
            this.manufacturer = manufacturer;
        }
    }
}
