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
package com.googlecode.cqengine.persistence;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.persistence.support.ObjectStore;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.util.Set;

/**
 * An interface with multiple implementations, which provide details on how a collection or indexes should be persisted
 * (for example on-heap, off-heap, or disk).
 *
 * @author niall.gallagher
 */
public interface Persistence<O, A extends Comparable<A>> {

    /**
     * Creates an ObjectStore which can store the objects added to the collection, either on-heap off-heap or on disk,
     * depending on the persistence implementation.
     *
     * @return An ObjectStore which persists objects added to the collection.
     */
    ObjectStore<O> createObjectStore();

    /**
     * Checks if this persistence manages the given index.
     * @param index The {@link Index} to check.
     * @return true if this persistence manages the given index. False otherwise.
     */
    boolean supportsIndex(Index<O> index);

    /**
     * Called at the start of every request into CQEngine, to prepare any resources needed by the request in order
     * to access the persistence.
     * <p>
     * The implementation of this method may be a no-op in some persistence implementations, such as those exclusively
     * on-heap. However the implementation of this method in other persistence implementations, such as off-heap or
     * on-disk, may open files or connections to the persistence for use by the ObjectStore and indexes during the
     * request.
     * <p>
     * This method does not modify the state of the Persistence object; instead it will add any resources it opens to
     * the given QueryOptions object. The related {@link #closeRequestScopeResources(QueryOptions)} method will then
     * be called at the end of every request into CQEngine, to close any resources in the QueryOptions which this method
     * opened.
     *
     * @param queryOptions The query options supplied with the request into CQEngine.
     */
    void openRequestScopeResources(QueryOptions queryOptions);

    /**
     * Called at the end of every request into CQEngine, to close any resources which were opened by the
     * {@link #openRequestScopeResources(QueryOptions)} method and stored in the given query options.
     *
     * @param queryOptions The query options supplied with the request into CQEngine, and which has earlier been
     *                     supplied to the {@link #openRequestScopeResources(QueryOptions)} method.
     */
    void closeRequestScopeResources(QueryOptions queryOptions);

    /**
     * Called at the end of a request with the outcome which determines whether transactional resources should be
     * committed or rolled back.
     * <p>
     * The default implementation preserves compatibility with persistence implementations written before explicit
     * request outcomes were introduced. Transactional implementations should override this method.
     *
     * @param queryOptions The query options supplied to {@link #openRequestScopeResources(QueryOptions)}.
     * @param outcome Whether the request completed successfully or failed.
     */
    default void closeRequestScopeResources(QueryOptions queryOptions, RequestScopeTransactionOutcome outcome) {
        closeRequestScopeResources(queryOptions);
    }

    /**
     * Commits the current transaction while keeping request-scope resources open for continued use.
     * <p>
     * This supports mutating iterators, where each successful {@code Iterator.remove()} is an observable collection
     * operation even though the iterator's read resources remain open. Transactional implementations which support
     * mutating iterators should override this method.
     *
     * @param queryOptions The query options supplied to {@link #openRequestScopeResources(QueryOptions)}.
     */
    default void commitRequestScopeTransaction(QueryOptions queryOptions) {
        // No-op for non-transactional persistence implementations.
    }

    /**
     * @return the primary key attribute, if configured. This may be null for some persistence implementations
     * especially on-heap persistence.
     */
    SimpleAttribute<O, A> getPrimaryKeyAttribute();
}
