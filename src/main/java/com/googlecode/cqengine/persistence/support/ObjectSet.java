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
package com.googlecode.cqengine.persistence.support;

import com.googlecode.cqengine.index.support.CloseableIterable;
import com.googlecode.cqengine.index.support.CloseableIterator;
import com.googlecode.cqengine.index.support.CloseableRequestResources;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

/**
 * Represents a set of objects which may be iterated repeatedly, allowing the resources opened
 * for each iteration to be closed afterwards.
 * <p>
 * The {@link #iterator()} method returns a {@link CloseableIterator} which should ideally be
 * closed after each iteration is complete.
 * <p>
 * However this object also keeps track of iterators which were opened but not yet closed,
 * and provides a {@link #close()} method which will close all iterators which remain open.
 *
 * @author npgall
 */
public abstract class ObjectSet<O> implements CloseableIterable<O>, Closeable {

    // ====== External interface methods... ======
    @Override
    public abstract void close();

    public abstract boolean isEmpty();


    // ====== Static factories to instantiate implementations... ======

    public static <O> ObjectSet<O> fromObjectStore(final ObjectStore<O> objectStore, final QueryOptions queryOptions) {
        return new ObjectStoreAsObjectSet<O>(objectStore, queryOptions);
    }

    public static <O> ObjectSet<O> fromCollection(final Collection<O> collection) {
        return new CollectionAsObjectSet<O>(collection);
    }


    // ====== ObjectSet implementations which wrap a Collection or ObjectStore... ======

    static class CollectionAsObjectSet<O> extends ObjectSet<O> {

        final Collection<O> collection;

        public CollectionAsObjectSet(Collection<O> collection) {
            this.collection = collection;
        }

        @Override
        public CloseableIterator<O> iterator() {
            final Iterator<O> iterator = collection.iterator();
            return new CloseableIterator<O>() {
                @Override
                public void close() {
                    // No op
                }

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public O next() {
                    return iterator.next();
                }

                @Override
                public void remove() {
                    iterator.remove();
                }
            };
        }

        @Override
        public boolean isEmpty() {
            return collection.isEmpty();
        }

        @Override
        public void close() {
            // No op
        }
    }

    static class ObjectStoreAsObjectSet<O> extends ObjectSet<O> {

        final ObjectStore<O> objectStore;
        final QueryOptions queryOptions;

        final Set<CloseableIterator<O>> openIterators = Collections.newSetFromMap(
                new IdentityHashMap<CloseableIterator<O>, Boolean>());
        private final Object iteratorRegistryLock = new Object();
        boolean closed;

        public ObjectStoreAsObjectSet(ObjectStore<O> objectStore, QueryOptions queryOptions) {
            this.objectStore = objectStore;
            this.queryOptions = queryOptions;
        }

        @Override
        public CloseableIterator<O> iterator() {
            synchronized (iteratorRegistryLock) {
                if (closed) {
                    throw new IllegalStateException("ObjectSet is closed");
                }
                final CloseableIterator<O> delegateIterator = objectStore.iterator(queryOptions);
                class ManagedIterator implements CloseableIterator<O> {
                    private final Object closeLock = new Object();
                    boolean iteratorClosed;

                    @Override
                    public void close() {
                        synchronized (closeLock) {
                            if (iteratorClosed) {
                                return;
                            }
                            iteratorClosed = true;
                        }
                        synchronized (iteratorRegistryLock) {
                            openIterators.remove(this);
                        }
                        delegateIterator.close();
                    }

                    @Override
                    public boolean hasNext() {
                        return delegateIterator.hasNext();
                    }

                    @Override
                    public O next() {
                        return delegateIterator.next();
                    }

                    @Override
                    public void remove() {
                        delegateIterator.remove();
                    }
                }
                ManagedIterator managedIterator = new ManagedIterator();
                openIterators.add(managedIterator);
                return managedIterator;
            }
        }

        public boolean isEmpty() {
            try (CloseableIterator<O> iterator = iterator()) {
                return !iterator.hasNext();
            }
        }

        @Override
        public void close() {
            final Collection<CloseableIterator<O>> iteratorsToClose;
            synchronized (iteratorRegistryLock) {
                if (closed) {
                    return;
                }
                closed = true;
                iteratorsToClose = new ArrayList<CloseableIterator<O>>(openIterators);
                openIterators.clear();
            }
            CloseableRequestResources.closeAll(iteratorsToClose);
        }
    }
}
