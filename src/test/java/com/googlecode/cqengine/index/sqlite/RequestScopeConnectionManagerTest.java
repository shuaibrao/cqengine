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
package com.googlecode.cqengine.index.sqlite;

import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.persistence.RequestScopeTransactionOutcome;
import com.googlecode.cqengine.persistence.support.PersistenceFlags;
import com.googlecode.cqengine.persistence.support.sqlite.LockReleasingConnection;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RequestScopeConnectionManagerTest {

    @Test
    public void successfulRequestCommitsAndCloses() {
        ConnectionRecorder connection = new ConnectionRecorder();
        Fixture fixture = fixture(connection.proxy());

        TestAssertions.assertSame(connection.proxy, fixture.manager.getConnection(fixture.index, new QueryOptions()));
        TestAssertions.assertFalse(connection.autoCommit);

        fixture.manager.close(RequestScopeTransactionOutcome.COMMIT);

        TestAssertions.assertEquals(1, connection.commitCalls);
        TestAssertions.assertEquals(0, connection.rollbackCalls);
        TestAssertions.assertEquals(1, connection.closeCalls);
        TestAssertions.assertTrue(fixture.manager.openConnections.isEmpty());

        fixture.manager.close(RequestScopeTransactionOutcome.COMMIT);
        TestAssertions.assertEquals(1, connection.closeCalls);
        assertClosedManagerRejectsAcquisition(fixture);
    }

    @Test
    public void failedRequestRollsBackAndCloses() {
        ConnectionRecorder connection = new ConnectionRecorder();
        Fixture fixture = fixture(connection.proxy());
        fixture.manager.getConnection(fixture.index, new QueryOptions());

        fixture.manager.close(RequestScopeTransactionOutcome.ROLLBACK);

        TestAssertions.assertEquals(0, connection.commitCalls);
        TestAssertions.assertEquals(1, connection.rollbackCalls);
        TestAssertions.assertEquals(1, connection.closeCalls);
        TestAssertions.assertTrue(fixture.manager.openConnections.isEmpty());
    }

    @Test
    public void checkpointCommitKeepsConnectionOpenForContinuedUse() {
        ConnectionRecorder connection = new ConnectionRecorder();
        Fixture fixture = fixture(connection.proxy());
        Connection acquired = fixture.manager.getConnection(fixture.index, new QueryOptions());

        fixture.manager.commitOpenTransactions();

        TestAssertions.assertEquals(1, connection.commitCalls);
        TestAssertions.assertEquals(0, connection.rollbackCalls);
        TestAssertions.assertEquals(0, connection.closeCalls);
        TestAssertions.assertSame(acquired, fixture.manager.getConnection(fixture.index, new QueryOptions()));

        fixture.manager.close(RequestScopeTransactionOutcome.COMMIT);
        TestAssertions.assertEquals(2, connection.commitCalls);
        TestAssertions.assertEquals(1, connection.closeCalls);
    }

    @Test
    public void acquisitionFailureClosesConnectionWithoutPublishingIt() {
        ConnectionRecorder connection = new ConnectionRecorder();
        connection.setAutoCommitFailure = new SQLException("set auto-commit failed");
        Fixture fixture = fixture(connection.proxy());

        try {
            fixture.manager.getConnection(fixture.index, new QueryOptions());
            TestAssertions.fail("Expected acquisition to fail");
        }
        catch (IllegalStateException expected) {
            TestAssertions.assertTrue(expected.getMessage().contains("autoCommit"));
        }

        TestAssertions.assertEquals(1, connection.closeCalls);
        TestAssertions.assertTrue(fixture.manager.openConnections.isEmpty());
    }

    @Test
    public void getAutoCommitFailureClosesConnectionWithoutPublishingIt() {
        ConnectionRecorder connection = new ConnectionRecorder();
        connection.getAutoCommitFailure = new SQLException("get auto-commit failed");
        Fixture fixture = fixture(connection.proxy());

        try {
            fixture.manager.getConnection(fixture.index, new QueryOptions());
            TestAssertions.fail("Expected acquisition to fail");
        }
        catch (IllegalStateException expected) {
            TestAssertions.assertTrue(expected.getMessage().contains("autoCommit"));
        }

        TestAssertions.assertEquals(1, connection.closeCalls);
        TestAssertions.assertTrue(fixture.manager.openConnections.isEmpty());
    }

    @Test
    public void commitFailureRollsBackRemainingConnectionsAndAttemptsEveryCleanup() {
        RequestScopeConnectionManager manager = new RequestScopeConnectionManager(mock(SQLitePersistence.class));
        SQLitePersistence<?, ?> firstPersistence = mock(SQLitePersistence.class);
        SQLitePersistence<?, ?> secondPersistence = mock(SQLitePersistence.class);
        ConnectionRecorder first = new ConnectionRecorder();
        ConnectionRecorder second = new ConnectionRecorder();
        first.commitFailure = new SQLException("commit failed");
        first.rollbackFailure = new SQLException("rollback failed");
        first.closeFailure = new SQLException("close failed");
        second.rollbackFailure = new SQLException("second rollback failed");
        manager.openConnections.put(firstPersistence, new RequestScopeConnectionManager.ManagedConnection(first.proxy()));
        manager.openConnections.put(secondPersistence, new RequestScopeConnectionManager.ManagedConnection(second.proxy()));

        IllegalStateException failure = null;
        try {
            manager.close(RequestScopeTransactionOutcome.COMMIT);
            TestAssertions.fail("Expected commit to fail");
        }
        catch (IllegalStateException expected) {
            failure = expected;
        }

        TestAssertions.assertNotNull(failure);
        TestAssertions.assertEquals("Commit failed", failure.getMessage());
        TestAssertions.assertEquals(3, failure.getSuppressed().length);
        TestAssertions.assertEquals(1, first.commitCalls);
        TestAssertions.assertEquals(1, first.rollbackCalls);
        TestAssertions.assertEquals(1, first.closeCalls);
        TestAssertions.assertEquals(0, second.commitCalls);
        TestAssertions.assertEquals(1, second.rollbackCalls);
        TestAssertions.assertEquals(1, second.closeCalls);
        TestAssertions.assertTrue(manager.openConnections.isEmpty());
    }

    @Test
    public void laterCommitFailureCannotRollBackAnEarlierCommittedPersistence() {
        RequestScopeConnectionManager manager = new RequestScopeConnectionManager(mock(SQLitePersistence.class));
        SQLitePersistence<?, ?> firstPersistence = mock(SQLitePersistence.class);
        SQLitePersistence<?, ?> secondPersistence = mock(SQLitePersistence.class);
        ConnectionRecorder first = new ConnectionRecorder();
        ConnectionRecorder second = new ConnectionRecorder();
        second.commitFailure = new SQLException("second commit failed");
        manager.openConnections.put(firstPersistence, new RequestScopeConnectionManager.ManagedConnection(first.proxy()));
        manager.openConnections.put(secondPersistence, new RequestScopeConnectionManager.ManagedConnection(second.proxy()));

        try {
            manager.close(RequestScopeTransactionOutcome.COMMIT);
            TestAssertions.fail("Expected the second commit to fail");
        }
        catch (IllegalStateException expected) {
            TestAssertions.assertEquals("Commit failed", expected.getMessage());
        }

        TestAssertions.assertEquals(1, first.commitCalls);
        TestAssertions.assertEquals(0, first.rollbackCalls);
        TestAssertions.assertEquals(1, second.commitCalls);
        TestAssertions.assertEquals(1, second.rollbackCalls);
        TestAssertions.assertEquals(1, first.closeCalls);
        TestAssertions.assertEquals(1, second.closeCalls);
    }

    @Test
    public void onlyExplicitDeferredWritesUseImmediateTransactions() throws Exception {
        assertTransactionMode(
                SQLiteConfig.TransactionMode.DEFERRED,
                PersistenceFlags.WRITE_REQUEST,
                SQLiteConfig.TransactionMode.IMMEDIATE);
        assertTransactionMode(
                SQLiteConfig.TransactionMode.DEFERRED,
                PersistenceFlags.READ_REQUEST,
                SQLiteConfig.TransactionMode.DEFERRED);
        assertTransactionMode(
                SQLiteConfig.TransactionMode.DEFERRED,
                null,
                SQLiteConfig.TransactionMode.DEFERRED);
        assertTransactionMode(
                SQLiteConfig.TransactionMode.IMMEDIATE,
                PersistenceFlags.WRITE_REQUEST,
                SQLiteConfig.TransactionMode.IMMEDIATE);
        assertTransactionMode(
                SQLiteConfig.TransactionMode.EXCLUSIVE,
                PersistenceFlags.WRITE_REQUEST,
                SQLiteConfig.TransactionMode.EXCLUSIVE);
    }

    @Test
    public void wrappedSQLiteConnectionUsesImmediateTransactionForWrites() throws Exception {
        try (SQLiteConnection target = openConnection(SQLiteConfig.TransactionMode.DEFERRED)) {
            ReentrantLock lock = new ReentrantLock();
            lock.lock();
            try (Connection wrapped = LockReleasingConnection.wrap(target, lock)) {
                QueryOptions queryOptions = optionsWithFlag(PersistenceFlags.WRITE_REQUEST);
                RequestScopeConnectionManager.useImmediateTransactionForWriteRequest(
                        wrapped, queryOptions);
                TestAssertions.assertEquals(
                        SQLiteConfig.TransactionMode.IMMEDIATE,
                        target.getConnectionConfig().getTransactionMode());
            }
            finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
            TestAssertions.assertFalse(lock.isLocked());
        }
    }

    private static void assertClosedManagerRejectsAcquisition(Fixture fixture) {
        try {
            fixture.manager.getConnection(fixture.index, new QueryOptions());
            TestAssertions.fail("Expected closed manager to reject acquisition");
        }
        catch (IllegalStateException expected) {
            TestAssertions.assertTrue(expected.getMessage().contains("closed"));
        }
    }

    private static void assertTransactionMode(
            SQLiteConfig.TransactionMode initialMode,
            Object requestFlag,
            SQLiteConfig.TransactionMode expectedMode) throws SQLException {
        try (SQLiteConnection connection = openConnection(initialMode)) {
            QueryOptions queryOptions = requestFlag == null
                    ? new QueryOptions()
                    : optionsWithFlag(requestFlag);
            RequestScopeConnectionManager.useImmediateTransactionForWriteRequest(
                    connection, queryOptions);
            TestAssertions.assertEquals(
                    expectedMode,
                    connection.getConnectionConfig().getTransactionMode());
        }
    }

    private static QueryOptions optionsWithFlag(Object flag) {
        QueryOptions queryOptions = new QueryOptions();
        FlagsEnabled.forQueryOptions(queryOptions).add(flag);
        return queryOptions;
    }

    private static SQLiteConnection openConnection(
            SQLiteConfig.TransactionMode transactionMode) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setTransactionMode(transactionMode);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite::memory:");
        return (SQLiteConnection) dataSource.getConnection();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Fixture fixture(Connection connection) {
        Index<Object> index = mock(Index.class);
        when(index.getEffectiveIndex()).thenReturn(index);
        SQLitePersistence<Object, Integer> persistence = mock(SQLitePersistence.class);
        when(persistence.supportsIndex(index)).thenReturn(true);
        when(persistence.getConnection(eq(index), any(QueryOptions.class))).thenReturn(connection);
        return new Fixture(new RequestScopeConnectionManager(persistence), index);
    }

    static class Fixture {
        final RequestScopeConnectionManager manager;
        final Index<Object> index;

        Fixture(RequestScopeConnectionManager manager, Index<Object> index) {
            this.manager = manager;
            this.index = index;
        }
    }

    static class ConnectionRecorder implements InvocationHandler {
        final Connection proxy = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                this);
        boolean autoCommit = true;
        boolean closed;
        int commitCalls;
        int rollbackCalls;
        int closeCalls;
        SQLException getAutoCommitFailure;
        SQLException setAutoCommitFailure;
        SQLException commitFailure;
        SQLException rollbackFailure;
        SQLException closeFailure;

        Connection proxy() {
            return proxy;
        }

        @Override
        public Object invoke(Object ignored, Method method, Object[] arguments) throws Throwable {
            switch (method.getName()) {
                case "getAutoCommit":
                    if (getAutoCommitFailure != null) {
                        throw getAutoCommitFailure;
                    }
                    return autoCommit;
                case "setAutoCommit":
                    if (setAutoCommitFailure != null) {
                        throw setAutoCommitFailure;
                    }
                    autoCommit = (Boolean) arguments[0];
                    return null;
                case "commit":
                    commitCalls++;
                    if (commitFailure != null) {
                        throw commitFailure;
                    }
                    return null;
                case "rollback":
                    rollbackCalls++;
                    if (rollbackFailure != null) {
                        throw rollbackFailure;
                    }
                    return null;
                case "close":
                    closeCalls++;
                    closed = true;
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                    return null;
                case "isClosed":
                    return closed;
                case "toString":
                    return "ConnectionRecorder";
                case "hashCode":
                    return System.identityHashCode(this);
                case "equals":
                    return ignored == arguments[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            if (type == double.class) {
                return 0D;
            }
            return null;
        }
    }
}
