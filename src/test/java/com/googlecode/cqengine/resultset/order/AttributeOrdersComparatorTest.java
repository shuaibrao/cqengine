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
package com.googlecode.cqengine.resultset.order;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.AttributeOrder;
import com.googlecode.cqengine.query.option.OrderByOption;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.googlecode.cqengine.query.QueryFactory.*;
import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;

/**
 * @author Roberto Socrates
 * @author Niall Gallagher
 */
public class AttributeOrdersComparatorTest {

    @Test
    public void comparatorSnapshotsItsSortOrders() {
        List<AttributeOrder<Car>> source = new ArrayList<AttributeOrder<Car>>();
        source.add(ascending(Car.CAR_ID));
        AttributeOrdersComparator<Car> comparator =
                new AttributeOrdersComparator<Car>(source, noQueryOptions());
        source.clear();

        Car first = new Car(1, "Ford", "Focus", Car.Color.BLUE, 5, 5000.00,
                Collections.<String>emptyList(), Collections.emptyList());
        Car second = new Car(2, "Ford", "Focus", Car.Color.BLUE, 5, 5000.00,
                Collections.<String>emptyList(), Collections.emptyList());
        TestAssertions.assertTrue(comparator.compare(first, second) < 0);
    }

    @Test
    public void equalHashCodesStillProduceAnAntisymmetricTotalOrder() {
        SimpleAttribute<CollidingObject, Integer> group =
                new SimpleAttribute<CollidingObject, Integer>("group") {
                    @Override
                    public Integer getValue(CollidingObject object, QueryOptions queryOptions) {
                        return object.group;
                    }
                };
        AttributeOrdersComparator<CollidingObject> comparator = new AttributeOrdersComparator<CollidingObject>(
                Collections.singletonList(ascending(group)), noQueryOptions());
        CollidingObject first = new CollidingObject(1, 1);
        CollidingObject second = new CollidingObject(2, 1);
        CollidingObject third = new CollidingObject(3, 1);

        int firstToSecond = comparator.compare(first, second);
        int secondToFirst = comparator.compare(second, first);
        TestAssertions.assertEquals(-Integer.signum(firstToSecond), Integer.signum(secondToFirst));
        TestAssertions.assertTrue(comparator.compare(first, third) < 0);
        TestAssertions.assertTrue(comparator.compare(second, third) < 0);

        List<CollidingObject> objects = new ArrayList<CollidingObject>(Arrays.asList(third, first, second));
        objects.sort(comparator);
        TestAssertions.assertEquals(Arrays.asList(first, second, third), objects);
    }

    @Test
    public void tieBreakerIdsDoNotRetainCollectedObjects() throws InterruptedException {
        SimpleAttribute<CollidingObject, Integer> group =
                new SimpleAttribute<CollidingObject, Integer>("group") {
                    @Override
                    public Integer getValue(CollidingObject object, QueryOptions queryOptions) {
                        return object.group;
                    }
                };
        AttributeOrdersComparator<CollidingObject> comparator = new AttributeOrdersComparator<CollidingObject>(
                Collections.singletonList(ascending(group)), noQueryOptions());

        CollidingObject first = new CollidingObject(1, 1);
        CollidingObject second = new CollidingObject(2, 1);
        comparator.compare(first, second);
        TestAssertions.assertEquals(2, comparator.retainedTieBreakerCount());

        // Ids must stay stable while the objects are alive...
        int comparison = comparator.compare(first, second);
        TestAssertions.assertEquals(comparison, comparator.compare(first, second));
        TestAssertions.assertEquals(2, comparator.retainedTieBreakerCount());

        // ...and must be released once the objects become unreachable.
        java.lang.ref.WeakReference<CollidingObject> firstRef = new java.lang.ref.WeakReference<CollidingObject>(first);
        java.lang.ref.WeakReference<CollidingObject> secondRef = new java.lang.ref.WeakReference<CollidingObject>(second);
        first = null;
        second = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while ((firstRef.get() != null || secondRef.get() != null || comparator.retainedTieBreakerCount() > 0)
                && System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(10);
        }
        TestAssertions.assertEquals(0, comparator.retainedTieBreakerCount());
    }

    @Test
    public void testSortAscending() {
        List<Car> cars = Arrays.asList(
                new Car(0, "Ford",  "Taurus", Car.Color.BLACK, 4, 7000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(1, "Ford",  "Focus",  Car.Color.BLUE,  5, 5000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(2, "BMW",   "M6",     Car.Color.RED,   2, 9000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(3, "Honda", "Civic",  Car.Color.WHITE, 5, 6000.00, Collections.<String>emptyList(), Collections.emptyList())
        );

        OrderByOption<Car> ordering = orderBy(ascending(Car.MANUFACTURER), ascending(Car.PRICE));
        Collections.sort(cars, new AttributeOrdersComparator<Car>(ordering.getAttributeOrders(), noQueryOptions()));

        List<Car> expected = Arrays.asList(
            new Car(2, "BMW",   "M6",     Car.Color.RED,   2, 9000.00, Collections.<String>emptyList(), Collections.emptyList()),
            new Car(1, "Ford",  "Focus",  Car.Color.BLUE,  5, 5000.00, Collections.<String>emptyList(), Collections.emptyList()),
            new Car(0, "Ford",  "Taurus", Car.Color.BLACK, 4, 7000.00, Collections.<String>emptyList(), Collections.emptyList()),
            new Car(3, "Honda", "Civic",  Car.Color.WHITE, 5, 6000.00, Collections.<String>emptyList(), Collections.emptyList())
        );

        TestAssertions.assertEquals(expected, cars);
    }

    @Test
    public void testSortDescending() {
        List<Car> cars = Arrays.asList(
                new Car(0, "Ford",  "Taurus", Car.Color.BLACK, 4, 7000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(1, "Ford",  "Focus",  Car.Color.BLUE,  5, 5000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(2, "BMW",   "M6",     Car.Color.RED,   2, 9000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(3, "Honda", "Civic",  Car.Color.WHITE, 5, 6000.00, Collections.<String>emptyList(), Collections.emptyList())
        );

        OrderByOption<Car> ordering = orderBy(descending(Car.MANUFACTURER), descending(Car.PRICE));
        Collections.sort(cars, new AttributeOrdersComparator<Car>(ordering.getAttributeOrders(), noQueryOptions()));

        List<Car> expected = Arrays.asList(
                new Car(3, "Honda", "Civic",  Car.Color.WHITE, 5, 6000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(0, "Ford",  "Taurus", Car.Color.BLACK, 4, 7000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(1, "Ford",  "Focus",  Car.Color.BLUE,  5, 5000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(2, "BMW",   "M6",     Car.Color.RED,   2, 9000.00, Collections.<String>emptyList(), Collections.emptyList())
        );

        TestAssertions.assertEquals(expected, cars);
    }

    @Test
    public void testSortMixed() {
        List<Car> cars = Arrays.asList(
                new Car(0, "Ford",  "Taurus", Car.Color.BLACK, 4, 2000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(1, "Ford",  "Taurus", Car.Color.BLACK, 4, 1000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(3, "Honda", "Civic",  Car.Color.BLACK, 4, 4000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(3, "Honda", "Civic",  Car.Color.BLACK, 4, 3000.00, Collections.<String>emptyList(), Collections.emptyList())
        );

        OrderByOption<Car> ordering = orderBy(descending(Car.MANUFACTURER), ascending(Car.PRICE));
        Collections.sort(cars, new AttributeOrdersComparator<Car>(ordering.getAttributeOrders(), noQueryOptions()));

        List<Car> expected = Arrays.asList(
                new Car(3, "Honda", "Civic",  Car.Color.BLACK, 4, 3000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(3, "Honda", "Civic",  Car.Color.BLACK, 4, 4000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(1, "Ford",  "Taurus", Car.Color.BLACK, 4, 1000.00, Collections.<String>emptyList(), Collections.emptyList()),
                new Car(0, "Ford",  "Taurus", Car.Color.BLACK, 4, 2000.00, Collections.<String>emptyList(), Collections.emptyList())
        );

        TestAssertions.assertEquals(expected, cars);
    }

    static final class CollidingObject {
        final int id;
        final int group;

        CollidingObject(int id, int group) {
            this.id = id;
            this.group = group;
        }

        @Override
        public boolean equals(Object object) {
            return this == object || object instanceof CollidingObject && id == ((CollidingObject) object).id;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
