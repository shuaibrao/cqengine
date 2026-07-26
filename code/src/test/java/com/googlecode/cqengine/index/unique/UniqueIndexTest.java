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
package com.googlecode.cqengine.index.unique;

import com.googlecode.cqengine.testutil.ExpectedException;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.examples.introduction.Car;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.persistence.support.ObjectSet;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.googlecode.cqengine.query.QueryFactory.*;


/**
 * @author Niall Gallagher
 */
public class UniqueIndexTest {

    @Test
    public void testUniqueIndex() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>();

        // Add some indexes...
        cars.addIndex(UniqueIndex.onAttribute(Car.CAR_ID));
        cars.addIndex(HashIndex.onAttribute(Car.CAR_ID));

        // Add some objects to the collection...
        cars.add(new Car(1, "ford focus", "great condition, low mileage", Arrays.asList("spare tyre", "sunroof")));
        cars.add(new Car(2, "ford taurus", "dirty and unreliable, flat tyre", Arrays.asList("spare tyre", "radio")));
        cars.add(new Car(3, "honda civic", "has a flat tyre and high mileage", Arrays.asList("radio")));

        Query<Car> query = equal(Car.CAR_ID, 2);
        ResultSet<Car> rs = cars.retrieve(query);
        TestAssertions.assertEquals("should prefer unique index over hash index", UniqueIndex.INDEX_RETRIEVAL_COST, rs.getRetrievalCost());

        TestAssertions.assertEquals("should retrieve car 2", 2, rs.uniqueResult().carId);
    }

    @Test
    public void iteratorNextThrowsWhenUniqueResultIsAbsentOrExhausted() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>();
        cars.addIndex(UniqueIndex.onAttribute(Car.CAR_ID));
        Car car = new Car(1, "ford focus", "", Collections.<String>emptyList());
        cars.add(car);

        try (ResultSet<Car> absent = cars.retrieve(equal(Car.CAR_ID, 2))) {
            Iterator<Car> iterator = absent.iterator();
            TestAssertions.assertFalse(iterator.hasNext());
            TestAssertions.assertThrows(NoSuchElementException.class, iterator::next);
        }

        try (ResultSet<Car> present = cars.retrieve(equal(Car.CAR_ID, 1))) {
            Iterator<Car> iterator = present.iterator();
            TestAssertions.assertSame(car, iterator.next());
            TestAssertions.assertFalse(iterator.hasNext());
            TestAssertions.assertThrows(NoSuchElementException.class, iterator::next);
        }
    }

    @Test
    @ExpectedException(UniqueIndex.UniqueConstraintViolatedException.class)
    public void testDuplicateObjectDetection_SimpleAttribute() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>();

        // Add some indexes...
        cars.addIndex(UniqueIndex.onAttribute(Car.CAR_ID));

        // Add some objects to the collection...
        cars.add(new Car(1, "ford focus", "great condition, low mileage", Arrays.asList("spare tyre", "sunroof")));
        cars.add(new Car(2, "ford taurus", "dirty and unreliable, flat tyre", Arrays.asList("spare tyre", "radio")));
        cars.add(new Car(3, "honda civic", "has a flat tyre and high mileage", Arrays.asList("radio")));

        cars.add(new Car(2, "some other car", "foo", Arrays.asList("bar")));
    }

    @Test
    @ExpectedException(UniqueIndex.UniqueConstraintViolatedException.class)
    public void testDuplicateObjectDetection_MultiValueAttribute() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>();

        // Add some indexes...
        cars.addIndex(UniqueIndex.onAttribute(Car.FEATURES));

        // Add some objects to the collection...
        cars.add(new Car(1, "ford focus", "foo", Arrays.asList("spare tyre", "sunroof")));
        cars.add(new Car(2, "ford taurus", "bar", Arrays.asList("radio", "cd player")));

        // Try to add another car which has a cd player, when one car already has a cd player...
        cars.add(new Car(3, "honda civic", "baz", Arrays.asList("cd player", "bluetooth")));
    }

    @Test
    public void testFailedSingleAddPreservesExistingMapping() {
        UniqueIndex<Integer, Car> index = UniqueIndex.onAttribute(Car.CAR_ID);
        Car existing = car(1, "existing");
        Car duplicate = car(1, "duplicate");
        add(index, existing);

        assertConstraintViolation(index, duplicate);

        TestAssertions.assertEquals(1, index.indexMap.size());
        TestAssertions.assertSame(existing, index.indexMap.get(1));
    }

    @Test
    public void testFailedSingleAddRollsBackEarlierAttributeValues() {
        UniqueIndex<String, Car> index = UniqueIndex.onAttribute(Car.FEATURES);
        Car existing = new Car(1, "existing", "", Collections.singletonList("shared"));
        Car duplicate = new Car(2, "duplicate", "", Arrays.asList("candidate-only", "shared"));
        add(index, existing);

        assertConstraintViolation(index, duplicate);

        TestAssertions.assertEquals(1, index.indexMap.size());
        TestAssertions.assertSame(existing, index.indexMap.get("shared"));
        TestAssertions.assertFalse(index.indexMap.containsKey("candidate-only"));
    }

    @Test
    public void testFailedBatchAddRollsBackEntireBatch() {
        UniqueIndex<Integer, Car> index = UniqueIndex.onAttribute(Car.CAR_ID);
        Car existing = car(3, "existing");
        add(index, existing);

        Car first = car(1, "first");
        Car second = car(2, "second");
        Car duplicate = car(3, "duplicate");
        assertConstraintViolation(index, Arrays.asList(first, second, duplicate));

        TestAssertions.assertEquals(1, index.indexMap.size());
        TestAssertions.assertSame(existing, index.indexMap.get(3));
        TestAssertions.assertFalse(index.indexMap.containsKey(1));
        TestAssertions.assertFalse(index.indexMap.containsKey(2));
    }

    @Test
    public void testDuplicateValuesWithinBatchRollBackEntireBatch() {
        UniqueIndex<Integer, Car> index = UniqueIndex.onAttribute(Car.CAR_ID);
        Car first = car(1, "first");
        Car duplicate = car(1, "duplicate");

        assertConstraintViolation(index, Arrays.asList(first, duplicate));

        TestAssertions.assertTrue(index.indexMap.isEmpty());
    }

    @Test
    public void testRemovingNonMemberDoesNotRemoveMemberWithSameKey() {
        UniqueIndex<Integer, Car> index = UniqueIndex.onAttribute(Car.CAR_ID);
        Car member = car(1, "member");
        Car nonMember = car(1, "non-member");
        add(index, member);

        boolean modified = index.removeAll(
                ObjectSet.fromCollection(Collections.singleton(nonMember)), noQueryOptions());

        TestAssertions.assertFalse(modified);
        TestAssertions.assertEquals(1, index.indexMap.size());
        TestAssertions.assertSame(member, index.indexMap.get(1));
        TestAssertions.assertTrue(index.removeAll(
                ObjectSet.fromCollection(Collections.singleton(member)), noQueryOptions()));
        TestAssertions.assertTrue(index.indexMap.isEmpty());
    }

    @Test
    public void testReaddingSameObjectIsIdempotent() {
        UniqueIndex<Integer, Car> index = UniqueIndex.onAttribute(Car.CAR_ID);
        Car member = car(1, "member");

        TestAssertions.assertTrue(add(index, member));
        TestAssertions.assertFalse(add(index, member));

        TestAssertions.assertEquals(1, index.indexMap.size());
        TestAssertions.assertSame(member, index.indexMap.get(1));
    }

    @Test
    public void testConcurrentCompetingAddsPreserveWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                UniqueIndex<Integer, Car> index = UniqueIndex.onAttribute(Car.CAR_ID);
                Car first = car(1, "first");
                Car second = car(1, "second");
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);

                Future<Boolean> firstResult = executor.submit(addTask(index, first, ready, start));
                Future<Boolean> secondResult = executor.submit(addTask(index, second, ready, start));
                TestAssertions.assertTrue("Workers did not become ready", ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                boolean firstSucceeded = addSucceeded(firstResult);
                boolean secondSucceeded = addSucceeded(secondResult);
                TestAssertions.assertTrue("Exactly one competing add must succeed", firstSucceeded ^ secondSucceeded);
                TestAssertions.assertEquals(1, index.indexMap.size());
                TestAssertions.assertSame(firstSucceeded ? first : second, index.indexMap.get(1));
            }
        }
        finally {
            executor.shutdownNow();
            TestAssertions.assertTrue("Executor did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Car car(int id, String name) {
        return new Car(id, name, "", Collections.<String>emptyList());
    }

    private static <A> boolean add(UniqueIndex<A, Car> index, Car car) {
        return index.addAll(ObjectSet.fromCollection(Collections.singleton(car)), noQueryOptions());
    }

    private static <A> void assertConstraintViolation(UniqueIndex<A, Car> index, Car car) {
        assertConstraintViolation(index, Collections.singletonList(car));
    }

    private static <A> void assertConstraintViolation(UniqueIndex<A, Car> index, List<Car> cars) {
        try {
            index.addAll(ObjectSet.fromCollection(cars), noQueryOptions());
        }
        catch (UniqueIndex.UniqueConstraintViolatedException expected) {
            return;
        }
        TestAssertions.fail("Expected unique constraint violation");
    }

    private static Callable<Boolean> addTask(final UniqueIndex<Integer, Car> index,
                                             final Car car,
                                             final CountDownLatch ready,
                                             final CountDownLatch start) {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for competing add");
                }
                return add(index, car);
            }
        };
    }

    private static boolean addSucceeded(Future<Boolean> result) throws Exception {
        try {
            return result.get(5, TimeUnit.SECONDS);
        }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof UniqueIndex.UniqueConstraintViolatedException) {
                return false;
            }
            throw failure;
        }
    }
}
