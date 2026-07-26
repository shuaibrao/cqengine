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

import com.googlecode.concurrenttrees.common.CharSequences;
import com.googlecode.cqengine.index.sqlite.SQLiteIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.simple.*;
import org.sqlite.SQLiteConfig;

import java.sql.*;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Database (SQLite) query executor used by the {@link SQLiteIndex}.
 *
 * @author Silvano Riz
 */
public class DBQueries {

    private static final String IDENTIFIER_MIGRATIONS_TABLE = "cqengine_sqlite_identifier_migrations_v2";

    static String tableIdentifier(String tableName) {
        return quoteGeneratedIdentifier(tableNameValue(tableName));
    }

    static String indexIdentifier(String tableName) {
        return quoteGeneratedIdentifier("cqidx_" + DBUtils.validateSQLiteIdentifierComponent(tableName) + "_value");
    }

    static String tableNameValue(String tableName) {
        return "cqtbl_" + DBUtils.validateSQLiteIdentifierComponent(tableName);
    }

    private static String quoteGeneratedIdentifier(String identifier) {
        return '"' + identifier + '"';
    }

    /**
     * Atomically adopts a table created with CQEngine's legacy sanitizer into the version-two identifier scheme.
     * A durable mapping prevents another logical name with the same legacy sanitizer output from claiming the table
     * on a later opening.
     *
     * @return true if this call renamed a legacy table
     */
    public static boolean migrateLegacyIndexTableIfNeeded(
            String legacyTableName, String versionTwoTableName, Connection connection) {
        String validatedLegacyName = DBUtils.validateSQLiteIdentifierComponent(legacyTableName);
        String validatedVersionTwoName = DBUtils.validateSQLiteIdentifierComponent(versionTwoTableName);
        if (validatedLegacyName.equals(validatedVersionTwoName)) {
            return false;
        }

        boolean restoreAutoCommit;
        try {
            restoreAutoCommit = connection.getAutoCommit();
            if (restoreAutoCommit) {
                connection.setAutoCommit(false);
            }
        }
        catch (SQLException e) {
            throw DBUtils.wrapAsRuntimeException("Unable to start SQLite identifier migration", e);
        }

        Savepoint savepoint = null;
        Throwable failure = null;
        try {
            savepoint = connection.setSavepoint("cqengine_identifier_migration_v2");
            boolean migrated = migrateLegacyIndexTableInTransaction(
                    validatedLegacyName, validatedVersionTwoName, connection);
            connection.releaseSavepoint(savepoint);
            savepoint = null;
            if (restoreAutoCommit) {
                connection.commit();
            }
            return migrated;
        }
        catch (Throwable migrationFailure) {
            failure = migrationFailure;
            try {
                if (savepoint == null) {
                    connection.rollback();
                }
                else {
                    connection.rollback(savepoint);
                    connection.releaseSavepoint(savepoint);
                }
            }
            catch (Throwable rollbackFailure) {
                migrationFailure.addSuppressed(rollbackFailure);
            }
            if (migrationFailure instanceof Error) {
                throw (Error) migrationFailure;
            }
            if (migrationFailure instanceof RuntimeException) {
                throw (RuntimeException) migrationFailure;
            }
            throw DBUtils.wrapAsRuntimeException("Unable to migrate SQLite identifier", migrationFailure);
        }
        finally {
            if (restoreAutoCommit) {
                try {
                    connection.setAutoCommit(true);
                }
                catch (Throwable restoreFailure) {
                    if (failure != null) {
                        failure.addSuppressed(restoreFailure);
                    }
                    else if (restoreFailure instanceof Error) {
                        throw (Error) restoreFailure;
                    }
                    else {
                        throw DBUtils.wrapAsRuntimeException(
                                "Unable to restore auto-commit after SQLite identifier migration", restoreFailure);
                    }
                }
            }
        }
    }

    private static boolean migrateLegacyIndexTableInTransaction(
            String legacyTableName, String versionTwoTableName, Connection connection) throws SQLException {
        String mappedVersionTwoName = readIdentifierMigration(legacyTableName, connection);
        boolean legacyTableExists = indexTableExists(legacyTableName, connection);
        boolean versionTwoTableExists = indexTableExists(versionTwoTableName, connection);

        if (mappedVersionTwoName != null) {
            if (mappedVersionTwoName.equals(versionTwoTableName)) {
                if (legacyTableExists) {
                    throw new IllegalStateException("SQLite identifier migration metadata disagrees with the schema");
                }
            }
            else if (legacyTableExists) {
                throw new IllegalStateException("A claimed legacy SQLite identifier has reappeared");
            }
            return false;
        }
        if (!legacyTableExists) {
            return false;
        }
        if (versionTwoTableExists) {
            throw new IllegalStateException("Both legacy and version-two SQLite index tables exist");
        }

        createIdentifierMigrationsTable(connection);
        renameIndexTable(legacyTableName, versionTwoTableName, connection);
        dropIndexOnTable(legacyTableName, connection);
        createIndexOnTable(versionTwoTableName, connection);
        recordIdentifierMigration(legacyTableName, versionTwoTableName, connection);
        return true;
    }

    private static String readIdentifierMigration(String legacyTableName, Connection connection) throws SQLException {
        if (!actualTableExists(IDENTIFIER_MIGRATIONS_TABLE, connection)) {
            return null;
        }
        String sql = "SELECT v2_component FROM \"" + IDENTIFIER_MIGRATIONS_TABLE
                + "\" WHERE legacy_component = ?;";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, legacyTableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static boolean actualTableExists(String actualTableName, Connection connection) throws SQLException {
        String sql = "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?;";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, actualTableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void createIdentifierMigrationsTable(Connection connection) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS \"" + IDENTIFIER_MIGRATIONS_TABLE + "\" ("
                + "legacy_component TEXT PRIMARY KEY NOT NULL, "
                + "v2_component TEXT UNIQUE NOT NULL);";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void renameIndexTable(
            String legacyTableName, String versionTwoTableName, Connection connection) throws SQLException {
        String sql = "ALTER TABLE " + tableIdentifier(legacyTableName)
                + " RENAME TO " + tableIdentifier(versionTwoTableName) + ";";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void recordIdentifierMigration(
            String legacyTableName, String versionTwoTableName, Connection connection) throws SQLException {
        String sql = "INSERT INTO \"" + IDENTIFIER_MIGRATIONS_TABLE
                + "\" (legacy_component, v2_component) VALUES (?, ?);";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, legacyTableName);
            statement.setString(2, versionTwoTableName);
            statement.executeUpdate();
        }
    }

    /**
     * Represents a table row (objectId, value).
     *
     * @param <K> The type of the objectId.
     * @param <A> The type of the value.
     */
    public static class Row<K, A>{
        private final K objectKey;
        private final A value;

        public Row(K objectKey, A value) {
            this.objectKey = objectKey;
            this.value = value;
        }

        public K getObjectKey() {
            return objectKey;
        }

        public A getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Row<?, ?> row = (Row<?, ?>) o;

            if (!objectKey.equals(row.objectKey)) return false;
            if (!value.equals(row.value)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = objectKey.hashCode();
            result = 31 * result + value.hashCode();
            return result;
        }
    }

    public static <K, A> void createIndexTable(final String tableName, final Class<K> objectKeyClass, final Class<A> valueClass, final Connection connection){

        final String tableIdentifier = tableIdentifier(tableName);
        final String objectKeySQLiteType = DBUtils.getDBTypeForClass(objectKeyClass);
        final String objectValueSQLiteType = DBUtils.getDBTypeForClass(valueClass);

        final String sqlCreateTable = String.format(
                "CREATE TABLE IF NOT EXISTS %s (objectKey %s, value %s, PRIMARY KEY (objectKey, value)) WITHOUT ROWID;",
                tableIdentifier,
                objectKeySQLiteType,
                objectValueSQLiteType);

        Statement statement = null;

        try {
            statement = connection.createStatement();
            statement.executeUpdate(sqlCreateTable);
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to create index table: " + tableName, e);
        }finally {
            DBUtils.closeQuietly(statement);
        }
    }

    public static boolean indexTableExists(final String tableName, final Connection connection) {
        final String selectSql = String.format(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='%s';", tableNameValue(tableName));
        Statement statement = null;
        ResultSet resultSet = null;
        try{
            statement = connection.createStatement();
            resultSet = statement.executeQuery(selectSql);
            return resultSet.next();
        }catch(Exception e){
            throw DBUtils.wrapAsRuntimeException("Unable to determine if table exists: " + tableName, e);
        }
        finally {
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(statement);
        }
    }

    public static void createIndexOnTable(final String tableName, final Connection connection){
        final String sqlCreateIndex = String.format(
                "CREATE INDEX IF NOT EXISTS %s ON %s (value);",
                indexIdentifier(tableName),
                tableIdentifier(tableName));
        Statement statement = null;

        try {
            statement = connection.createStatement();
            statement.executeUpdate(sqlCreateIndex);
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to add index on table: " + tableName, e);
        }finally {
            DBUtils.closeQuietly(statement);
        }
    }

    public static void suspendSyncAndJournaling(final Connection connection){
        setSyncAndJournaling(connection, SQLiteConfig.SynchronousMode.OFF, SQLiteConfig.JournalMode.OFF);
    }

    public static void setSyncAndJournaling(final Connection connection, final SQLiteConfig.SynchronousMode pragmaSynchronous, final SQLiteConfig.JournalMode pragmaJournalMode){
        Statement statement = null;
        try {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("Cannot change SQLite sync and journaling inside an active request transaction");
            }
            statement = connection.createStatement();
            statement.execute("PRAGMA synchronous = " + pragmaSynchronous.getValue());

            // This little transaction will also cause a wanted fsync on the OS to flush the data still in the OS cache to disc.
            statement.execute("PRAGMA journal_mode = " + pragmaJournalMode.getValue());
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to set the 'synchronous' and 'journal_mode' pragmas", e);
        }finally{
            DBUtils.closeQuietly(statement);
        }
    }

    public static SQLiteConfig.SynchronousMode getPragmaSynchronousOrNull(final Connection connection){
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.createStatement();
            resultSet = statement.executeQuery("PRAGMA synchronous;");
            if (resultSet.next()){
                final int syncPragmaId = resultSet.getInt(1);
                if (!resultSet.wasNull()) {
                    switch (syncPragmaId){
                        case 0: return SQLiteConfig.SynchronousMode.OFF;
                        case 1: return SQLiteConfig.SynchronousMode.NORMAL;
                        case 2: return SQLiteConfig.SynchronousMode.FULL;
                        default: return null;
                    }
                }
            }
            return null;
        }catch (Exception e){
            DBUtils.rethrowIfBusy("Unable to read the 'synchronous' pragma", e);
            return null;
        }finally{
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(statement);
        }
    }

    public static SQLiteConfig.JournalMode getPragmaJournalModeOrNull(final Connection connection){
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.createStatement();
            resultSet = statement.executeQuery("PRAGMA journal_mode;");
            if (resultSet.next()){
                final String journalMode = resultSet.getString(1);
                return journalMode != null
                        ? SQLiteConfig.JournalMode.valueOf(journalMode.toUpperCase(Locale.ROOT))
                        : null;
            }
            return null;
        }catch (Exception e){
            DBUtils.rethrowIfBusy("Unable to read the 'journal_mode' pragma", e);
            return null;
        }finally{
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(statement);
        }
    }

    public static void dropIndexOnTable(final String tableName, final Connection connection){
        final String sqlDropIndex = String.format("DROP INDEX IF EXISTS %s;", indexIdentifier(tableName));
        Statement statement = null;

        try {
            statement = connection.createStatement();
            statement.executeUpdate(sqlDropIndex);
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to drop index on table: " + tableName, e);
        }finally {
            DBUtils.closeQuietly(statement);
        }
    }

    public static void dropIndexTable(final String tableName, final Connection connection){
        final String sqlDropIndex = String.format("DROP INDEX IF EXISTS %s;", indexIdentifier(tableName));
        final String sqlDropTable = String.format("DROP TABLE IF EXISTS %s;", tableIdentifier(tableName));
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.executeUpdate(sqlDropIndex);
            statement.executeUpdate(sqlDropTable);
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to drop index table: "+ tableName, e);
        }finally{
            DBUtils.closeQuietly(statement);
        }
    }

    public static void clearIndexTable(final String tableName, final Connection connection){
        final String clearTable = String.format("DELETE FROM %s;", tableIdentifier(tableName));
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.executeUpdate(clearTable);
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to clear index table: " + tableName, e);
        }finally{
            DBUtils.closeQuietly(statement);
        }
    }

    public static void compactDatabase(final Connection connection){
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("VACUUM;");
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to compact database", e);
        }finally{
            DBUtils.closeQuietly(statement);
        }
    }

    public static void expandDatabase(final Connection connection, long numBytes) {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("CREATE TABLE IF NOT EXISTS cq_expansion (val);");
            statement.execute("INSERT INTO cq_expansion VALUES (zeroblob(" + numBytes + "));");
            statement.execute("DROP TABLE cq_expansion;");

        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to expand database by bytes: " + numBytes, e);
        }finally{
            DBUtils.closeQuietly(statement);
        }
    }

    public static long getDatabaseSize(final Connection connection){
        long pageCount = readPragmaLong(connection, "PRAGMA page_count;");
        long pageSize = readPragmaLong(connection, "PRAGMA page_size;");
        return pageCount * pageSize;
    }

    static long readPragmaLong(final Connection connection, String query) {
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.createStatement();
            resultSet = statement.executeQuery(query);
            if (!resultSet.next()){
                throw new IllegalStateException("Unable to read long from pragma query. The ResultSet returned no row. Query: " + query);
            }
            return resultSet.getLong(1);
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to read long from pragma query", e);
        }finally{
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(statement);
        }
    }

    public static <K,A> int bulkAdd(Iterable<Row<K, A>> rows, final String tableName, final Connection connection){
        final String sql = String.format("INSERT OR IGNORE INTO %s values(?, ?);", tableIdentifier(tableName));
        PreparedStatement statement = null;
        int totalRowsModified = 0;
        try {
            statement = connection.prepareStatement(sql);

            for (Row<K, A> row : rows) {
                statement.setObject(1, row.getObjectKey());
                statement.setObject(2, row.getValue());
                statement.addBatch();
            }
            int[] rowsModified = statement.executeBatch();
            for (int m : rowsModified) {
                ensureNotNegative(m);
                totalRowsModified += m;
            }
            return totalRowsModified;
        }
        catch (NullPointerException e) {
            // Note: here we catch a and rethrow NullPointerException,
            // to allow compatibility with Java Collections Framework,
            // which requires NPE to be thrown for null arguments...
            NullPointerException npe = new NullPointerException("Unable to bulk add rows containing a null object to the index table: "+ tableName);
            npe.initCause(e);
            throw npe;
        }
        catch (Exception e){
            throw DBUtils.wrapAsRuntimeException("Unable to bulk add rows to the index table: "+ tableName, e);
        }finally {
            DBUtils.closeQuietly(statement);
        }
    }

    public static <K> int bulkRemove(Iterable<K> objectKeys, final String tableName, final Connection connection){
        final String sql = String.format("DELETE FROM %s WHERE objectKey = ?;", tableIdentifier(tableName));
        PreparedStatement statement = null;
        int totalRowsModified = 0;
        try{
            statement = connection.prepareStatement(sql);
            for(K objectKey: objectKeys) {
                statement.setObject(1, objectKey);
                statement.addBatch();
            }
            int[] rowsModified = statement.executeBatch();
            for (int m : rowsModified) {
                ensureNotNegative(m);
                totalRowsModified += m;
            }
            return totalRowsModified;
        }
        catch (NullPointerException e) {
            // Note: here we catch a and rethrow NullPointerException,
            // to allow compatibility with Java Collections Framework,
            // which requires NPE to be thrown for null arguments...
            NullPointerException npe = new NullPointerException("Unable to bulk remove rows containing a null object from the index table: "+ tableName);
            npe.initCause(e);
            throw npe;
        }
        catch (Exception e){
            throw DBUtils.wrapAsRuntimeException("Unable to remove rows from the index table: " + tableName, e);
        }finally{
            DBUtils.closeQuietly(statement);
        }
    }

    static class WhereClause{
        final String whereClause;
        final Object objectToBind;
        WhereClause(String whereClause, Object objectToBind){
            this.whereClause = whereClause;
            this.objectToBind = objectToBind;
        }
    }

    static <O, A> PreparedStatement createAndBindSelectPreparedStatement(final String selectPrefix,
                                                                         final String groupingAndSorting,
                                                                         final List<WhereClause> additionalWhereClauses,
                                                                         final Query<O> query,
                                                                         final Connection connection) throws SQLException {

        int bindingIndex = 1;
        StringBuilder stringBuilder = new StringBuilder(selectPrefix).append(' ');
        StringBuilder suffix = new StringBuilder();
        final Class<?> queryClass = query.getClass();
        PreparedStatement statement;

        if (queryClass == Has.class){
            // Has is a special case, because there is no WHERE clause by default.
            if (additionalWhereClauses.isEmpty()){
                suffix.append(groupingAndSorting);
                suffix.append(';');
            }else{
                stringBuilder.append("WHERE ");
                for (Iterator<WhereClause> iterator = additionalWhereClauses.iterator(); iterator.hasNext(); ) {
                    WhereClause additionalWhereClause = iterator.next();
                    suffix.append(additionalWhereClause.whereClause);
                    if (iterator.hasNext()) {
                        suffix.append(" AND ");
                    }
                }
                suffix.append(groupingAndSorting);
                suffix.append(';');
            }
            stringBuilder.append(suffix);
            statement = connection.prepareStatement(stringBuilder.toString());

        }else {
            // Other queries have a WHERE clause by default.
            if (additionalWhereClauses.isEmpty()){
                suffix.append(groupingAndSorting);
                suffix.append(';');
            }else{
                for (WhereClause additionalWhereClause : additionalWhereClauses){
                    suffix.append(" AND ").append(additionalWhereClause.whereClause);
                }
                suffix.append(groupingAndSorting);
                suffix.append(';');
            }
            if (queryClass == Equal.class) {
                @SuppressWarnings("unchecked")
                final Equal<O, A> equal = (Equal<O, A>) query;
                stringBuilder.append("WHERE value = ?").append(suffix);
                statement = connection.prepareStatement(stringBuilder.toString());
                DBUtils.setValueToPreparedStatement(bindingIndex++, statement, equal.getValue());
            } else if (queryClass == In.class){
                @SuppressWarnings("unchecked")
                final In<O, A> in = (In<O, A>) query;
                Set<A> values = in.getValues();
                stringBuilder.append("WHERE value IN ( ");
                for (int i=0; i<values.size(); i++){
                    if (i > 0){
                        stringBuilder.append(", ");
                    }
                    stringBuilder.append("?");
                }
                stringBuilder.append(")").append(suffix);
                statement = connection.prepareStatement(stringBuilder.toString());
                bindingIndex = DBUtils.setValuesToPreparedStatement(bindingIndex, statement, values);
            } else if (queryClass == LessThan.class) {
                @SuppressWarnings("unchecked")
                final LessThan<O, ? extends Comparable<A>> lessThan = (LessThan<O, ? extends Comparable<A>>) query;
                boolean isValueInclusive = lessThan.isValueInclusive();
                if (isValueInclusive) {
                    stringBuilder.append("WHERE value <= ?").append(suffix);
                } else {
                    stringBuilder.append("WHERE value < ?").append(suffix);
                }
                statement = connection.prepareStatement(stringBuilder.toString());
                DBUtils.setValueToPreparedStatement(bindingIndex++, statement, lessThan.getValue());

            } else if (queryClass == StringStartsWith.class) {
                final StringStartsWith<O, ? extends CharSequence> stringStartsWith = (StringStartsWith<O, ? extends CharSequence>) query;
                stringBuilder.append("WHERE value >= ? AND value < ?").append(suffix);
                final String lowerBoundInclusive = CharSequences.toString(stringStartsWith.getValue());
                final int len = lowerBoundInclusive.length();
                final String allButLast = lowerBoundInclusive.substring(0, len - 1);
                final String upperBoundExclusive = allButLast + Character.toChars(lowerBoundInclusive.charAt(len - 1) + 1)[0];

                statement = connection.prepareStatement(stringBuilder.toString());
                DBUtils.setValueToPreparedStatement(bindingIndex++, statement, lowerBoundInclusive);
                DBUtils.setValueToPreparedStatement(bindingIndex++, statement, upperBoundExclusive);

            } else if (queryClass == GreaterThan.class) {
                @SuppressWarnings("unchecked")
                final GreaterThan<O, ? extends Comparable<A>> greaterThan = (GreaterThan<O, ? extends Comparable<A>>) query;
                boolean isValueInclusive = greaterThan.isValueInclusive();
                if (isValueInclusive) {
                    stringBuilder.append("WHERE value >= ?").append(suffix);
                } else {
                    stringBuilder.append("WHERE value > ?").append(suffix);
                }
                statement = connection.prepareStatement(stringBuilder.toString());
                DBUtils.setValueToPreparedStatement(bindingIndex++, statement, greaterThan.getValue());

            } else if (queryClass == Between.class) {
                @SuppressWarnings("unchecked")
                final Between<O, ? extends Comparable<A>> between = (Between<O, ? extends Comparable<A>>) query;
                if (between.isLowerInclusive()) {
                    stringBuilder.append("WHERE value >= ?");
                } else {
                    stringBuilder.append("WHERE value > ?");
                }
                if (between.isUpperInclusive()) {
                    stringBuilder.append(" AND value <= ?");
                } else {
                    stringBuilder.append(" AND value < ?");
                }
                stringBuilder.append(suffix);
                statement = connection.prepareStatement(stringBuilder.toString());
                DBUtils.setValueToPreparedStatement(bindingIndex++, statement, between.getLowerValue());
                DBUtils.setValueToPreparedStatement(bindingIndex++, statement, between.getUpperValue());

            } else {
                throw new IllegalStateException("Query " + queryClass + " not supported.");
            }
        }

        for (WhereClause additionalWhereClause : additionalWhereClauses){
            DBUtils.setValueToPreparedStatement(bindingIndex++, statement, additionalWhereClause.objectToBind);
        }

        return statement;
    }

    public static <O> int count(final Query<O> query, final String tableName, final Connection connection) {

        final String selectSql = String.format("SELECT COUNT(objectKey) FROM %s", tableIdentifier(tableName));
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = createAndBindSelectPreparedStatement(selectSql, "", Collections.<WhereClause>emptyList(), query, connection);
            resultSet = statement.executeQuery();

            if (!resultSet.next()) {
                throw new IllegalStateException("Unable to execute count. The ResultSet returned no row. Query: " + query);
            }

            return resultSet.getInt(1);
        }
        catch (Exception e) {
            throw DBUtils.wrapAsRuntimeException("Unable to execute count. Query: " + query, e);
        }
        finally {
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(statement);
        }
    }

    public static <O> int countDistinct(final Query<O> query, final String tableName, final Connection connection){

        // NOTE: Using GROUP BY is much faster than using SELECT DISTINCT in SQLite for deduplication
        final String selectSql = String.format(
                "SELECT COUNT(1) AS countDistinct FROM (SELECT objectKey FROM %s", tableIdentifier(tableName));
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try{
            statement = createAndBindSelectPreparedStatement(selectSql, " GROUP BY objectKey)", Collections.<WhereClause>emptyList(), query, connection);
            resultSet = statement.executeQuery();

            if (!resultSet.next()){
                throw new IllegalStateException("Unable to execute count. The ResultSet returned no row. Query: " + query);
            }

            return resultSet.getInt(1);
        }catch(Exception e){
            throw DBUtils.wrapAsRuntimeException("Unable to execute count. Query: " + query, e);
        }finally {
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(statement);
        }

    }

    public static <O> java.sql.ResultSet search(final Query<O> query, final String tableName, final Connection connection){
        final String selectSql = String.format("SELECT DISTINCT objectKey FROM %s", tableIdentifier(tableName));
        PreparedStatement statement = null;
        try{
            statement = createAndBindSelectPreparedStatement(selectSql, "", Collections.<WhereClause>emptyList(), query, connection);
            return statement.executeQuery();
        }catch(Exception e){
            DBUtils.closeQuietly(statement);
            throw DBUtils.wrapAsRuntimeException("Unable to execute search. Query: " + query, e);
        }
        // In case of success we leave the statement and result-set open because the iteration of an Index ResultSet is lazy.

    }

    public static <O> java.sql.ResultSet getDistinctKeys(final Query<O> query, boolean descending, final String tableName, final Connection connection){
        final String selectSql = String.format("SELECT DISTINCT value FROM %s", tableIdentifier(tableName));
        PreparedStatement statement = null;
        try{
            String orderByClause = descending ? " ORDER BY value DESC" : " ORDER BY value ASC";
            statement = createAndBindSelectPreparedStatement(selectSql, orderByClause, Collections.<WhereClause>emptyList(), query, connection);
            return statement.executeQuery();
        }catch(Exception e){
            DBUtils.closeQuietly(statement);
            throw DBUtils.wrapAsRuntimeException("Unable to look up keys. Query: " + query, e);
        }
        // In case of success we leave the statement and result-set open because the iteration of an Index ResultSet is lazy.

    }

    public static <O> java.sql.ResultSet getKeysAndValues(final Query<O> query, boolean descending, final String tableName, final Connection connection){
        final String selectSql = String.format("SELECT objectKey, value FROM %s", tableIdentifier(tableName));
        PreparedStatement statement = null;
        try{
            String orderByClause = descending ? " ORDER BY value DESC" : " ORDER BY value ASC";
            statement = createAndBindSelectPreparedStatement(selectSql, orderByClause, Collections.<WhereClause>emptyList(), query, connection);
            return statement.executeQuery();
        }catch(Exception e){
            DBUtils.closeQuietly(statement);
            throw DBUtils.wrapAsRuntimeException("Unable to look up keys and values. Query: " + query, e);
        }
        // In case of success we leave the statement and result-set open because the iteration of an Index ResultSet is lazy.

    }

    public static int getCountOfDistinctKeys(final String tableName, final Connection connection){
        final String selectSql = String.format("SELECT COUNT(DISTINCT value) FROM %s", tableIdentifier(tableName));
        Statement statement = null;
        ResultSet resultSet = null;
        try{
            statement = connection.createStatement();
            resultSet = statement.executeQuery(selectSql);
            if (!resultSet.next()){
                throw new IllegalStateException("Unable to execute count. The ResultSet returned no row. Query: " + selectSql);
            }

            return resultSet.getInt(1);
        }catch(Exception e){
            throw DBUtils.wrapAsRuntimeException("Unable to count distinct keys.", e);
        }finally{
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(statement);
        }
    }

    public static java.sql.ResultSet getDistinctKeysAndCounts(boolean sortByKeyDescending, final String tableName, final Connection connection){
        final String selectSql = String.format(
                "SELECT DISTINCT value, COUNT(value) AS valueCount FROM %s GROUP BY (value) %s",
                tableIdentifier(tableName),
                sortByKeyDescending ? "ORDER BY value DESC" : "");
        Statement statement = null;
        try{
            statement = connection.createStatement();
            return statement.executeQuery(selectSql);
        }catch(Exception e){
            DBUtils.closeQuietly(statement);
            throw DBUtils.wrapAsRuntimeException("Unable to look up index entries and counts.", e);
        }
        // In case of success we leave the statement and result-set open because the iteration of an Index ResultSet is lazy.
    }

    public static java.sql.ResultSet getAllIndexEntries(final String tableName, final Connection connection){
        final String selectSql = String.format(
                "SELECT objectKey, value FROM %s ORDER BY objectKey;", tableIdentifier(tableName));
        Statement statement = null;
        try{
            statement = connection.createStatement();
            return statement.executeQuery(selectSql);
        }catch(Exception e){
            DBUtils.closeQuietly(statement);
            throw DBUtils.wrapAsRuntimeException("Unable to look up index entries.", e);
        }
        // In case of success we leave the statement and result-set open because the iteration of an Index ResultSet is lazy.

    }

    public static <K> java.sql.ResultSet getIndexEntryByObjectKey(final K key , final String tableName, final Connection connection){
        final String selectSql = String.format(
                "SELECT objectKey, value FROM %s WHERE objectKey = ?", tableIdentifier(tableName));
        PreparedStatement statement = null;
        try{
            statement = connection.prepareStatement(selectSql);
            DBUtils.setValueToPreparedStatement(1, statement, key);
            return statement.executeQuery();
        }catch(Exception e){
            DBUtils.closeQuietly(statement);
            throw DBUtils.wrapAsRuntimeException("Unable to look up index entries.", e);
        }
        // In case of success we leave the statement and result-set open because the iteration of an Index ResultSet is lazy.

    }

    public static <K, O> boolean contains(final K objectKey, final Query<O> query, final String tableName, final Connection connection){
        final String selectSql = String.format("SELECT objectKey FROM %s", tableIdentifier(tableName));
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try{
            List<WhereClause> additionalWhereClauses = Collections.singletonList(new WhereClause("objectKey = ?", objectKey));
            statement = createAndBindSelectPreparedStatement(selectSql, " LIMIT 1", additionalWhereClauses, query, connection);
            resultSet = statement.executeQuery();
            return resultSet.next();
        }catch (SQLException e){
            throw DBUtils.wrapAsRuntimeException("Unable to execute contains. Query: " + query, e);
        }finally{
            DBUtils.closeQuietly(resultSet);
            DBUtils.closeQuietly(statement);
        }
    }

    static void ensureNotNegative(int value) {
        if (value < 0) throw new IllegalStateException("Update returned error code: " + value);
    }
}
