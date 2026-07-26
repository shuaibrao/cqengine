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
package com.googlecode.cqengine.persistence.disk;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.unique.UniqueIndex;
import com.googlecode.cqengine.index.unique.UniqueIndex.UniqueConstraintViolatedException;
import com.googlecode.cqengine.index.sqlite.support.DBQueries;
import com.googlecode.cqengine.index.sqlite.support.DBUtils;
import com.googlecode.cqengine.index.sqlite.support.SQLiteIndexFlags;
import com.googlecode.cqengine.index.support.CloseableIterator;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;

import static com.googlecode.cqengine.query.QueryFactory.enableFlags;
import static com.googlecode.cqengine.query.QueryFactory.queryOptions;

public class DiskTransactionOutcomeTest {

    @Test
    public void failedAddIsRolledBackBeforeReopen() {
        File file = DiskPersistence.createTempFile();
        try {
            DiskPersistence<Item, Integer> persistence = DiskPersistence.onPrimaryKeyInFile(Item.ID, file);
            IndexedCollection<Item> items = new ConcurrentIndexedCollection<Item>(persistence);
            items.addIndex(UniqueIndex.onAttribute(Item.MANUFACTURER));
            items.add(new Item(1, "Ford"));

            try {
                items.add(new Item(2, "Ford"));
                TestAssertions.fail("Expected the unique index to reject the duplicate manufacturer");
            }
            catch (UniqueConstraintViolatedException expected) {
            }
            finally {
                persistence.close();
            }

            DiskPersistence<Item, Integer> reopenedPersistence = DiskPersistence.onPrimaryKeyInFile(Item.ID, file);
            try {
                IndexedCollection<Item> reopenedItems = new ConcurrentIndexedCollection<Item>(reopenedPersistence);
                TestAssertions.assertEquals(1, reopenedItems.size());
            }
            finally {
                reopenedPersistence.close();
            }
        }
        finally {
            TestAssertions.assertTrue("Failed to delete temp file: " + file, file.delete());
        }
    }

    @Test
    public void bulkImportRestoresPragmasAfterTransaction() {
        DiskPersistence<Item, Integer> persistence = DiskPersistence.onPrimaryKey(Item.ID);
        File file = persistence.getFile();
        try {
            SQLiteConfig.SynchronousMode synchronousMode = readSynchronousMode(persistence);
            SQLiteConfig.JournalMode journalMode = readJournalMode(persistence);
            IndexedCollection<Item> items = new ConcurrentIndexedCollection<Item>(persistence);
            QueryOptions options = queryOptions(enableFlags(
                    SQLiteIndexFlags.BULK_IMPORT,
                    SQLiteIndexFlags.BULK_IMPORT_SUSPEND_SYNC_AND_JOURNALING));

            TestAssertions.assertTrue(items.update(
                    Collections.<Item>emptyList(),
                    Arrays.asList(new Item(1, "Ford"), new Item(2, "Honda")),
                    options));

            TestAssertions.assertEquals(synchronousMode, readSynchronousMode(persistence));
            TestAssertions.assertEquals(journalMode, readJournalMode(persistence));
        }
        finally {
            persistence.close();
            TestAssertions.assertTrue("Failed to delete temp file: " + file, file.delete());
        }
    }

    @Test
    public void failedBulkImportRollsBackAndRestoresPragmas() {
        DiskPersistence<Item, Integer> persistence = DiskPersistence.onPrimaryKey(Item.ID);
        File file = persistence.getFile();
        try {
            IndexedCollection<Item> items = new ConcurrentIndexedCollection<Item>(persistence);
            items.addIndex(UniqueIndex.onAttribute(Item.MANUFACTURER));
            items.add(new Item(1, "Ford"));
            SQLiteConfig.SynchronousMode synchronousMode = readSynchronousMode(persistence);
            SQLiteConfig.JournalMode journalMode = readJournalMode(persistence);
            QueryOptions options = queryOptions(enableFlags(
                    SQLiteIndexFlags.BULK_IMPORT,
                    SQLiteIndexFlags.BULK_IMPORT_SUSPEND_SYNC_AND_JOURNALING));

            try {
                items.update(
                        Collections.<Item>emptyList(),
                        Collections.singletonList(new Item(2, "Ford")),
                        options);
                TestAssertions.fail("Expected the unique index to reject the duplicate manufacturer");
            }
            catch (UniqueConstraintViolatedException expected) {
            }

            TestAssertions.assertEquals(1, countRows(persistence));
            TestAssertions.assertEquals(synchronousMode, readSynchronousMode(persistence));
            TestAssertions.assertEquals(journalMode, readJournalMode(persistence));
        }
        finally {
            persistence.close();
            TestAssertions.assertTrue("Failed to delete temp file: " + file, file.delete());
        }
    }

    @Test
    public void iteratorRemoveCheckpointsAreVisibleWhileCursorRemainsOpen() {
        DiskPersistence<Item, Integer> persistence = DiskPersistence.onPrimaryKey(Item.ID);
        File file = persistence.getFile();
        try {
            ConcurrentIndexedCollection<Item> items = new ConcurrentIndexedCollection<Item>(persistence);
            items.add(new Item(1, "Ford"));
            items.add(new Item(2, "Honda"));
            items.add(new Item(3, "Toyota"));

            CloseableIterator<Item> iterator = items.iterator();
            try {
                iterator.next();
                iterator.remove();
                TestAssertions.assertEquals(2, countRows(persistence));
                iterator.next();
                iterator.remove();
                TestAssertions.assertEquals(1, countRows(persistence));
            }
            finally {
                iterator.close();
            }
        }
        finally {
            persistence.close();
            TestAssertions.assertTrue("Failed to delete temp file: " + file, file.delete());
        }
    }

    private static int countRows(DiskPersistence<Item, Integer> persistence) {
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

    private static SQLiteConfig.SynchronousMode readSynchronousMode(DiskPersistence<Item, Integer> persistence) {
        Connection connection = persistence.getConnection(null, new QueryOptions());
        try {
            return DBQueries.getPragmaSynchronousOrNull(connection);
        }
        finally {
            DBUtils.closeQuietly(connection);
        }
    }

    private static SQLiteConfig.JournalMode readJournalMode(DiskPersistence<Item, Integer> persistence) {
        Connection connection = persistence.getConnection(null, new QueryOptions());
        try {
            return DBQueries.getPragmaJournalModeOrNull(connection);
        }
        finally {
            DBUtils.closeQuietly(connection);
        }
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
