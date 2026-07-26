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

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.support.CloseableIterator;
import com.googlecode.cqengine.persistence.offheap.OffHeapPersistence;
import com.googlecode.cqengine.persistence.onheap.OnHeapPersistence;
import com.googlecode.cqengine.persistence.support.PersistenceFlags;
import com.googlecode.cqengine.persistence.wrapping.WrappingPersistence;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.resultset.iterator.IteratorUtil;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.MutableSetContract;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.*;
import java.util.stream.Stream;

import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;
import static com.googlecode.cqengine.testutil.TestUtil.setOf;
import static java.util.Arrays.asList;

/**
 * Unit tests for {@link ConcurrentIndexedCollection}. Note that tests for support behavior (such as query processing)
 * which applies to all implementations of {@link IndexedCollection} can be found in
 * {@link com.googlecode.cqengine.IndexedCollectionFunctionalTest}.
 * <p/>
 * The Jupiter behavior matrix in this class validates the mutable {@link Set} contract for on-heap and off-heap
 * collections.
 *
 * @author Niall Gallagher
 */
public class ConcurrentIndexedCollectionTest {

    @TestFactory
    Stream<DynamicTest> mutableSetContract() {
        return Stream.concat(
                MutableSetContract.tests("on-heap ConcurrentIndexedCollection", ConcurrentIndexedCollectionTest::newOnHeapCollection),
                MutableSetContract.tests("off-heap ConcurrentIndexedCollection", ConcurrentIndexedCollectionTest::newOffHeapCollection));
    }

    private static Set<String> newOnHeapCollection() {
        return new ConcurrentIndexedCollection<String>(
                OnHeapPersistence.onPrimaryKey(QueryFactory.selfAttribute(String.class)));
    }

    private static Set<String> newOffHeapCollection() {
        return new ConcurrentIndexedCollection<String>(
                OffHeapPersistence.onPrimaryKey(QueryFactory.selfAttribute(String.class)));
    }

    @Test
    public void testUpdate() {
        IndexedCollection<String> indexedCollection = new ConcurrentIndexedCollection<String>();
        TestAssertions.assertTrue(indexedCollection.update(Collections.<String>emptyList(), asList("a", "b", "c")));

        TestAssertions.assertEquals(setOf("a", "b", "c"), indexedCollection);

        TestAssertions.assertTrue(indexedCollection.update(asList("b"), Collections.<String>emptyList()));
        TestAssertions.assertEquals(setOf("a", "c"), indexedCollection);

        TestAssertions.assertTrue(indexedCollection.update(asList("a"), asList("d")));
        TestAssertions.assertEquals(setOf("c", "d"), indexedCollection);

        TestAssertions.assertFalse(indexedCollection.update(asList("a"), Collections.<String>emptyList()));
        TestAssertions.assertEquals(setOf("c", "d"), indexedCollection);

        TestAssertions.assertTrue(indexedCollection.update(asList("c", "e"), Collections.<String>emptyList()));
        TestAssertions.assertEquals(setOf("d"), indexedCollection);
    }

    @Test
    public void testUpdate_IterableArguments() {
        IndexedCollection<String> indexedCollection = new ConcurrentIndexedCollection<String>();
        TestAssertions.assertTrue(indexedCollection.update(asIterable(Collections.<String>emptyList()), asIterable(asList("a", "b", "c"))));
        TestAssertions.assertEquals(setOf("a", "b", "c"), indexedCollection);

        TestAssertions.assertTrue(indexedCollection.update(asIterable(asList("b")), asIterable(Collections.<String>emptyList())));
        TestAssertions.assertEquals(setOf("a", "c"), indexedCollection);

        TestAssertions.assertTrue(indexedCollection.update(asIterable(asList("a")), asIterable(asList("d"))));
        TestAssertions.assertEquals(setOf("c", "d"), indexedCollection);

        TestAssertions.assertFalse(indexedCollection.update(asIterable(asList("a")), asIterable(Collections.<String>emptyList())));
        TestAssertions.assertEquals(setOf("c", "d"), indexedCollection);

        TestAssertions.assertTrue(indexedCollection.update(asIterable(asList("c", "e")), asIterable(Collections.<String>emptyList())));
        TestAssertions.assertEquals(setOf("d"), indexedCollection);

        TestAssertions.assertTrue(indexedCollection.update(asIterable(Collections.<String>emptyList()), asIterable(asList("e", "d"))));
        TestAssertions.assertEquals(setOf("d", "e"), indexedCollection);

        TestAssertions.assertFalse(indexedCollection.update(asIterable(Collections.<String>emptyList()), asIterable(asList("e", "d"))));
        TestAssertions.assertEquals(setOf("d", "e"), indexedCollection);
    }



    @Test
    public void testGetIndexes() {
        IndexedCollection<Car> indexedCollection = new ConcurrentIndexedCollection<Car>();
        indexedCollection.addIndex(HashIndex.onAttribute(Car.CAR_ID), noQueryOptions());

        List<Index<Car>> indexes = new ArrayList<Index<Car>>();
        for (Index<Car> index : indexedCollection.getIndexes()) {
            indexes.add(index);
        }

        TestAssertions.assertEquals(1, indexes.size());
        TestAssertions.assertEquals(HashIndex.class, indexes.get(0).getClass());
    }

    @Test
    public void testRemoveIndex() {
        IndexedCollection<Car> indexedCollection = new ConcurrentIndexedCollection<Car>();
        HashIndex<Integer, Car> index = HashIndex.onAttribute(Car.CAR_ID);

        indexedCollection.addIndex(index);
        TestAssertions.assertEquals(1, IteratorUtil.countElements(indexedCollection.getIndexes()));

        indexedCollection.removeIndex(index);
        TestAssertions.assertEquals(0, IteratorUtil.countElements(indexedCollection.getIndexes()));
    }

    @Test
    public void testMetadataEngineDoesNotInvokeCollectionDuringConstruction() {
        ConstructionGuardedCollection collection = new ConstructionGuardedCollection();
        TestAssertions.assertNotNull(collection.getMetadataEngine());
    }

    @Test
    public void testRequestDirectionPrecedesAndPreservesExtensionHooks() {
        DirectionAwareCollection collection = new DirectionAwareCollection();

        collection.clearDirections();
        collection.size();
        TestAssertions.assertEquals(
                Collections.singletonList(RequestDirection.READ),
                collection.scopeDirections);
        TestAssertions.assertEquals(
                Collections.singletonList(RequestDirection.READ),
                collection.resourceDirections);

        collection.clearDirections();
        collection.add("one");
        TestAssertions.assertEquals(
                Collections.singletonList(RequestDirection.WRITE),
                collection.scopeDirections);
        TestAssertions.assertEquals(
                Collections.singletonList(RequestDirection.WRITE),
                collection.resourceDirections);

        SimpleAttribute<String, String> attribute = QueryFactory.selfAttribute(String.class);
        collection.addIndex(HashIndex.onAttribute(attribute));
        collection.clearDirections();
        TestAssertions.assertEquals(
                Integer.valueOf(1),
                collection.getMetadataEngine().getAttributeMetadata(attribute).getCountOfDistinctKeys());
        TestAssertions.assertTrue(collection.scopeDirections.isEmpty());
        TestAssertions.assertEquals(
                Collections.singletonList(RequestDirection.READ),
                collection.resourceDirections);

        collection.clearDirections();
        try (CloseableIterator<String> iterator = collection.iterator()) {
            TestAssertions.assertTrue(iterator.hasNext());
            TestAssertions.assertEquals(
                    Collections.singletonList(RequestDirection.MIXED),
                    collection.scopeDirections);
            TestAssertions.assertEquals(
                    Collections.singletonList(RequestDirection.MIXED),
                    collection.resourceDirections);
        }
    }

    @Test
    public void testDirectionIsVisibleWhenPersistenceResourcesOpen() {
        DirectionInspectingPersistence persistence = new DirectionInspectingPersistence();
        ConcurrentIndexedCollection<String> collection =
                new ConcurrentIndexedCollection<String>(persistence);
        TestAssertions.assertEquals(
                Collections.singletonList(RequestDirection.WRITE),
                persistence.openDirections);

        persistence.openDirections.clear();
        collection.size();
        TestAssertions.assertEquals(
                Collections.singletonList(RequestDirection.READ),
                persistence.openDirections);

        persistence.openDirections.clear();
        collection.add("one");
        TestAssertions.assertEquals(
                Collections.singletonList(RequestDirection.WRITE),
                persistence.openDirections);
    }

    @Test
    public void testReusedQueryOptionsReplacePreviousDirection() {
        ConcurrentIndexedCollection<String> collection = new ConcurrentIndexedCollection<String>();
        QueryOptions queryOptions = new QueryOptions();

        try (ResultSet<String> results = collection.retrieve(
                QueryFactory.all(String.class), queryOptions)) {
            TestAssertions.assertTrue(results.isEmpty());
            TestAssertions.assertEquals(RequestDirection.READ, directionOf(queryOptions));
        }

        collection.update(
                Collections.<String>emptySet(),
                Collections.singleton("one"),
                queryOptions);
        TestAssertions.assertEquals(RequestDirection.WRITE, directionOf(queryOptions));

        try (ResultSet<String> results = collection.retrieve(
                QueryFactory.all(String.class), queryOptions)) {
            TestAssertions.assertEquals(1, results.size());
            TestAssertions.assertEquals(RequestDirection.READ, directionOf(queryOptions));
        }
    }

    static class ConstructionGuardedCollection extends ConcurrentIndexedCollection<String> {
        boolean constructed;

        ConstructionGuardedCollection() {
            constructed = true;
        }

        @Override
        public Iterable<Index<String>> getIndexes() {
            assertConstructed();
            return super.getIndexes();
        }

        @Override
        protected QueryOptions openRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
            assertConstructed();
            return super.openRequestScopeResourcesIfNecessary(queryOptions);
        }

        @Override
        protected void closeRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
            assertConstructed();
            super.closeRequestScopeResourcesIfNecessary(queryOptions);
        }

        private void assertConstructed() {
            if (!constructed) {
                throw new AssertionError("MetadataEngine invoked a collection callback during construction");
            }
        }
    }

    enum RequestDirection {
        READ,
        WRITE,
        MIXED
    }

    static RequestDirection directionOf(QueryOptions queryOptions) {
        boolean read = queryOptions != null
                && FlagsEnabled.isFlagEnabled(queryOptions, PersistenceFlags.READ_REQUEST);
        boolean write = queryOptions != null
                && FlagsEnabled.isFlagEnabled(queryOptions, PersistenceFlags.WRITE_REQUEST);
        if (read && write) {
            throw new AssertionError("A request cannot be classified as both read and write");
        }
        if (read) {
            return RequestDirection.READ;
        }
        if (write) {
            return RequestDirection.WRITE;
        }
        return RequestDirection.MIXED;
    }

    static class DirectionAwareCollection extends ConcurrentIndexedCollection<String> {
        final List<RequestDirection> scopeDirections = new ArrayList<RequestDirection>();
        final List<RequestDirection> resourceDirections = new ArrayList<RequestDirection>();
        boolean constructed;

        DirectionAwareCollection() {
            constructed = true;
        }

        @Override
        protected RequestScope openRequestScope(QueryOptions queryOptions) {
            assertConstructed();
            scopeDirections.add(directionOf(queryOptions));
            return super.openRequestScope(queryOptions);
        }

        @Override
        protected QueryOptions openRequestScopeResourcesIfNecessary(QueryOptions queryOptions) {
            assertConstructed();
            resourceDirections.add(directionOf(queryOptions));
            return super.openRequestScopeResourcesIfNecessary(queryOptions);
        }

        void clearDirections() {
            scopeDirections.clear();
            resourceDirections.clear();
        }

        private void assertConstructed() {
            if (!constructed) {
                throw new AssertionError("Request-scope extension hook invoked during construction");
            }
        }
    }

    static class DirectionInspectingPersistence extends WrappingPersistence<String, String> {
        final List<RequestDirection> openDirections = new ArrayList<RequestDirection>();

        DirectionInspectingPersistence() {
            super(new LinkedHashSet<String>());
        }

        @Override
        public void openRequestScopeResources(QueryOptions queryOptions) {
            openDirections.add(directionOf(queryOptions));
            super.openRequestScopeResources(queryOptions);
        }
    }


    static <O> Iterable<O> asIterable(final Collection<O> collection) {
        return new Iterable<O>() {
            @Override
            public Iterator<O> iterator() {
                return collection.iterator();
            }
        };
    }
}
