/*
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

import com.googlecode.cqengine.index.sqlite.SQLiteBusyException;
import com.googlecode.cqengine.index.sqlite.support.DBQueries;
import com.googlecode.cqengine.index.sqlite.support.DBUtils;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;

public class DiskPersistenceBusyTimeoutTest {

    @Test
    public void usesFiniteDriverAlignedDefaultAndRetainsValidatedCallerOverride() throws Exception {
        DiskPersistence<Car, Integer> defaultPersistence = DiskPersistence.onPrimaryKey(Car.CAR_ID);
        File defaultFile = defaultPersistence.getFile();
        try (Connection connection = defaultPersistence.getConnection(null, noQueryOptions())) {
            TestAssertions.assertEquals(
                    DiskPersistence.DEFAULT_BUSY_TIMEOUT_MILLIS,
                    defaultPersistence.getBusyTimeoutMillis());
            TestAssertions.assertEquals(
                    DiskPersistence.DEFAULT_BUSY_TIMEOUT_MILLIS,
                    ((SQLiteConnection) connection).getBusyTimeout());
        }
        finally {
            defaultPersistence.close();
            TestAssertions.assertTrue("Failed to delete temp file: " + defaultFile, defaultFile.delete());
        }

        Properties override = new Properties();
        override.setProperty(DiskPersistence.BUSY_TIMEOUT_PROPERTY, "125");
        DiskPersistence<Car, Integer> overriddenPersistence = DiskPersistence.onPrimaryKeyInFileWithProperties(
                Car.CAR_ID, DiskPersistence.createTempFile(), override);
        File overriddenFile = overriddenPersistence.getFile();
        try (Connection connection = overriddenPersistence.getConnection(null, noQueryOptions())) {
            TestAssertions.assertEquals(125, overriddenPersistence.getBusyTimeoutMillis());
            TestAssertions.assertEquals(125, ((SQLiteConnection) connection).getBusyTimeout());
        }
        finally {
            overriddenPersistence.close();
            TestAssertions.assertTrue("Failed to delete temp file: " + overriddenFile, overriddenFile.delete());
        }
    }

    @Test
    public void acceptsImmediateFailureAndRejectsInvalidTimeouts() {
        Properties properties = new Properties();
        properties.setProperty(DiskPersistence.BUSY_TIMEOUT_PROPERTY, "0");
        TestAssertions.assertEquals(0, DiskPersistence.validateBusyTimeout(properties));

        String[] invalidValues = {"-1", "", "not-a-number", "2147483648"};
        for (String invalidValue : invalidValues) {
            properties.setProperty(DiskPersistence.BUSY_TIMEOUT_PROPERTY, invalidValue);
            File persistenceFile = DiskPersistence.createTempFile();
            try {
                TestAssertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> DiskPersistence.onPrimaryKeyInFileWithProperties(
                                Car.CAR_ID, persistenceFile, properties));
            }
            finally {
                TestAssertions.assertTrue("Failed to delete temp file: " + persistenceFile, persistenceFile.delete());
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void contentionTimesOutAndLeavesBothConnectionsAndPersistenceUsable() throws Exception {
        final int timeoutMillis = 150;
        final String tableName = "busy_timeout_test";
        Properties properties = new Properties();
        properties.setProperty(DiskPersistence.BUSY_TIMEOUT_PROPERTY, String.valueOf(timeoutMillis));
        DiskPersistence<Car, Integer> persistence = DiskPersistence.onPrimaryKeyInFileWithProperties(
                Car.CAR_ID, DiskPersistence.createTempFile(), properties);
        File persistenceFile = persistence.getFile();
        Connection lockHolder = null;
        Connection contender = null;
        try {
            lockHolder = persistence.getConnection(null, noQueryOptions());
            contender = persistence.getConnection(null, noQueryOptions());
            TestAssertions.assertNotSame(lockHolder, contender);
            DBQueries.createIndexTable(tableName, Integer.class, String.class, lockHolder);

            lockHolder.setAutoCommit(false);
            TestAssertions.assertEquals(
                    1,
                    DBQueries.bulkAdd(
                            Collections.singletonList(new DBQueries.Row<Integer, String>(1, "held")),
                            tableName,
                            lockHolder));

            Connection contendingConnection = contender;
            long startedNanos = System.nanoTime();
            SQLiteBusyException busyFailure = TestAssertions.assertThrows(
                    SQLiteBusyException.class,
                    () -> DBQueries.bulkAdd(
                            Collections.singletonList(new DBQueries.Row<Integer, String>(2, "blocked")),
                            tableName,
                            contendingConnection));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            TestAssertions.assertTrue("Busy wait returned too early: " + elapsedMillis + " ms", elapsedMillis >= 75);
            TestAssertions.assertTrue("Busy wait exceeded its bound: " + elapsedMillis + " ms", elapsedMillis < 2000);
            TestAssertions.assertEquals(SQLiteErrorCode.SQLITE_BUSY.code, busyFailure.getPrimaryErrorCode());
            TestAssertions.assertEquals(
                    SQLiteErrorCode.SQLITE_BUSY.code,
                    busyFailure.getExtendedErrorCode() & 0xff);
            TestAssertions.assertEquals(
                    busyFailure.getSQLiteException().getResultCode().code,
                    busyFailure.getExtendedErrorCode());
            TestAssertions.assertSame(busyFailure.getSQLiteException(), busyFailure.getCause());

            lockHolder.rollback();
            lockHolder.setAutoCommit(true);
            TestAssertions.assertEquals(
                    1,
                    DBQueries.bulkAdd(
                            Collections.singletonList(new DBQueries.Row<Integer, String>(1, "committed")),
                            tableName,
                            lockHolder));
            TestAssertions.assertEquals(
                    1,
                    DBQueries.bulkAdd(
                            Collections.singletonList(new DBQueries.Row<Integer, String>(2, "committed")),
                            tableName,
                            contender));

            TestAssertions.assertEquals(2, countRows(lockHolder, tableName));
            TestAssertions.assertEquals(2, countRows(contender, tableName));
            TestAssertions.assertTrue(persistence.getBytesUsed() > 0);
        }
        finally {
            if (lockHolder != null) {
                DBUtils.rollback(lockHolder);
                DBUtils.closeQuietly(lockHolder);
            }
            DBUtils.closeQuietly(contender);
            persistence.close();
            TestAssertions.assertTrue("Failed to delete temp file: " + persistenceFile, persistenceFile.delete());
        }
    }

    private static int countRows(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM \"cqtbl_" + tableName + "\"")) {
            TestAssertions.assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
