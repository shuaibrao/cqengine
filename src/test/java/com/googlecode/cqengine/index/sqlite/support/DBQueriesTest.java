/**
 * Copyright 2012-2015 Niall Gallagher
 * Modified by Shuaib Rao in 2026.
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
package com.googlecode.cqengine.index.sqlite.support;

import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.query.simple.*;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;

import java.sql.*;
import java.util.*;

import static com.googlecode.cqengine.index.sqlite.TemporaryDatabase.TemporaryFileDatabase;
import static com.googlecode.cqengine.query.QueryFactory.*;
import static com.googlecode.cqengine.query.QueryFactory.startsWith;
import static com.googlecode.cqengine.testutil.TestAssertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DBQueries}
 *
 * @author Silvano Riz
 */
public class DBQueriesTest {

    private static final String NAME = "features";
    private static final String TABLE_NAME = "cqtbl_" + NAME;
    private static final String INDEX_NAME = "cqidx_" + NAME + "_value";

    @RegisterExtension
    public TemporaryFileDatabase temporaryFileDatabase = new TemporaryFileDatabase();

    @Test
    public void testCreateIndexTable() throws SQLException {

        Connection connection = null;
        Statement statement = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            connection = spy(connectionManager.getConnection(null, noQueryOptions()));
            statement = spy(connection.createStatement());
            when(connection.createStatement()).thenReturn(statement);

            DBQueries.createIndexTable(NAME, Integer.class, String.class, connection);

            assertObjectExistenceInSQLIteMasterTable(TABLE_NAME, "table", true, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(INDEX_NAME, "index", false, connectionManager);
            verify(statement, times(1)).close();
        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }
    }

    @Test
    public void testCreateIndexOnTable() throws SQLException {

        Connection connection = null;
        Statement statement = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            connection = spy(connectionManager.getConnection(null, noQueryOptions()));
            statement = spy(connection.createStatement());
            when(connection.createStatement()).thenReturn(statement);

            DBQueries.createIndexTable(NAME, Integer.class, String.class, connection);
            DBQueries.createIndexOnTable(NAME, connection);

            assertObjectExistenceInSQLIteMasterTable(TABLE_NAME, "table", true, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(INDEX_NAME, "index", true, connectionManager);
            verify(statement, times(2)).close();
        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }
    }

    @Test
    public void testDropIndexOnTable() throws SQLException {

        Connection connection = null;
        Statement statement = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            connection = spy(connectionManager.getConnection(null, noQueryOptions()));
            statement = spy(connection.createStatement());
            when(connection.createStatement()).thenReturn(statement);

            DBQueries.createIndexTable(NAME, Integer.class, String.class, connection);
            DBQueries.createIndexOnTable(NAME, connection);

            assertObjectExistenceInSQLIteMasterTable(TABLE_NAME, "table", true, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(INDEX_NAME, "index", true, connectionManager);

            DBQueries.dropIndexOnTable(NAME, connection);
            assertObjectExistenceInSQLIteMasterTable(TABLE_NAME, "table", true, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(INDEX_NAME, "index", false, connectionManager);

            verify(statement, times(3)).close();
        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }
    }

    @Test
    public void testDropIndexTable() throws SQLException {

        Connection connection = null;
        Statement statement = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            createSchema(connectionManager);

            assertObjectExistenceInSQLIteMasterTable(TABLE_NAME, "table", true, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(INDEX_NAME, "index", true, connectionManager);

            connection = spy(connectionManager.getConnection(null, noQueryOptions()));
            statement = spy(connection.createStatement());
            when(connection.createStatement()).thenReturn(statement);
            DBQueries.dropIndexTable(NAME, connection);

            assertObjectExistenceInSQLIteMasterTable(TABLE_NAME, "table", false, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(INDEX_NAME, "index", false, connectionManager);
            verify(statement, times(1)).close();
        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }
    }

    @Test
    public void testClearIndexTable() throws SQLException {
        Connection connection = null;
        Statement statement = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            createSchema(connectionManager);
            assertObjectExistenceInSQLIteMasterTable(TABLE_NAME, "table", true, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(INDEX_NAME, "index", true, connectionManager);

            connection = spy(connectionManager.getConnection(null, noQueryOptions()));
            statement = spy(connection.createStatement());
            when(connection.createStatement()).thenReturn(statement);
            DBQueries.clearIndexTable(NAME, connection);

            List<DBQueries.Row<Integer, String>> expectedRows = Collections.emptyList();
            assertQueryResultSet("SELECT * FROM " + TABLE_NAME, expectedRows, connectionManager);
            verify(statement, times(1)).close();
        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }
    }

    @Test
    public void testBulkAdd() throws SQLException {
        Connection connection = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            createSchema(connectionManager);

            List<DBQueries.Row<Integer, String>> rowsToAdd = new ArrayList<DBQueries.Row<Integer, String>>(4);
            rowsToAdd.add(new DBQueries.Row<Integer, String>(1, "abs"));
            rowsToAdd.add(new DBQueries.Row<Integer, String>(1, "gps"));
            rowsToAdd.add(new DBQueries.Row<Integer, String>(2, "airbags"));
            rowsToAdd.add(new DBQueries.Row<Integer, String>(2, "abs"));

            connection = connectionManager.getConnection(null, noQueryOptions());
            DBQueries.bulkAdd(rowsToAdd, NAME, connection);
            assertQueryResultSet("SELECT * FROM " + TABLE_NAME, rowsToAdd, connectionManager);

        }finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void testBulkRemove() throws SQLException {

        Connection connection = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            List<DBQueries.Row<Integer, String>> expectedRows = new ArrayList<DBQueries.Row<Integer, String>>(2);
            expectedRows.add(new DBQueries.Row<Integer, String>(2, "airbags"));
            expectedRows.add(new DBQueries.Row<Integer, String>(3, "abs"));

            connection = connectionManager.getConnection(null, noQueryOptions());
            DBQueries.bulkRemove(Collections.singletonList(1), NAME, connection);
            assertQueryResultSet("SELECT * FROM " + TABLE_NAME, expectedRows, connectionManager);

        }finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void bulkRemoveDoesNotCompleteCallerTransaction() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement("DELETE FROM \"cqtbl_features\" WHERE objectKey = ?;"))
                .thenReturn(statement);
        when(statement.executeBatch()).thenReturn(new int[] {1});

        TestAssertions.assertEquals(1, DBQueries.bulkRemove(Collections.singletonList(1), NAME, connection));

        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    public void bulkAddFailureDoesNotRollbackCallerTransaction() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement("INSERT OR IGNORE INTO \"cqtbl_features\" values(?, ?);"))
                .thenReturn(statement);
        when(statement.executeBatch()).thenThrow(new SQLException("batch failed"));

        try {
            DBQueries.bulkAdd(Collections.singletonList(new DBQueries.Row<Integer, String>(1, "abs")), NAME, connection);
            TestAssertions.fail("Expected bulk add to fail");
        }
        catch (IllegalStateException expected) {
            TestAssertions.assertTrue(expected.getMessage().contains("bulk add"));
        }

        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    public void testGetAllIndexEntries() throws SQLException {

        Connection connection = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            List<DBQueries.Row<Integer, String>> expectedRows = new ArrayList<DBQueries.Row<Integer, String>>(2);
            expectedRows.add(new DBQueries.Row<Integer, String>(1, "abs"));
            expectedRows.add(new DBQueries.Row<Integer, String>(1, "gps"));
            expectedRows.add(new DBQueries.Row<Integer, String>(2, "airbags"));
            expectedRows.add(new DBQueries.Row<Integer, String>(3, "abs"));

            connection = connectionManager.getConnection(null, noQueryOptions());
            ResultSet resultSet = DBQueries.getAllIndexEntries( NAME, connection);
            assertResultSetOrderAgnostic(resultSet, expectedRows);

        }finally {
            DBUtils.closeQuietly(connection);
        }

    }

    @Test
    public void testGetIndexEntryByObjectKey() throws SQLException {

        Connection connection = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            connection = connectionManager.getConnection(null, noQueryOptions());
            ResultSet resultSet = DBQueries.getIndexEntryByObjectKey(3, NAME, connection);

            List<DBQueries.Row<Integer, String>> expectedRows = new ArrayList<DBQueries.Row<Integer, String>>(2);
            expectedRows.add(new DBQueries.Row<Integer, String>(3, "abs"));

            assertResultSetOrderAgnostic(resultSet, expectedRows);

        }finally {
            DBUtils.closeQuietly(connection);
        }

    }

    @Test
    public void testCount_Equal() throws SQLException {

        Connection connection = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            Equal<Car, String> equal = equal(Car.FEATURES, "abs");

            connection = connectionManager.getConnection(null, noQueryOptions());
            int count = DBQueries.count(equal, NAME, connection);
            TestAssertions.assertEquals(2, count);

        }finally {
            DBUtils.closeQuietly(connection);
        }

    }

    @Test
    public void testSearch_Equal() throws SQLException {
        Connection connection = null;
        ResultSet resultSet = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            Equal<Car, String> equal = equal(Car.FEATURES, "abs");

            connection = connectionManager.getConnection(null, noQueryOptions());
            resultSet = DBQueries.search(equal, NAME, connection);
            assertResultSetObjectKeysOrderAgnostic(resultSet, Arrays.asList(1, 3));

        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(resultSet);
        }
    }

    @Test
    public void testSearch_LessThan() throws SQLException {
        Connection connection = null;
        ResultSet resultSet = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            LessThan<Car, String> lessThan = lessThan(Car.FEATURES, "abz");

            connection = connectionManager.getConnection(null, noQueryOptions());
            resultSet = DBQueries.search(lessThan, NAME, connection);
            assertResultSetObjectKeysOrderAgnostic(resultSet, Arrays.asList(1, 3));

        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(resultSet);
        }
    }

    @Test
    public void testSearch_GreaterThan() throws SQLException {
        Connection connection = null;
        ResultSet resultSet = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            GreaterThan<Car, String> greaterThan = greaterThan(Car.FEATURES, "abz");

            connection = connectionManager.getConnection(null, noQueryOptions());
            resultSet = DBQueries.search(greaterThan, NAME, connection);
            assertResultSetObjectKeysOrderAgnostic(resultSet, Arrays.asList(1, 2));

        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(resultSet);
        }
    }

    @Test
    public void testSearch_Between() throws SQLException {
        Connection connection = null;
        ResultSet resultSet = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            Between<Car, String> between = between(Car.FEATURES, "a", "b");

            connection = connectionManager.getConnection(null, noQueryOptions());
            resultSet = DBQueries.search(between, NAME, connection);
            assertResultSetObjectKeysOrderAgnostic(resultSet, Arrays.asList(1, 2, 3));

        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(resultSet);
        }
    }

    @Test
    public void testSearch_StringStartsWith() throws SQLException {
        Connection connection = null;
        ResultSet resultSet = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            StringStartsWith<Car, String> startsWith = startsWith(Car.FEATURES, "ab");

            connection = connectionManager.getConnection(null, noQueryOptions());
            resultSet = DBQueries.search(startsWith, NAME, connection);
            assertResultSetObjectKeysOrderAgnostic(resultSet, Arrays.asList(1, 3));

        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(resultSet);
        }
    }

    @Test
    public void testSearch_Has() throws SQLException {
        Connection connection = null;
        ResultSet resultSet = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            connection = connectionManager.getConnection(null, noQueryOptions());
            resultSet = DBQueries.search(has(selfAttribute(Car.class)), NAME, connection);
            assertResultSetObjectKeysOrderAgnostic(resultSet, Arrays.asList(1, 2, 3));

        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(resultSet);
        }
    }

    @Test
    public void testContains() throws SQLException {
        Connection connection = null;

        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            Equal<Car, String> equal = equal(Car.FEATURES, "abs");

            connection = connectionManager.getConnection(null, noQueryOptions());
            TestAssertions.assertTrue(DBQueries.contains(1, equal, NAME, connection));
            TestAssertions.assertFalse(DBQueries.contains(4, equal, NAME, connection));

        }finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void testEnsureNotNegative_ValidCase() {
        IllegalStateException unexpected = null;
        try {
            DBQueries.ensureNotNegative(0);
            DBQueries.ensureNotNegative(1);
        }
        catch (IllegalStateException e) {
            unexpected = e;
        }
        assertNull(unexpected);
    }

    @Test
    public void testEnsureNotNegative_InvalidCase() {
        IllegalStateException expected = null;
        try {
            DBQueries.ensureNotNegative(-1);
        }
        catch (IllegalStateException e) {
            expected = e;
        }
        assertNotNull(expected);
        assertEquals("Update returned error code: -1", expected.getMessage());
    }

    @Test
    public void getDistinctKeysAndCounts(){

        Connection connection = null;
        ResultSet resultSet = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            connection = connectionManager.getConnection(null, noQueryOptions());
            resultSet = DBQueries.getDistinctKeysAndCounts(false, NAME, connection);

            Map<String, Integer> resultSetToMap = resultSetToMap(resultSet);
            assertEquals(3, resultSetToMap.size());
            assertEquals(Integer.valueOf(2), resultSetToMap.get("abs"));
            assertEquals(Integer.valueOf(1), resultSetToMap.get("airbags"));
            assertEquals(Integer.valueOf(1), resultSetToMap.get("gps"));

        }finally {
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(connection);
        }

    }

    @Test
    public void getDistinctKeysAndCounts_SortByKeyDescending(){

        Connection connection = null;
        ResultSet resultSet = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            connection = connectionManager.getConnection(null, noQueryOptions());
            resultSet = DBQueries.getDistinctKeysAndCounts(true, NAME, connection);

            Map<String, Integer> resultSetToMap = resultSetToMap(resultSet);
            assertEquals(3, resultSetToMap.size());

            Iterator<Map.Entry<String, Integer>> entriesIterator = resultSetToMap.entrySet().iterator();

            Map.Entry<String, Integer> entry = entriesIterator.next();
            assertEquals("gps", entry.getKey());
            assertEquals(Integer.valueOf(1), entry.getValue());

            entry = entriesIterator.next();
            assertEquals("airbags", entry.getKey());
            assertEquals(Integer.valueOf(1), entry.getValue());

            entry = entriesIterator.next();
            assertEquals("abs", entry.getKey());
            assertEquals(Integer.valueOf(2), entry.getValue());

        }finally {
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(connection);
        }

    }

    @Test
    public void getCountOfDistinctKeys(){

        Connection connection = null;
        try {
            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            initWithTestData(connectionManager);

            connection = connectionManager.getConnection(null, noQueryOptions());
            int countOfDistinctKeys = DBQueries.getCountOfDistinctKeys(NAME, connection);
            assertEquals(3, countOfDistinctKeys);

        }finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void getCountOfDistinctKeysClosesItsStatement() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(3);

        assertEquals(3, DBQueries.getCountOfDistinctKeys(NAME, connection));

        verify(resultSet).close();
        verify(statement).close();
    }

    @Test
    public void journalModeParsingDoesNotDependOnTheDefaultLocale() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("PRAGMA journal_mode;")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("persist");
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(SQLiteConfig.JournalMode.PERSIST, DBQueries.getPragmaJournalModeOrNull(connection));
        }
        finally {
            Locale.setDefault(previousLocale);
        }
        verify(resultSet).close();
        verify(statement).close();
    }

    @Test
    public void suspendSyncAndJournaling() throws Exception {
        Connection connection = null;
        try {

            ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
            connection = connectionManager.getConnection(null, noQueryOptions());

            final SQLiteConfig.JournalMode journalMode = DBQueries.getPragmaJournalModeOrNull(connection);
            final SQLiteConfig.SynchronousMode synchronousMode = DBQueries.getPragmaSynchronousOrNull(connection);

            DBQueries.suspendSyncAndJournaling(connection);

            final SQLiteConfig.JournalMode journalModeDisabled = DBQueries.getPragmaJournalModeOrNull(connection);
            final SQLiteConfig.SynchronousMode synchronousModeDisabled = DBQueries.getPragmaSynchronousOrNull(connection);

            TestAssertions.assertEquals(journalModeDisabled, SQLiteConfig.JournalMode.OFF);
            TestAssertions.assertEquals(synchronousModeDisabled, SQLiteConfig.SynchronousMode.OFF);

            DBQueries.setSyncAndJournaling(connection, SQLiteConfig.SynchronousMode.FULL, SQLiteConfig.JournalMode.DELETE);

            final SQLiteConfig.JournalMode journalModeReset = DBQueries.getPragmaJournalModeOrNull(connection);
            final SQLiteConfig.SynchronousMode synchronousModeReset = DBQueries.getPragmaSynchronousOrNull(connection);

            TestAssertions.assertEquals(journalModeReset, journalMode);
            TestAssertions.assertEquals(synchronousModeReset, synchronousMode);

        }finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void setSyncAndJournalingRejectsActiveTransactionWithoutCommittingIt() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(false);

        try {
            DBQueries.setSyncAndJournaling(connection, SQLiteConfig.SynchronousMode.FULL, SQLiteConfig.JournalMode.DELETE);
            TestAssertions.fail("Expected an active transaction to be rejected");
        }
        catch (IllegalStateException expected) {
            TestAssertions.assertTrue(expected.getMessage().contains("active request transaction"));
        }

        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).commit();
        verify(connection, never()).createStatement();
    }

    @Test
    public void testIndexTableExists_ExceptionHandling() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='cqtbl_foo';"))
                .thenThrow(new SQLException("expected_exception"));

        IllegalStateException expected = null;
        try {
            DBQueries.indexTableExists("foo", connection);
        }
        catch (IllegalStateException e) {
            expected = e;
        }
        assertNotNull(expected);
        assertEquals("Unable to determine if table exists: foo", expected.getMessage());
        assertNotNull(expected.getCause());
        assertEquals("expected_exception", expected.getCause().getMessage());
    }

    @Test
    public void quotesValidatedGeneratedIdentifiers() {
        TestAssertions.assertEquals("\"cqtbl_features\"", DBQueries.tableIdentifier("features"));
        TestAssertions.assertEquals("\"cqidx_features_value\"", DBQueries.indexIdentifier("features"));
        TestAssertions.assertEquals("cqtbl_features", DBQueries.tableNameValue("features"));
    }

    @Test
    public void rejectsInvalidTableNamesBeforeCallingJdbc() {
        Connection connection = mock(Connection.class);
        String[] rejected = {
                "",
                "features;DROP_TABLE",
                "features\"quoted",
                "features'quoted",
                "café",
                "车辆",
                "a".repeat(DBUtils.MAX_SQLITE_IDENTIFIER_COMPONENT_LENGTH + 1)
        };

        TestAssertions.assertThrows(
                NullPointerException.class, () -> DBQueries.indexTableExists(null, connection));
        for (String tableName : rejected) {
            TestAssertions.assertThrows(
                    "Expected table name to be rejected",
                    IllegalArgumentException.class,
                    () -> DBQueries.indexTableExists(tableName, connection));
            TestAssertions.assertThrows(
                    "Expected table name to be rejected",
                    IllegalArgumentException.class,
                    () -> DBQueries.bulkAdd(Collections.emptyList(), tableName, connection));
            TestAssertions.assertThrows(
                    "Expected table name to be rejected",
                    IllegalArgumentException.class,
                    () -> DBQueries.getAllIndexEntries(tableName, connection));
        }
        verifyNoInteractions(connection);
    }

    @Test
    public void quotedQueriesReopenLegacyUnquotedTableNames() throws SQLException {
        ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
        Connection originalConnection = connectionManager.getConnection(null, noQueryOptions());
        try (Statement statement = originalConnection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE cqtbl_legacy_name "
                            + "(objectKey INTEGER, value TEXT, PRIMARY KEY (objectKey, value)) WITHOUT ROWID;");
            statement.executeUpdate("INSERT INTO cqtbl_legacy_name VALUES (1, 'abs');");
        }
        finally {
            DBUtils.closeQuietly(originalConnection);
        }

        Connection reopenedConnection = connectionManager.getConnection(null, noQueryOptions());
        java.sql.ResultSet entries = null;
        try {
            TestAssertions.assertTrue(DBQueries.indexTableExists("legacy_name", reopenedConnection));
            DBQueries.createIndexOnTable("legacy_name", reopenedConnection);
            entries = DBQueries.getAllIndexEntries("legacy_name", reopenedConnection);
            TestAssertions.assertTrue(entries.next());
            TestAssertions.assertEquals(1, entries.getInt(1));
            TestAssertions.assertEquals("abs", entries.getString(2));
            TestAssertions.assertFalse(entries.next());
        }
        finally {
            DBUtils.closeQuietly(entries);
            DBUtils.closeQuietly(reopenedConnection);
        }
    }

    @Test
    public void adoptsLegacyTableWhoseAttributeNameSanitizedToTheEmptyComponent() throws SQLException {
        ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
        Connection connection = connectionManager.getConnection(null, noQueryOptions());
        String versionTwoName = DBUtils.createSQLiteIndexTableNameV2("***", "");
        String otherVersionTwoName = DBUtils.createSQLiteIndexTableNameV2("###", "");
        try {
            // A pre-4.0 store whose attribute name contained no alphanumeric characters used the bare
            // "cqtbl_" table with the "cqidx__value" index; seed exactly that legacy schema.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE \"cqtbl_\" (objectKey INTEGER, value TEXT, "
                                + "PRIMARY KEY (objectKey, value)) WITHOUT ROWID;");
                statement.executeUpdate("CREATE INDEX \"cqidx__value\" ON \"cqtbl_\" (value);");
                statement.executeUpdate("INSERT INTO \"cqtbl_\" (objectKey, value) VALUES (1, 'legacy');");
            }

            TestAssertions.assertTrue(DBQueries.migrateLegacyIndexTableIfNeeded("", versionTwoName, connection));
            TestAssertions.assertTrue(DBQueries.indexTableExists(versionTwoName, connection));
            assertObjectExistenceInSQLIteMasterTable("cqtbl_", "table", false, connectionManager);
            assertObjectExistenceInSQLIteMasterTable("cqidx__value", "index", false, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(
                    "cqidx_" + versionTwoName + "_value", "index", true, connectionManager);
            try (java.sql.ResultSet entries = DBQueries.getAllIndexEntries(versionTwoName, connection)) {
                TestAssertions.assertTrue(entries.next());
                TestAssertions.assertEquals(1, entries.getInt(1));
                TestAssertions.assertEquals("legacy", entries.getString(2));
                TestAssertions.assertFalse(entries.next());
            }

            // Reopening is a no-op, and a different all-non-alphanumeric attribute cannot claim the data.
            TestAssertions.assertFalse(DBQueries.migrateLegacyIndexTableIfNeeded("", versionTwoName, connection));
            TestAssertions.assertFalse(
                    DBQueries.migrateLegacyIndexTableIfNeeded("", otherVersionTwoName, connection));
            TestAssertions.assertFalse(DBQueries.indexTableExists(otherVersionTwoName, connection));
        }
        finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void atomicallyMigratesLegacyTableDataAndIndexToVersionTwoName() throws SQLException {
        ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
        Connection connection = connectionManager.getConnection(null, noQueryOptions());
        String versionTwoName = DBUtils.createSQLiteIndexTableNameV2("legacy-name", "");
        try {
            DBQueries.createIndexTable("legacyname", Integer.class, String.class, connection);
            DBQueries.createIndexOnTable("legacyname", connection);
            DBQueries.bulkAdd(
                    Collections.singletonList(new DBQueries.Row<Integer, String>(1, "abs")),
                    "legacyname",
                    connection);

            TestAssertions.assertTrue(
                    DBQueries.migrateLegacyIndexTableIfNeeded("legacyname", versionTwoName, connection));
            TestAssertions.assertFalse(DBQueries.indexTableExists("legacyname", connection));
            TestAssertions.assertTrue(DBQueries.indexTableExists(versionTwoName, connection));
            TestAssertions.assertFalse(
                    DBQueries.migrateLegacyIndexTableIfNeeded("legacyname", versionTwoName, connection));

            try (java.sql.ResultSet entries = DBQueries.getAllIndexEntries(versionTwoName, connection)) {
                TestAssertions.assertTrue(entries.next());
                TestAssertions.assertEquals(1, entries.getInt(1));
                TestAssertions.assertEquals("abs", entries.getString(2));
                TestAssertions.assertFalse(entries.next());
            }
            assertObjectExistenceInSQLIteMasterTable(
                    "cqidx_" + versionTwoName + "_value", "index", true, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(
                    "cqidx_legacyname_value", "index", false, connectionManager);
        }
        finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void legacyMigrationMappingKeepsCollidingVersionTwoTablesIndependent() throws SQLException {
        ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
        Connection connection = connectionManager.getConnection(null, noQueryOptions());
        String punctuatedName = DBUtils.createSQLiteIndexTableNameV2("a-b", "");
        String plainName = DBUtils.createSQLiteIndexTableNameV2("ab", "");
        try {
            DBQueries.createIndexTable("ab", Integer.class, String.class, connection);
            DBQueries.bulkAdd(
                    Collections.singletonList(new DBQueries.Row<Integer, String>(1, "legacy")),
                    "ab",
                    connection);

            TestAssertions.assertTrue(DBQueries.migrateLegacyIndexTableIfNeeded("ab", punctuatedName, connection));
            TestAssertions.assertFalse(DBQueries.migrateLegacyIndexTableIfNeeded("ab", plainName, connection));
            DBQueries.createIndexTable(plainName, Integer.class, String.class, connection);
            DBQueries.bulkAdd(
                    Collections.singletonList(new DBQueries.Row<Integer, String>(2, "current")),
                    plainName,
                    connection);

            TestAssertions.assertEquals(1, DBQueries.count(has(Car.FEATURES), punctuatedName, connection));
            TestAssertions.assertEquals(1, DBQueries.count(has(Car.FEATURES), plainName, connection));
            try (java.sql.ResultSet entries = DBQueries.getAllIndexEntries(punctuatedName, connection)) {
                TestAssertions.assertTrue(entries.next());
                TestAssertions.assertEquals(1, entries.getInt(1));
                TestAssertions.assertEquals("legacy", entries.getString(2));
                TestAssertions.assertFalse(entries.next());
            }
            try (java.sql.ResultSet entries = DBQueries.getAllIndexEntries(plainName, connection)) {
                TestAssertions.assertTrue(entries.next());
                TestAssertions.assertEquals(2, entries.getInt(1));
                TestAssertions.assertEquals("current", entries.getString(2));
                TestAssertions.assertFalse(entries.next());
            }

            DBQueries.dropIndexTable(punctuatedName, connection);
            TestAssertions.assertFalse(DBQueries.migrateLegacyIndexTableIfNeeded("ab", punctuatedName, connection));
            DBQueries.createIndexTable(punctuatedName, Integer.class, String.class, connection);
            TestAssertions.assertTrue(DBQueries.indexTableExists(punctuatedName, connection));
        }
        finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void rejectsAmbiguousDualLegacyAndVersionTwoSchemasWithoutChangingEither() throws SQLException {
        ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
        Connection connection = connectionManager.getConnection(null, noQueryOptions());
        String versionTwoName = DBUtils.createSQLiteIndexTableNameV2("a-b", "");
        try {
            DBQueries.createIndexTable("ab", Integer.class, String.class, connection);
            DBQueries.createIndexTable(versionTwoName, Integer.class, String.class, connection);

            TestAssertions.assertThrows(
                    IllegalStateException.class,
                    () -> DBQueries.migrateLegacyIndexTableIfNeeded("ab", versionTwoName, connection));
            TestAssertions.assertTrue(DBQueries.indexTableExists("ab", connection));
            TestAssertions.assertTrue(DBQueries.indexTableExists(versionTwoName, connection));
        }
        finally {
            DBUtils.closeQuietly(connection);
        }
    }

    @Test
    public void rollsBackTheWholeMigrationWhenRecordingTheAssignmentFails() throws SQLException {
        ConnectionManager connectionManager = temporaryFileDatabase.getConnectionManager(true);
        Connection connection = connectionManager.getConnection(null, noQueryOptions());
        String versionTwoName = DBUtils.createSQLiteIndexTableNameV2("legacy-name", "");
        try {
            DBQueries.createIndexTable("legacyname", Integer.class, String.class, connection);
            DBQueries.createIndexOnTable("legacyname", connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE cqengine_sqlite_identifier_migrations_v2 ("
                                + "legacy_component TEXT PRIMARY KEY NOT NULL, "
                                + "v2_component TEXT UNIQUE NOT NULL CHECK (v2_component = 'rejected'));"
                );
            }

            TestAssertions.assertThrows(
                    IllegalStateException.class,
                    () -> DBQueries.migrateLegacyIndexTableIfNeeded("legacyname", versionTwoName, connection));

            TestAssertions.assertTrue(connection.getAutoCommit());
            TestAssertions.assertTrue(DBQueries.indexTableExists("legacyname", connection));
            TestAssertions.assertFalse(DBQueries.indexTableExists(versionTwoName, connection));
            assertObjectExistenceInSQLIteMasterTable(
                    "cqidx_legacyname_value", "index", true, connectionManager);
            assertObjectExistenceInSQLIteMasterTable(
                    "cqidx_" + versionTwoName + "_value", "index", false, connectionManager);
        }
        finally {
            DBUtils.closeQuietly(connection);
        }
    }

    void createSchema(final ConnectionManager connectionManager){
        Connection connection = null;
        Statement statement = null;

        try {
            connection = connectionManager.getConnection(null, noQueryOptions());
            statement = connection.createStatement();
            assertEquals(statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (objectKey INTEGER, value TEXT)"), 0);
            assertEquals(statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + INDEX_NAME + " ON " + TABLE_NAME + "(value)"), 0);
        }catch(Exception e){
            throw new IllegalStateException("Unable to create test database schema", e);
        }finally{
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }
    }

    void initWithTestData(final ConnectionManager connectionManager){

        createSchema(connectionManager);

        Connection connection = null;
        Statement statement = null;
        try {
            connection = connectionManager.getConnection(null, noQueryOptions());
            statement = connection.createStatement();
            assertEquals(statement.executeUpdate("INSERT INTO " + TABLE_NAME + " values (1, 'abs')"), 1);
            assertEquals(statement.executeUpdate("INSERT INTO " + TABLE_NAME + " values (1, 'gps')"), 1);
            assertEquals(statement.executeUpdate("INSERT INTO " + TABLE_NAME + " values (2, 'airbags')"), 1);
            assertEquals(statement.executeUpdate("INSERT INTO " + TABLE_NAME + " values (3, 'abs')"), 1);
        }catch(Exception e){
            throw new IllegalStateException("Unable to initialize test database",e);
        }finally{
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }
    }

    public void assertObjectExistenceInSQLIteMasterTable(final String name, final String type, boolean exists, final ConnectionManager connectionManager){
        Connection connection = null;
        PreparedStatement statement = null;
        try{
            connection = connectionManager.getConnection(null, noQueryOptions());
            statement = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type=?");
            statement.setString(1, type);
            java.sql.ResultSet indices = statement.executeQuery();

            boolean found = false;
            StringBuilder objectsFound = new StringBuilder();
            String next;
            while(indices.next()){
                next = indices.getString(1);
                objectsFound.append("'").append(next).append("' ");
                if (name.equals(next)){
                    found = true;
                }
            }

            if (exists)
                TestAssertions.assertTrue("Object '" + name + "' must exists in 'sqlite_master' but it doesn't. found: " + found + ". Objects found: " + objectsFound, found);
            else
                TestAssertions.assertFalse("Object '" + name + "' must NOT exists in 'sqlite_master' but it does. found: " + found + " Objects found: " + objectsFound, found);

        }catch(Exception e){
            throw new IllegalStateException("Unable to verify existence of the object '" + name + "' in the 'sqlite_master' table", e);
        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }
    }

    public static <K, V> Map<K, V> resultSetToMap(final ResultSet resultSet){

        try {
            final Map<K, V> map = new LinkedHashMap<K, V>();

            while (resultSet.next()) {

                @SuppressWarnings("unchecked")
                K key = (K) resultSet.getObject(1);
                @SuppressWarnings("unchecked")
                V value = (V) resultSet.getObject(2);

                map.put(key, value);
            }

            return map;
        }catch(Exception e){
            throw new IllegalStateException("Unable to transform the resultSet into a Map", e);
        }
    }

    public void assertResultSetObjectKeysOrderAgnostic(final ResultSet resultSet, final List<Integer> objectKeys){
        try {
            List<Integer> actual = new ArrayList<Integer>(objectKeys.size());
            while (resultSet.next()) {
                actual.add(resultSet.getInt(1));
            }

            Collections.sort(actual);
            Collections.sort(objectKeys);

            TestAssertions.assertEquals(objectKeys, actual);

        }catch(Exception e){
            throw new IllegalStateException("Unable to verify resultSet", e);
        }
    }

    public void assertResultSetOrderAgnostic(final ResultSet resultSet, final List<DBQueries.Row<Integer, String>> rows){

        try {
            List<DBQueries.Row<Integer, String>> actual = new ArrayList<DBQueries.Row<Integer, String>>(rows.size());
            while (resultSet.next()) {
                actual.add(new DBQueries.Row<Integer, String>(resultSet.getInt(1), resultSet.getString(2)));
            }

            Comparator<DBQueries.Row<Integer, String>> comparator = new Comparator<DBQueries.Row<Integer, String>>() {
                @Override
                public int compare(DBQueries.Row<Integer, String> o1, DBQueries.Row<Integer, String> o2) {
                    int objectKeyComparison = o1.getObjectKey().compareTo(o2.getObjectKey());
                    if (objectKeyComparison != 0){
                        return objectKeyComparison;
                    }
                    return o1.getValue().compareTo(o2.getValue());
                }
            };

            Collections.sort(actual, comparator);
            Collections.sort(rows, comparator);

            TestAssertions.assertEquals(rows, actual);
        }catch(Exception e){
            throw new IllegalStateException("Unable to verify resultSet", e);
        }
    }

    public void assertQueryResultSet(final String query, final List<DBQueries.Row<Integer, String>> rows, final ConnectionManager connectionManager) throws SQLException {

        Connection connection = null;
        Statement statement = null;

        try{
            connection = connectionManager.getConnection(null, noQueryOptions());
            statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(query);
            assertResultSetOrderAgnostic(resultSet, rows);

        }catch(Exception e){
            throw new IllegalStateException("Unable to verify resultSet", e);
        }finally {
            DBUtils.closeQuietly(connection);
            DBUtils.closeQuietly(statement);
        }

    }
}
