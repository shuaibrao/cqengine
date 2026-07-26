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
package com.googlecode.cqengine;

import com.googlecode.cqengine.engine.QueryEngineInternal;
import com.googlecode.cqengine.engine.CollectionQueryEngine;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.support.CloseableIterator;
import com.googlecode.cqengine.index.support.CloseableRequestResources;
import com.googlecode.cqengine.metadata.MetadataEngine;
import com.googlecode.cqengine.persistence.Persistence;
import com.googlecode.cqengine.persistence.RequestScopeTransactionOutcome;
import com.googlecode.cqengine.persistence.onheap.OnHeapPersistence;
import com.googlecode.cqengine.persistence.support.ObjectSet;
import com.googlecode.cqengine.persistence.support.ObjectStore;
import com.googlecode.cqengine.persistence.support.ObjectStoreAsSet;
import com.googlecode.cqengine.persistence.support.PersistenceFlags;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.resultset.closeable.CloseableResultSet;

import java.util.*;

import static com.googlecode.cqengine.query.QueryFactory.queryOptions;
import static java.util.Collections.singleton;

/**
 * An implementation of {@link java.util.Set} and {@link com.googlecode.cqengine.engine.QueryEngine}, thus providing
 * {@link #retrieve(com.googlecode.cqengine.query.Query)} methods for performing queries on the collection to retrieve
 * matching objects, and {@link #addIndex(com.googlecode.cqengine.index.Index)} methods allowing indexes to be
 * added to the collection to improve query performance.
 * <p>
 * This collection takes care of automatically updating any indexes with objects added to/from the collection.
 * <p>
 * This collection is thread-safe for concurrent reads in all cases.
 * <p>
 * This collection is thread-safe for concurrent writes in cases where multiple threads might try to add/remove
 * <i>different</i> objects to/from the collection concurrently.
 * <p>
 * This collection is <b>not</b> thread-safe in cases where two or more threads might try to add or remove the
 * <i>same</i> object to/from the collection concurrently. There is a risk that indexes might get out of sync causing
 * inconsistent results in that scenario with this implementation.
 * <p>
 * In applications where multiple threads might add/remove the same object concurrently, then the subclass
 * {@link ObjectLockingIndexedCollection} should be used instead. That subclass allows concurrent writes, but with
 * additional safeguards against concurrent modification for the same object, with some additional overhead.
 * <p>
 * Note that in this context the <i>same object</i> refers to either the same object instance, OR two object instances
 * having the same hash code and being equal according to their {@link #equals(Object)} methods.
 *
 * @author Niall Gallagher
 */
public class ConcurrentIndexedCollection<O> implements IndexedCollection<O> {

    private static final Object REQUEST_SCOPE_OUTCOME = new Object();

    protected final Persistence<O, ?> persistence;
    protected final ObjectStore<O> objectStore;
    protected final QueryEngineInternal<O> indexEngine;
    protected final MetadataEngine<O> metadataEngine;
    protected final boolean requestScopeCloseRequired;

    /**
     * Creates a new {@link ConcurrentIndexedCollection} with default settings, using {@link OnHeapPersistence}.
     */
    @SuppressWarnings("unchecked")
    public ConcurrentIndexedCollection() {
        this(OnHeapPersistence.<O>withoutPrimaryKey());
    }

    /**
     * Creates a new {@link ConcurrentIndexedCollection} which will use the given persistence to create the backing set.
     *
     * @param persistence The {@link Persistence} implementation which will create a concurrent {@link java.util.Set}
     *                    in which objects added to the indexed collection will be stored, and which will provide
     *                    access to the underlying storage of indexes.
     */
    @SuppressWarnings({"rawtypes", "this-escape"}) // Legacy signature; callbacks are not invoked or published here.
    public ConcurrentIndexedCollection(Persistence<O, ? extends Comparable> persistence) {
        this.persistence = persistence;
        this.requestScopeCloseRequired = !(persistence instanceof OnHeapPersistence);
        this.objectStore = persistence.createObjectStore();
        QueryEngineInternal<O> queryEngine = new CollectionQueryEngine<O>();
        try (InitialRequestScope requestScope = new InitialRequestScope(persistence)) {
            QueryOptions queryOptions = requestScope.getQueryOptions();
            queryEngine.init(objectStore, queryOptions);
            requestScope.markSuccessful();
        }
        this.indexEngine = queryEngine;
        this.metadataEngine = new MetadataEngine<>(
                this,
                () -> openReadRequestScopeResourcesIfNecessary(null),
                this::closeRequestScopeResourcesIfNecessary
        );
    }

    private static final class InitialRequestScope implements AutoCloseable {
        private final Persistence<?, ?> persistence;
        private final QueryOptions queryOptions = new QueryOptions();
        private final boolean closeRequired;
        private RequestScopeTransactionOutcome outcome = RequestScopeTransactionOutcome.ROLLBACK;
        private boolean closed;

        private InitialRequestScope(Persistence<?, ?> persistence) {
            this.persistence = persistence;
            classifyAsWriteRequest(queryOptions);
            this.closeRequired = !(persistence instanceof OnHeapPersistence<?, ?>);
            if (closeRequired) {
                persistence.openRequestScopeResources(queryOptions);
            }
            queryOptions.put(Persistence.class, persistence);
        }

        private QueryOptions getQueryOptions() {
            return queryOptions;
        }

        private void markSuccessful() {
            outcome = RequestScopeTransactionOutcome.COMMIT;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (closeRequired) {
                    persistence.closeRequestScopeResources(queryOptions, outcome);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Persistence<O, ?> getPersistence() {
        return persistence;
    }

    @Override
    public MetadataEngine<O> getMetadataEngine() {
        return metadataEngine;
    }

    // ----------- Query Engine Methods -------------

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultSet<O> retrieve(Query<O> query) {
        return retrieve(query, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultSet<O> retrieve(Query<O> query, QueryOptions queryOptions) {
        final RequestScope requestScope = openReadRequestScope(queryOptions);
        final QueryOptions finalQueryOptions = requestScope.getQueryOptions();
        ResultSet<O> results = null;
        try {
            results = indexEngine.retrieve(query, finalQueryOptions);
            CloseableResultSet<O> closeableResults = new CloseableResultSet<O>(
                    results, query, finalQueryOptions, requestScope::close);
            requestScope.markSuccessful();
            return closeableResults;
        }
        catch (RuntimeException | Error failure) {
            CloseableRequestResources.closeAndAddSuppressed(results, failure);
            requestScope.closeAfterFailure(failure);
            throw failure;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean update(Iterable<O> objectsToRemove, Iterable<O> objectsToAdd) {
        return update(objectsToRemove, objectsToAdd, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean update(Iterable<O> objectsToRemove, Iterable<O> objectsToAdd, QueryOptions queryOptions) {
        try (RequestScope requestScope = openWriteRequestScope(queryOptions)) {
            queryOptions = requestScope.getQueryOptions();
            boolean modified = doRemoveAll(objectsToRemove, queryOptions);
            modified = doAddAll(objectsToAdd, queryOptions) || modified;
            requestScope.markSuccessful();
            return modified;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addIndex(Index<O> index) {
        addIndex(index, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addIndex(Index<O> index, QueryOptions queryOptions) {
        try (RequestScope requestScope = openWriteRequestScope(queryOptions)) {
            queryOptions = requestScope.getQueryOptions();
            indexEngine.addIndex(index, queryOptions);
            requestScope.markSuccessful();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeIndex(Index<O> index) {
        removeIndex(index, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeIndex(Index<O> index, QueryOptions queryOptions) {
        try (RequestScope requestScope = openWriteRequestScope(queryOptions)) {
            queryOptions = requestScope.getQueryOptions();
            indexEngine.removeIndex(index, queryOptions);
            requestScope.markSuccessful();
        }
    }

    @Override
    public Iterable<Index<O>> getIndexes() {
        return indexEngine.getIndexes();
    }

    // ----------- Collection Accessor Methods -------------

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            int size = objectStore.size(requestScope.getQueryOptions());
            requestScope.markSuccessful();
            return size;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            boolean empty = objectStore.isEmpty(requestScope.getQueryOptions());
            requestScope.markSuccessful();
            return empty;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(Object o) {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            boolean contains = objectStore.contains(o, requestScope.getQueryOptions());
            requestScope.markSuccessful();
            return contains;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] toArray() {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            Object[] array = getObjectStoreAsSet(requestScope.getQueryOptions()).toArray();
            requestScope.markSuccessful();
            return array;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T[] toArray(T[] a) {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            //noinspection SuspiciousToArrayCall
            T[] array = getObjectStoreAsSet(requestScope.getQueryOptions()).toArray(a);
            requestScope.markSuccessful();
            return array;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsAll(Collection<?> c) {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            boolean containsAll = objectStore.containsAll(c, requestScope.getQueryOptions());
            requestScope.markSuccessful();
            return containsAll;
        }
    }

    // ----------- Collection Mutator Methods -------------

    /**
     * {@inheritDoc}
     */
    @Override
    public CloseableIterator<O> iterator() {
        final RequestScope requestScope = openRequestScope(null);
        final QueryOptions queryOptions = requestScope.getQueryOptions();
        try {
            final CloseableIterator<O> collectionIterator = objectStore.iterator(queryOptions);
            return new CloseableIterator<O>() {
                boolean autoClosed = false;
                boolean closed;
                O currentObject = null;

                @Override
                public boolean hasNext() {
                    try {
                        boolean hasNext = collectionIterator.hasNext();
                        if (!hasNext) {
                            autoClosed = true;
                            close();
                        }
                        return hasNext;
                    }
                    catch (RuntimeException | Error failure) {
                        fail(failure);
                        throw failure;
                    }
                }

                @Override
                public O next() {
                    try {
                        O next = collectionIterator.next();
                        currentObject = next;
                        return next;
                    }
                    catch (NoSuchElementException exhausted) {
                        throw exhausted;
                    }
                    catch (RuntimeException | Error failure) {
                        fail(failure);
                        throw failure;
                    }
                }

                @Override
                public void remove() {
                    if (currentObject == null) {
                        throw new IllegalStateException();
                    }
                    try {
                        // Handle an edge case where we might have retrieved the last object and called close() automatically,
                        // but then the application calls remove() so we have to reopen request-scope resources temporarily
                        // to remove the last object...
                        if (autoClosed) {
                            ConcurrentIndexedCollection.this.remove(currentObject); // reopens resources temporarily
                        }
                        else {
                            doRemoveAll(Collections.singleton(currentObject), queryOptions); // uses existing resources
                            commitRequestScopeTransaction(queryOptions);
                        }
                        currentObject = null;
                    }
                    catch (RuntimeException | Error failure) {
                        fail(failure);
                        throw failure;
                    }
                }

                synchronized void fail(Throwable failure) {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    CloseableRequestResources.closeAndAddSuppressed(collectionIterator, failure);
                    requestScope.closeAfterFailure(failure);
                }

                @Override
                public synchronized void close() {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    try {
                        collectionIterator.close();
                    }
                    catch (RuntimeException | Error failure) {
                        requestScope.closeAfterFailure(failure);
                        throw failure;
                    }
                    requestScope.markSuccessful();
                    requestScope.close();
                }
            };
        }
        catch (RuntimeException | Error failure) {
            requestScope.closeAfterFailure(failure);
            throw failure;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(O o) {
        try (RequestScope requestScope = openWriteRequestScope(null)) {
            QueryOptions queryOptions = requestScope.getQueryOptions();
            // Add the object to the index.
            // Indexes handle gracefully the case that the objects supplied already exist in the index...
            boolean modified = objectStore.add(o, queryOptions);
            indexEngine.addAll(ObjectSet.fromCollection(singleton(o)), queryOptions);
            requestScope.markSuccessful();
            return modified;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(Object object) {
        try (RequestScope requestScope = openWriteRequestScope(null)) {
            QueryOptions queryOptions = requestScope.getQueryOptions();
            @SuppressWarnings({"unchecked"})
            O o = (O) object;
            boolean modified = objectStore.remove(o, queryOptions);
            indexEngine.removeAll(ObjectSet.fromCollection(singleton(o)), queryOptions);
            requestScope.markSuccessful();
            return modified;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addAll(Collection<? extends O> c) {
        if (c == this) {
            return false;
        }
        try (RequestScope requestScope = openWriteRequestScope(null)) {
            QueryOptions queryOptions = requestScope.getQueryOptions();
            @SuppressWarnings({"unchecked"})
            Collection<O> objects = (Collection<O>) c;
            boolean modified = objectStore.addAll(objects, queryOptions);
            indexEngine.addAll(ObjectSet.fromCollection(objects), queryOptions);
            requestScope.markSuccessful();
            return modified;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        if (c == this) {
            boolean modified = !isEmpty();
            clear();
            return modified;
        }
        try (RequestScope requestScope = openWriteRequestScope(null)) {
            QueryOptions queryOptions = requestScope.getQueryOptions();
            @SuppressWarnings({"unchecked"})
            Collection<O> objects = (Collection<O>) c;
            boolean modified = objectStore.removeAll(objects, queryOptions);
            indexEngine.removeAll(ObjectSet.fromCollection(objects), queryOptions);
            requestScope.markSuccessful();
            return modified;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        RequestScope requestScope = openWriteRequestScope(null);
        QueryOptions queryOptions = requestScope.getQueryOptions();
        try (requestScope) {
            CloseableIterator<O> iterator = null;
            try {
                boolean modified = false;
                iterator = objectStore.iterator(queryOptions);
                while (iterator.hasNext()) {
                    O next = iterator.next();
                    if (!c.contains(next)) {
                        doRemoveAll(Collections.singleton(next), queryOptions);
                        modified = true;
                    }
                }
                requestScope.markSuccessful();
                return modified;
            }
            finally {
                CloseableRequestResources.closeQuietly(iterator);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        try (RequestScope requestScope = openWriteRequestScope(null)) {
            QueryOptions queryOptions = requestScope.getQueryOptions();
            objectStore.clear(queryOptions);
            indexEngine.clear(queryOptions);
            requestScope.markSuccessful();
        }
    }

    boolean doAddAll(Iterable<O> objects, QueryOptions queryOptions) {
        if (objects instanceof Collection) {
            Collection<O> c = (Collection<O>) objects;
            boolean modified = objectStore.addAll(c, queryOptions);
            indexEngine.addAll(ObjectSet.fromCollection(c), queryOptions);
            return modified;
        }
        else {
            boolean modified = false;
            for (O object : objects) {
                boolean added = objectStore.add(object, queryOptions);
                indexEngine.addAll(ObjectSet.fromCollection(singleton(object)), queryOptions);
                modified = added || modified;
            }
            return modified;
        }
    }

    boolean doRemoveAll(Iterable<O> objects, QueryOptions queryOptions) {
        if (objects instanceof Collection) {
            Collection<O> c = (Collection<O>) objects;
            boolean modified = objectStore.removeAll(c, queryOptions);
            indexEngine.removeAll(ObjectSet.fromCollection(c), queryOptions);
            return modified;
        } else {
            boolean modified = false;
            for (O object : objects) {
                boolean removed = objectStore.remove(object, queryOptions);
                indexEngine.removeAll(ObjectSet.fromCollection(singleton(object)), queryOptions);
                modified = removed || modified;
            }
            return modified;
        }
    }

    protected QueryOptions openRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
        if (queryOptions == null) {
            queryOptions = new QueryOptions();
        }
        if (requestScopeCloseRequired) {
            persistence.openRequestScopeResources(queryOptions);
        }
        queryOptions.put(Persistence.class, persistence);
        return queryOptions;
    }

    private QueryOptions openReadRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
        QueryOptions classifiedOptions = classifyAsReadRequest(queryOptions);
        QueryOptions openedOptions = openRequestScopeResourcesIfNecessary(classifiedOptions);
        flagAsReadRequest(openedOptions);
        return openedOptions;
    }

    protected void closeRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
        if (!requestScopeCloseRequired) {
            return;
        }
        RequestScopeTransactionOutcome outcome = (RequestScopeTransactionOutcome) queryOptions.get(REQUEST_SCOPE_OUTCOME);
        if (outcome == null) {
            outcome = RequestScopeTransactionOutcome.COMMIT;
        }
        persistence.closeRequestScopeResources(queryOptions, outcome);
    }

    protected void closeRequestScopeResourcesIfNecessary(
            QueryOptions queryOptions,
            RequestScopeTransactionOutcome outcome) {
        if (!requestScopeCloseRequired) {
            // On-heap fast path: skip the outcome bookkeeping, but keep dispatching to the overridable
            // one-arg method so subclasses hooking request-scope closure still observe every close.
            closeRequestScopeResourcesIfNecessary(queryOptions);
            return;
        }
        queryOptions.put(REQUEST_SCOPE_OUTCOME, outcome);
        try {
            closeRequestScopeResourcesIfNecessary(queryOptions);
        }
        finally {
            queryOptions.remove(REQUEST_SCOPE_OUTCOME);
        }
    }

    protected RequestScope openRequestScope(QueryOptions queryOptions) {
        return new RequestScope(openRequestScopeResourcesIfNecessary(queryOptions));
    }

    private RequestScope openReadRequestScope(QueryOptions queryOptions) {
        RequestScope requestScope = openRequestScope(classifyAsReadRequest(queryOptions));
        flagAsReadRequest(requestScope.getQueryOptions());
        return requestScope;
    }

    private RequestScope openWriteRequestScope(QueryOptions queryOptions) {
        RequestScope requestScope = openRequestScope(classifyAsWriteRequest(queryOptions));
        flagAsWriteRequest(requestScope.getQueryOptions());
        return requestScope;
    }

    protected void commitRequestScopeTransaction(QueryOptions queryOptions) {
        if (requestScopeCloseRequired) {
            persistence.commitRequestScopeTransaction(queryOptions);
        }
    }

    protected class RequestScope implements AutoCloseable {
        private final QueryOptions queryOptions;
        private RequestScopeTransactionOutcome outcome = RequestScopeTransactionOutcome.ROLLBACK;
        private boolean closed;

        RequestScope(QueryOptions queryOptions) {
            this.queryOptions = queryOptions;
        }

        public QueryOptions getQueryOptions() {
            return queryOptions;
        }

        public void markSuccessful() {
            outcome = RequestScopeTransactionOutcome.COMMIT;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closeRequestScopeResourcesIfNecessary(queryOptions, outcome);
            }
        }

        void closeAfterFailure(Throwable failure) {
            try {
                close();
            }
            catch (RuntimeException | Error closeFailure) {
                if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }


    protected ObjectStoreAsSet<O> getObjectStoreAsSet(QueryOptions queryOptions) {
        return new ObjectStoreAsSet<O>(objectStore, queryOptions);
    }

    @Override
    public boolean equals(Object o) {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            QueryOptions queryOptions = requestScope.getQueryOptions();
            boolean equal = this == o || o instanceof Set && getObjectStoreAsSet(queryOptions).equals(o);
            requestScope.markSuccessful();
            return equal;
        }
    }

    @Override
    public int hashCode() {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            int hashCode = getObjectStoreAsSet(requestScope.getQueryOptions()).hashCode();
            requestScope.markSuccessful();
            return hashCode;
        }
    }

    @Override
    public String toString() {
        try (RequestScope requestScope = openReadRequestScope(null)) {
            String string = getObjectStoreAsSet(requestScope.getQueryOptions()).toString();
            requestScope.markSuccessful();
            return string;
        }
    }

    /**
     * Sets a flag into the given query options to record that this request will read from the collection
     * but will not modify it.
     * This is used to facilitate locking in some persistence implementations.
     *
     * @param queryOptions The query options for the request
     */
    protected static void flagAsReadRequest(QueryOptions queryOptions) {
        FlagsEnabled flags = FlagsEnabled.forQueryOptions(queryOptions);
        flags.remove(PersistenceFlags.WRITE_REQUEST);
        flags.add(PersistenceFlags.READ_REQUEST);
    }

    static QueryOptions classifyAsReadRequest(QueryOptions queryOptions) {
        QueryOptions classifiedOptions = queryOptions == null ? new QueryOptions() : queryOptions;
        flagAsReadRequest(classifiedOptions);
        return classifiedOptions;
    }

    static QueryOptions classifyAsWriteRequest(QueryOptions queryOptions) {
        QueryOptions classifiedOptions = queryOptions == null ? new QueryOptions() : queryOptions;
        flagAsWriteRequest(classifiedOptions);
        return classifiedOptions;
    }

    private static void flagAsWriteRequest(QueryOptions queryOptions) {
        FlagsEnabled flags = FlagsEnabled.forQueryOptions(queryOptions);
        flags.remove(PersistenceFlags.READ_REQUEST);
        flags.add(PersistenceFlags.WRITE_REQUEST);
    }
}
