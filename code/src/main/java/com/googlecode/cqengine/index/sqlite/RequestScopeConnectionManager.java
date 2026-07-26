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
package com.googlecode.cqengine.index.sqlite;

import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.sqlite.support.DBQueries;
import com.googlecode.cqengine.index.sqlite.support.DBUtils;
import com.googlecode.cqengine.index.sqlite.support.SQLiteIndexFlags;
import com.googlecode.cqengine.persistence.Persistence;
import com.googlecode.cqengine.persistence.RequestScopeTransactionOutcome;
import com.googlecode.cqengine.persistence.composite.CompositePersistence;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.googlecode.cqengine.index.sqlite.support.SQLiteIndexFlags.BULK_IMPORT;
import static com.googlecode.cqengine.index.sqlite.support.SQLiteIndexFlags.BULK_IMPORT_SUSPEND_SYNC_AND_JOURNALING;
import static com.googlecode.cqengine.persistence.RequestScopeTransactionOutcome.COMMIT;
import static com.googlecode.cqengine.persistence.support.PersistenceFlags.WRITE_REQUEST;

/**
 * A ConnectionManager which create connections on-demand to the correct persistence for the indexes requesting the
 * connection, and subsequently caches the connections, for re-use within the scope of the same request into CQEngine.
 * <p>
 * Opens only one connection to each persistence, and returns the same already-open connection for subsequent requests.
 * <p>
 * Connections are obtained from the {@link Persistence} object provided to the constructor.
 * This can be a {@link CompositePersistence} which actually wraps more than one backing persistence.
 * In that case, the {@link CompositePersistence#getPersistenceForIndex(Index)} method will be used to locate the
 * correct persistence to use for the index requesting the connection.
 *
 * @author niall.gallagher
 */
public class RequestScopeConnectionManager implements ConnectionManager, Closeable {

    final Persistence<?, ?> persistence;

    final Map<SQLitePersistence<?, ?>, ManagedConnection> openConnections = new LinkedHashMap<SQLitePersistence<?, ?>, ManagedConnection>();
    boolean closed;

    public RequestScopeConnectionManager(Persistence<?, ?> persistence) {
        this.persistence = persistence;
    }


    @Override
    public synchronized Connection getConnection(Index<?> index, QueryOptions queryOptions) {
        if (closed) {
            throw new IllegalStateException("RequestScopeConnectionManager has been closed");
        }
        index = index.getEffectiveIndex();
        SQLitePersistence<?, ?> persistence = getPersistenceForIndex(index);
        ManagedConnection managedConnection = openConnections.get(persistence);
        if (managedConnection == null) {
            managedConnection = openConnection(persistence, index, queryOptions);
            openConnections.put(persistence, managedConnection);
        }
        return managedConnection.connection;
    }

    /**
     * Commits the transactions on the open connections to all persistences, then closes the connections.
     */
    @Override
    public void close() {
        close(COMMIT);
    }

    /**
     * Completes every open transaction according to the request outcome, then closes every connection.
     */
    public synchronized void close(RequestScopeTransactionOutcome outcome) {
        if (outcome == null) {
            throw new NullPointerException("The request outcome cannot be null");
        }
        if (closed) {
            return;
        }
        closed = true;

        ManagedConnection[] connections = openConnections.values().toArray(new ManagedConnection[0]);
        openConnections.clear();

        Throwable failure = null;
        boolean commitRemaining = outcome == COMMIT;
        for (ManagedConnection managedConnection : connections) {
            boolean transactionCompleted = false;
            if (commitRemaining) {
                try {
                    DBUtils.commit(managedConnection.connection);
                    transactionCompleted = true;
                }
                catch (RuntimeException | Error commitFailure) {
                    failure = addFailure(failure, commitFailure);
                    commitRemaining = false;
                    try {
                        DBUtils.rollbackOrThrow(managedConnection.connection);
                        transactionCompleted = true;
                    }
                    catch (RuntimeException | Error rollbackFailure) {
                        failure = addFailure(failure, rollbackFailure);
                    }
                }
            }
            else {
                try {
                    DBUtils.rollbackOrThrow(managedConnection.connection);
                    transactionCompleted = true;
                }
                catch (RuntimeException | Error rollbackFailure) {
                    failure = addFailure(failure, rollbackFailure);
                }
            }

            if (managedConnection.restorePragmas && transactionCompleted) {
                try {
                    restorePragmas(managedConnection);
                }
                catch (RuntimeException | Error restoreFailure) {
                    failure = addFailure(failure, restoreFailure);
                }
            }

            try {
                managedConnection.connection.close();
            }
            catch (Throwable closeFailure) {
                failure = addFailure(failure, new IllegalStateException("Unable to close request-scope connection", closeFailure));
            }
        }

        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }

    /**
     * Commits the current transaction on every open connection without closing the request scope.
     */
    public synchronized void commitOpenTransactions() {
        if (closed) {
            throw new IllegalStateException("RequestScopeConnectionManager has been closed");
        }

        Throwable failure = null;
        boolean commitRemaining = true;
        for (ManagedConnection managedConnection : openConnections.values()) {
            if (commitRemaining) {
                try {
                    DBUtils.commit(managedConnection.connection);
                }
                catch (RuntimeException | Error commitFailure) {
                    failure = addFailure(failure, commitFailure);
                    commitRemaining = false;
                    try {
                        DBUtils.rollbackOrThrow(managedConnection.connection);
                    }
                    catch (RuntimeException | Error rollbackFailure) {
                        failure = addFailure(failure, rollbackFailure);
                    }
                }
            }
            else {
                try {
                    DBUtils.rollbackOrThrow(managedConnection.connection);
                }
                catch (RuntimeException | Error rollbackFailure) {
                    failure = addFailure(failure, rollbackFailure);
                }
            }
        }

        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }

    ManagedConnection openConnection(SQLitePersistence<?, ?> persistence, Index<?> index, QueryOptions queryOptions) {
        Connection connection = persistence.getConnection(index, queryOptions);
        ManagedConnection managedConnection = new ManagedConnection(connection);
        try {
            boolean bulkImport = queryOptions.get(SQLiteIndexFlags.BulkImportExternallyManged.class) != null
                    || FlagsEnabled.isFlagEnabled(queryOptions, BULK_IMPORT);
            if (bulkImport && FlagsEnabled.isFlagEnabled(queryOptions, BULK_IMPORT_SUSPEND_SYNC_AND_JOURNALING)) {
                managedConnection.synchronousMode = DBQueries.getPragmaSynchronousOrNull(connection);
                managedConnection.journalMode = DBQueries.getPragmaJournalModeOrNull(connection);
                if (managedConnection.synchronousMode == null || managedConnection.journalMode == null) {
                    throw new IllegalStateException("Cannot suspend sync and journaling because their current values could not be read");
                }
                managedConnection.restorePragmas = true;
                DBQueries.suspendSyncAndJournaling(connection);
            }
            useImmediateTransactionForWriteRequest(connection, queryOptions);
            DBUtils.setAutoCommit(connection, false);
            return managedConnection;
        }
        catch (RuntimeException | Error acquisitionFailure) {
            if (managedConnection.restorePragmas) {
                try {
                    restorePragmas(managedConnection);
                }
                catch (RuntimeException | Error restoreFailure) {
                    acquisitionFailure.addSuppressed(restoreFailure);
                }
            }
            try {
                connection.close();
            }
            catch (Throwable closeFailure) {
                acquisitionFailure.addSuppressed(new IllegalStateException("Unable to close rejected request-scope connection", closeFailure));
            }
            throw acquisitionFailure;
        }
    }

    static void useImmediateTransactionForWriteRequest(
            Connection connection,
            QueryOptions queryOptions) {
        if (!FlagsEnabled.isFlagEnabled(queryOptions, WRITE_REQUEST)) {
            return;
        }
        try {
            SQLiteConnection sqLiteConnection;
            if (connection instanceof SQLiteConnection) {
                sqLiteConnection = (SQLiteConnection) connection;
            }
            else if (connection.isWrapperFor(SQLiteConnection.class)) {
                sqLiteConnection = connection.unwrap(SQLiteConnection.class);
            }
            else {
                return;
            }
            if (sqLiteConnection.getConnectionConfig().getTransactionMode()
                    == SQLiteConfig.TransactionMode.DEFERRED) {
                sqLiteConnection.getConnectionConfig().setTransactionMode(
                        SQLiteConfig.TransactionMode.IMMEDIATE);
            }
        }
        catch (SQLException e) {
            throw DBUtils.wrapAsRuntimeException(
                    "Unable to configure an immediate SQLite write transaction", e);
        }
    }

    static void restorePragmas(ManagedConnection managedConnection) {
        DBUtils.setAutoCommit(managedConnection.connection, true);
        DBQueries.setSyncAndJournaling(
                managedConnection.connection,
                managedConnection.synchronousMode,
                managedConnection.journalMode);
    }

    /**
     * Schedules SQLite connection settings to be restored only after the request transaction has completed.
     */
    public synchronized void restoreSyncAndJournalingOnClose(
            Connection connection,
            SQLiteConfig.SynchronousMode synchronousMode,
            SQLiteConfig.JournalMode journalMode) {
        if (closed) {
            throw new IllegalStateException("RequestScopeConnectionManager has been closed");
        }
        for (ManagedConnection managedConnection : openConnections.values()) {
            if (managedConnection.connection == connection) {
                managedConnection.synchronousMode = synchronousMode;
                managedConnection.journalMode = journalMode;
                managedConnection.restorePragmas = true;
                return;
            }
        }
        throw new IllegalArgumentException("The connection is not managed by this request scope");
    }

    static Throwable addFailure(Throwable existingFailure, Throwable additionalFailure) {
        if (existingFailure == null) {
            return additionalFailure;
        }
        if (existingFailure != additionalFailure) {
            existingFailure.addSuppressed(additionalFailure);
        }
        return existingFailure;
    }

    static class ManagedConnection {
        final Connection connection;
        SQLiteConfig.SynchronousMode synchronousMode;
        SQLiteConfig.JournalMode journalMode;
        boolean restorePragmas;

        ManagedConnection(Connection connection) {
            this.connection = connection;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) // Bridges existential index/persistence types after runtime routing.
    SQLitePersistence<?, ?> getPersistenceForIndex(Index<?> index) {
        if (persistence instanceof SQLitePersistence) {
            if (persistence.supportsIndex((Index)index)) {
                return (SQLitePersistence<?, ?>) persistence;
            }
        }
        else if (persistence instanceof CompositePersistence) {
            CompositePersistence<?, ?> compositePersistence = ((CompositePersistence<?, ?>) persistence);
            Persistence<?, ?> indexPersistence = compositePersistence.getPersistenceForIndex((Index) index);
            if (indexPersistence instanceof SQLitePersistence) {
                return (SQLitePersistence<?, ?>) indexPersistence;
            }
        }
        throw new IllegalStateException("No configured Persistence implementation can support the given index: " + index);
    }

    @Override
    public boolean isApplyUpdateForIndexEnabled(Index<?> index) {
        return true;
    }
}
