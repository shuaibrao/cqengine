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
import com.googlecode.cqengine.index.sqlite.SQLiteBusyException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A bunch of useful database utilities.
 *
 * @author Silvano Riz
 */
public class DBUtils {

    /** Maximum length accepted for an application-derived SQLite identifier component or suffix. */
    public static final int MAX_SQLITE_IDENTIFIER_COMPONENT_LENGTH = 255;

    /**
     * Preserves sqlite-jdbc busy results as a distinct CQEngine exception while retaining the existing runtime
     * exception contract for every other failure.
     *
     * @param message description of the CQEngine operation which failed
     * @param failure failure to inspect, including its cause chain
     * @return the existing or newly mapped runtime failure
     */
    public static RuntimeException wrapAsRuntimeException(String message, Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof SQLiteBusyException) {
                return (SQLiteBusyException) current;
            }
            if (current instanceof SQLiteException) {
                SQLiteException sqliteException = (SQLiteException) current;
                if (sqliteException.getErrorCode() == SQLiteErrorCode.SQLITE_BUSY.code) {
                    return new SQLiteBusyException(message, sqliteException);
                }
            }
            current = current.getCause();
        }
        return new IllegalStateException(message, failure);
    }

    /** Rethrows the given failure only if it represents an SQLite busy result. */
    static void rethrowIfBusy(String message, Throwable failure) {
        RuntimeException mappedFailure = wrapAsRuntimeException(message, failure);
        if (mappedFailure instanceof SQLiteBusyException) {
            throw mappedFailure;
        }
    }

    public static Closeable wrapAsCloseable(final ResultSet resultSet){
        return new Closeable() {
            @Override
            public void close() throws IOException {
                DBUtils.closeQuietly(resultSet);
            }
        };
    }

    public static boolean setAutoCommit(final Connection connection, final boolean value){
        try {
            boolean previousValue = connection.getAutoCommit();
            connection.setAutoCommit(value);
            return previousValue;
        }catch (Exception e){
            throw wrapAsRuntimeException("Unable to set the Connection autoCommit to " + value, e);
        }
    }

    public static void commit(final Connection connection){
        try {
            connection.commit();
        }catch (Exception e){
            throw wrapAsRuntimeException("Commit failed", e);
        }
    }

    public static boolean rollback(final Connection connection){
        try {
            connection.rollback();
            return true;
        }catch (Exception e){
            return false;
        }
    }

    public static void rollbackOrThrow(final Connection connection){
        try {
            connection.rollback();
        }catch (Exception e){
            throw wrapAsRuntimeException("Rollback failed", e);
        }
    }

    public static void closeQuietly(java.sql.ResultSet resultSet){
        if (resultSet == null)
            return;

        try {
            Statement statement = resultSet.getStatement();
            if (statement != null){
                statement.close();
            }
        }catch(Exception e){
            // Ignore
        }

        try{
            resultSet.close();
        }catch (Exception e){
            // Ignore
        }
    }

    public static void closeQuietly(Statement statement){
        if (statement == null)
            return;
        try{
            statement.close();
        }catch (Exception e){
            // Ignore
        }
    }

    public static void closeQuietly(Connection connection){
        if (connection == null)
            return;
        try{
            connection.close();
        }catch (Exception e){
            // Ignore
        }
    }

    public static String getDBTypeForClass(final Class<?> valueType){

        if ( CharSequence.class.isAssignableFrom(valueType) || BigDecimal.class.isAssignableFrom(valueType)) {
            return "TEXT";

        }else if (Long.class.isAssignableFrom(valueType) || Integer.class.isAssignableFrom(valueType) || Short.class.isAssignableFrom(valueType) || Boolean.class.isAssignableFrom(valueType) || Date.class.isAssignableFrom(valueType)) {
            return "INTEGER";

        }else if (Float.class.isAssignableFrom(valueType) || Double.class.isAssignableFrom(valueType)){
            return "REAL";

        }else if (valueType == byte[].class){
            return "BLOB";

        }else{
            throw new IllegalStateException("Type " + valueType + " not supported.");
        }
    }

    public static void setValueToPreparedStatement(int index, final PreparedStatement preparedStatement, Object value) throws SQLException {

        if (value instanceof Date) {
            preparedStatement.setLong(index, ((Date) value).getTime());

        }else if(value instanceof CharSequence){
            preparedStatement.setString(index, CharSequences.toString((CharSequence)value));

        }else{
            preparedStatement.setObject(index, value);
        }
    }

    /**
     * <p> Binds a set of values to the statement.
     *
     * @param startIndex parameter index from where to start the binding.
     * @param preparedStatement the prepared statement.
     * @param values The values to bind
     * @return The new start index.
     * @throws SQLException if the binding fails.
     */
    @SuppressWarnings("rawtypes") // Retains the legacy public Iterable parameter signature.
    public static int setValuesToPreparedStatement(final int startIndex, final PreparedStatement preparedStatement, final Iterable values) throws SQLException {
        int index = startIndex;
        for (Object value : values){
            setValueToPreparedStatement(index++, preparedStatement, value);
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    public static <T>T getValueFromResultSet(int index, final ResultSet resultSet, final Class<T> type){

        try {
            if (java.sql.Date.class.isAssignableFrom(type)) {
                final long time = resultSet.getLong(index);
                return (T)new java.sql.Date(time);

            } else if (Time.class.isAssignableFrom(type)) {
                final long time = resultSet.getLong(index);
                return (T)new java.sql.Time(time);

            } else if (Timestamp.class.isAssignableFrom(type)) {
                final long time = resultSet.getLong(index);
                return (T)new java.sql.Timestamp(time);

            }else if (Date.class.isAssignableFrom(type)) {
                final long time = resultSet.getLong(index);
                return (T)new Date(time);

            } else if (Long.class.isAssignableFrom(type)) {
                return (T) Long.valueOf(resultSet.getLong(index));

            } else if (Integer.class.isAssignableFrom(type)) {
                return (T) Integer.valueOf(resultSet.getInt(index));

            } else if (Short.class.isAssignableFrom(type)) {
                return (T) Short.valueOf(resultSet.getShort(index));

            } else if (Float.class.isAssignableFrom(type)) {
                return (T) Float.valueOf(resultSet.getFloat(index));

            } else if (Double.class.isAssignableFrom(type)) {
                return (T) Double.valueOf(resultSet.getDouble(index));

            } else if (Boolean.class.isAssignableFrom(type)) {
                return (T) Boolean.valueOf(resultSet.getBoolean(index));

            } else if (BigDecimal.class.isAssignableFrom(type)) {
                return (T) resultSet.getBigDecimal(index);

            } else if (CharSequence.class.isAssignableFrom(type)) {
                return (T) resultSet.getString(index);

            } else if (byte[].class.isAssignableFrom(type)) {
                return (T) resultSet.getBytes(index);

            } else {
                throw new IllegalStateException("Type " + type + " not supported.");
            }
        }catch (Exception e){
            throw wrapAsRuntimeException(
                    "Unable to read the value from the resultSet. Index:" + index + ", type: " + type, e);
        }
    }

    /**
     * Validates a non-empty component used to construct CQEngine-owned SQLite table and index names.
     *
     * @return the input unchanged
     */
    public static String validateSQLiteIdentifierComponent(String input) {
        return validateSQLiteIdentifierComponent(input, false, "SQLite identifier component");
    }

    /**
     * Validates an optional suffix used to construct a CQEngine-owned SQLite table name.
     *
     * @return the input unchanged
     */
    public static String validateSQLiteIdentifierSuffix(String input) {
        return validateSQLiteIdentifierComponent(input, true, "SQLite identifier suffix");
    }

    static String validateSQLiteIdentifierComponent(String input, boolean allowEmpty, String description) {
        if (input == null) {
            throw new NullPointerException(description + " cannot be null");
        }
        if (!allowEmpty && input.isEmpty()) {
            throw new IllegalArgumentException(description + " cannot be empty");
        }
        if (input.length() > MAX_SQLITE_IDENTIFIER_COMPONENT_LENGTH) {
            throw new IllegalArgumentException(
                    description + " cannot exceed " + MAX_SQLITE_IDENTIFIER_COMPONENT_LENGTH + " ASCII characters");
        }
        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);
            boolean allowed = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_';
            if (!allowed) {
                throw new IllegalArgumentException(
                        description + " may contain only ASCII letters, digits, and underscore");
            }
        }
        return input;
    }

    /**
     * Deletes non-alphanumeric characters to retain CQEngine's legacy persisted-name mapping.
     * This is not validation: distinct inputs can produce the same output.
     */
    public static String sanitizeForTableName(String input) {
        return input.replaceAll("[^A-Za-z0-9]", "");
    }

    /**
     * Creates the version-two table-name component for a logical SQLite index.
     *
     * <p>The digest includes length-delimited UTF-16 code units for both inputs. This avoids the delimiter,
     * normalization and malformed-surrogate ambiguities which would be possible if the Java strings were first
     * concatenated or converted with a replacement-based character encoder.</p>
     */
    public static String createSQLiteIndexTableNameV2(String attributeName, String tableNameSuffix) {
        if (attributeName == null) {
            throw new NullPointerException("Attribute name cannot be null");
        }
        if (attributeName.isEmpty()) {
            throw new IllegalArgumentException("Attribute name cannot be empty");
        }
        validateSQLiteIdentifierSuffix(tableNameSuffix);

        MessageDigest digest = sha256();
        digest.update("CQEngine SQLite index name V2".getBytes(StandardCharsets.US_ASCII));
        updateLengthDelimitedUtf16(digest, attributeName);
        updateLengthDelimitedUtf16(digest, tableNameSuffix);
        return "v2_" + HexFormat.of().formatHex(digest.digest());
    }

    /** Creates a collision-resistant suffix for an internally generated partial index. */
    public static String createPartialIndexTableNameSuffixV2(String filterQueryDescription) {
        if (filterQueryDescription == null) {
            throw new NullPointerException("Filter query description cannot be null");
        }
        MessageDigest digest = sha256();
        digest.update("CQEngine SQLite partial index V2".getBytes(StandardCharsets.US_ASCII));
        updateLengthDelimitedUtf16(digest, filterQueryDescription);
        return "_partial_v2_" + HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required SHA-256 implementation is unavailable", e);
        }
    }

    private static void updateLengthDelimitedUtf16(MessageDigest digest, String value) {
        int length = value.length();
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        for (int i = 0; i < length; i++) {
            char character = value.charAt(i);
            digest.update((byte) (character >>> 8));
            digest.update((byte) character);
        }
    }

}
