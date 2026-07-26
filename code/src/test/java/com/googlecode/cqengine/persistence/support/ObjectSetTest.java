/**
 * Copyright 2012-2015 Niall Gallagher
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

import com.googlecode.cqengine.index.support.CloseableIterator;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.CarFactory;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;
import static com.googlecode.cqengine.testutil.TestAssertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ObjectSet}.
 *
 * @author niall.gallagher
 */
public class ObjectSetTest {

    @Test
    @SuppressWarnings("unchecked")
    public void externalObjectSetMonitorCannotBlockIteratorCreationOrClose() throws Exception {
        ObjectStore<Car> objectStore = mock(ObjectStore.class);
        CloseableIterator<Car> delegate = mock(CloseableIterator.class);
        when(objectStore.iterator(Mockito.<QueryOptions>any())).thenReturn(delegate);
        ObjectSet<Car> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CloseableIterator<Car> iterator;
            synchronized (objectSet) {
                Future<CloseableIterator<Car>> creation = executor.submit(objectSet::iterator);
                iterator = creation.get(5, TimeUnit.SECONDS);
            }
            synchronized (objectSet) {
                Future<?> close = executor.submit(iterator::close);
                close.get(5, TimeUnit.SECONDS);
            }
        }
        finally {
            executor.shutdownNow();
            objectSet.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void externalIteratorMonitorCannotBlockIteratorClose() throws Exception {
        ObjectStore<Car> objectStore = mock(ObjectStore.class);
        CloseableIterator<Car> delegate = mock(CloseableIterator.class);
        when(objectStore.iterator(Mockito.<QueryOptions>any())).thenReturn(delegate);
        ObjectSet<Car> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        CloseableIterator<Car> iterator = objectSet.iterator();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            synchronized (iterator) {
                Future<?> close = executor.submit(iterator::close);
                close.get(5, TimeUnit.SECONDS);
            }
        }
        finally {
            executor.shutdownNow();
            objectSet.close();
        }
    }

    // ====== Tests for ObjectStore-based implementation of ObjectSet... ======

    @Test
    @SuppressWarnings("unchecked")
    public void testFromObjectStore_IteratorClose() throws Exception {
        ObjectStore<Car> objectStore = mock(ObjectStore.class);
        CloseableIterator<Car> closeableIterator = mock(CloseableIterator.class);
        when(objectStore.iterator(Mockito.<QueryOptions>any())).thenReturn(closeableIterator);

        ObjectSet<Car> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        CloseableIterator<Car> objectSetIterator = objectSet.iterator();
        objectSetIterator.close();
        Mockito.verify(closeableIterator, times(1)).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFromObjectStore_IteratorRemove() throws Exception {
        ObjectStore<Car> objectStore = mock(ObjectStore.class);
        CloseableIterator<Car> closeableIterator = mock(CloseableIterator.class);
        when(closeableIterator.hasNext()).thenReturn(true);
        when(closeableIterator.next()).thenReturn(CarFactory.createCar(1));

        when(objectStore.iterator(Mockito.<QueryOptions>any())).thenReturn(closeableIterator);

        ObjectSet<Car> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        CloseableIterator<Car> objectSetIterator = objectSet.iterator();
        TestAssertions.assertTrue(objectSetIterator.hasNext());
        TestAssertions.assertNotNull(objectSetIterator.next());
        objectSetIterator.remove();
        Mockito.verify(closeableIterator, times(1)).remove();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFromObjectStore_ObjectSetClose() throws Exception {
        ObjectStore<Car> objectStore = mock(ObjectStore.class);
        CloseableIterator<Car> closeableIterator = mock(CloseableIterator.class);
        when(objectStore.iterator(Mockito.<QueryOptions>any())).thenReturn(closeableIterator);

        ObjectSet<Car> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        objectSet.iterator();
        objectSet.close(); // should close the iterator
        objectSet.close(); // should have no effect as it was removed from openIterators
        Mockito.verify(closeableIterator, times(1)).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFromObjectStore_IsEmpty_True() throws Exception {
        ObjectStore<Car> objectStore = mock(ObjectStore.class);
        CloseableIterator<Car> closeableIterator = mock(CloseableIterator.class);
        when(closeableIterator.hasNext()).thenReturn(false);
        when(objectStore.iterator(Mockito.<QueryOptions>any())).thenReturn(closeableIterator);

        ObjectSet<Car> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        TestAssertions.assertEquals(true, objectSet.isEmpty());
        Mockito.verify(closeableIterator, times(1)).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFromObjectStore_IsEmpty_False() throws Exception {
        ObjectStore<Car> objectStore = mock(ObjectStore.class);
        CloseableIterator<Car> closeableIterator = mock(CloseableIterator.class);
        when(closeableIterator.hasNext()).thenReturn(true);
        when(objectStore.iterator(Mockito.<QueryOptions>any())).thenReturn(closeableIterator);

        ObjectSet<Car> objectSet = ObjectSet.fromObjectStore(objectStore, noQueryOptions());
        TestAssertions.assertEquals(false, objectSet.isEmpty());
        Mockito.verify(closeableIterator, times(1)).close();
    }


    // ====== Tests for Collection-based implementation of ObjectSet... ======

    @Test
    @SuppressWarnings("unchecked")
    public void testFromCollection_IteratorClose() throws Exception {
        ObjectSet<Car> objectSet = ObjectSet.fromCollection(Collections.<Car>emptySet());
        objectSet.iterator().close(); // Can't really verify anything because close() is a no-op
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFromCollection_IteratorRemove() throws Exception {
        Collection<Car> collection = mock(Collection.class);
        Iterator<Car> iterator = mock(Iterator.class);
        when(iterator.hasNext()).thenReturn(true);
        when(iterator.next()).thenReturn(CarFactory.createCar(1));

        when(collection.iterator()).thenReturn(iterator);

        ObjectSet<Car> objectSet = ObjectSet.fromCollection(collection);
        CloseableIterator<Car> objectSetIterator = objectSet.iterator();
        TestAssertions.assertTrue(objectSetIterator.hasNext());
        TestAssertions.assertNotNull(objectSetIterator.next());
        objectSetIterator.remove();
        Mockito.verify(iterator, times(1)).remove();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFromCollection_IsEmpty_True() throws Exception {
        Collection<Car> collection = mock(Collection.class);
        when(collection.isEmpty()).thenReturn(true);

        ObjectSet<Car> objectSet = ObjectSet.fromCollection(collection);
        TestAssertions.assertEquals(true, objectSet.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFromCollection_IsEmpty_False() throws Exception {
        Collection<Car> collection = mock(Collection.class);
        when(collection.isEmpty()).thenReturn(false);

        ObjectSet<Car> objectSet = ObjectSet.fromCollection(collection);
        TestAssertions.assertEquals(false, objectSet.isEmpty());
    }
}
