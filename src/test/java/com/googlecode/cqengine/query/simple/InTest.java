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
package com.googlecode.cqengine.query.simple;

import com.googlecode.cqengine.testutil.ExpectedException;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleNullableAttribute;
import com.googlecode.cqengine.examples.introduction.Car;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.googlecode.cqengine.query.QueryFactory.in;

/**
 * @author Kevin Minder
 * @author Niall Gallagher
 */
public class InTest {

    @Test
    public void testInMany() {
        // Create an indexed collection (note: could alternatively use CQEngine.copyFrom() existing collection)...
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>();

        Attribute<Car, String> NAME = new SimpleNullableAttribute<Car, String>("name") {
            public String getValue(Car car, QueryOptions queryOptions) {
                return car.name;
            }
        };
        cars.addIndex(NavigableIndex.onAttribute(NAME));

        // Add some objects to the collection...
        cars.add(new Car(1, "ford", null, null));
        cars.add(new Car(2, "honda", null, null));
        cars.add(new Car(3, "toyota", null, null));

        TestAssertions.assertEquals(cars.retrieve(in(NAME, "ford", "honda")).size(), 2);
        TestAssertions.assertEquals(cars.retrieve(in(NAME, Arrays.asList("ford", "honda"))).size(), 2);
    }

    @Test
    public void testInOne() {
        // Create an indexed collection (note: could alternatively use CQEngine.copyFrom() existing collection)...
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>();

        Attribute<Car, String> NAME = new SimpleNullableAttribute<Car, String>("name") {
            public String getValue(Car car, QueryOptions queryOptions) {
                return car.name;
            }
        };
        cars.addIndex(NavigableIndex.onAttribute(NAME));

        // Add some objects to the collection...
        cars.add(new Car(1, "ford", null, null));
        cars.add(new Car(2, "honda", null, null));
        cars.add(new Car(3, "toyota", null, null));

        TestAssertions.assertEquals(cars.retrieve(in(NAME, "ford")).size(), 1);
        TestAssertions.assertEquals(cars.retrieve(in(NAME, Collections.singletonList("ford"))).size(), 1);
    }

    @Test
    public void testInNone() {
        // Create an indexed collection (note: could alternatively use CQEngine.copyFrom() existing collection)...
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>();

        Attribute<Car, String> NAME = new SimpleNullableAttribute<Car, String>("name") {
            public String getValue(Car car, QueryOptions queryOptions) {
                return car.name;
            }
        };
        cars.addIndex(NavigableIndex.onAttribute(NAME));

        // Add some objects to the collection...
        cars.add(new Car(1, "ford", null, null));
        cars.add(new Car(2, "honda", null, null));
        cars.add(new Car(3, "toyota", null, null));

        TestAssertions.assertEquals(cars.retrieve(in(NAME)).size(), 0);
        TestAssertions.assertEquals(cars.retrieve(in(NAME, new ArrayList<String>())).size(), 0);
    }

    @Test
    @ExpectedException(NullPointerException.class)
    public void testInNull() {
        Attribute<Car, String> NAME = new SimpleNullableAttribute<Car, String>("name") {
            public String getValue(Car car, QueryOptions queryOptions) {
                return car.name;
            }
        };
        in(NAME, (Collection<String>) null);
    }

    @Test
    public void snapshotsValuesToKeepItsCachedHashAndMatchesStable() {
        Attribute<Car, String> name = new SimpleNullableAttribute<Car, String>("name") {
            public String getValue(Car car, QueryOptions queryOptions) {
                return car.name;
            }
        };
        Set<String> sourceValues = new LinkedHashSet<String>(Arrays.asList("ford", "honda"));
        In<Car, String> query = new In<Car, String>(name, true, sourceValues);
        int originalHashCode = query.hashCode();

        sourceValues.clear();
        sourceValues.add("toyota");

        TestAssertions.assertEquals(Arrays.asList("ford", "honda"), new ArrayList<String>(query.getValues()));
        TestAssertions.assertEquals(originalHashCode, query.hashCode());
        TestAssertions.assertThrows(UnsupportedOperationException.class, () -> query.getValues().add("toyota"));
    }
}
