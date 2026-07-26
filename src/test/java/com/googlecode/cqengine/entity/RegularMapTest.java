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
package com.googlecode.cqengine.entity;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.index.radix.RadixTreeIndex;
import com.googlecode.cqengine.index.radixinverted.InvertedRadixTreeIndex;
import com.googlecode.cqengine.index.radixreversed.ReversedRadixTreeIndex;
import com.googlecode.cqengine.index.suffix.SuffixTreeIndex;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.testutil.Car;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.googlecode.cqengine.query.QueryFactory.*;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestUtil.setOf;
import static com.googlecode.cqengine.testutil.TestUtil.valuesOf;

/**
 * Validates general functionality using Map as collection element - indexes, query engine, ordering results.
 *
 * @author Niall Gallagher
 */
@SuppressWarnings("rawtypes") // This suite intentionally verifies CQEngine's legacy raw-Map public API.
public class RegularMapTest {

    private static final Attribute<Map, String> MODEL = mapAttribute("MODEL", String.class);
    private static final Attribute<Map, Integer> DOORS = mapAttribute("DOORS", Integer.class);
    private static final Attribute<Map, Car.Color> COLOR = mapAttribute("COLOR",Car.Color.class);
    private static final Attribute<Map, String> MANUFACTURER = mapAttribute("MANUFACTURER", String.class);
    private static final Attribute<Map, Double> PRICE = mapAttribute("PRICE", Double.class);
    private static final Attribute<Map, Integer> CAR_ID = mapAttribute("CAR_ID", Integer.class);

    @Test
    public void testMapFunctionality() {
        IndexedCollection<Map> cars = new ConcurrentIndexedCollection<Map>();

        cars.addIndex(HashIndex.onAttribute(COLOR));
        cars.addIndex(NavigableIndex.onAttribute(DOORS));
        cars.addIndex(RadixTreeIndex.onAttribute(MODEL));
        cars.addIndex(ReversedRadixTreeIndex.onAttribute(MODEL));
        cars.addIndex(InvertedRadixTreeIndex.onAttribute(MODEL));
        cars.addIndex(SuffixTreeIndex.onAttribute(MODEL));

        cars.add(buildNewCar(1, "Ford",   "Focus",  Car.Color.BLUE,  5, 9000.50, Collections.<String>emptyList()));
        cars.add(buildNewCar(2, "Ford",   "Fiesta", Car.Color.BLUE,  2, 5000.00, Collections.<String>emptyList()));
        cars.add(buildNewCar(3, "Ford",   "F-150",  Car.Color.RED,   2, 9500.00, Collections.<String>emptyList()));
        cars.add(buildNewCar(4, "Honda",  "Civic",  Car.Color.RED,   5, 5000.00, Collections.<String>emptyList()));
        cars.add(buildNewCar(5, "Toyota", "Prius",  Car.Color.BLACK, 3, 9700.00, Collections.<String>emptyList()));

        // Ford cars...
        assertEquals(setOf(1, 2, 3), carIdsIn(cars.retrieve(equal(MANUFACTURER, "Ford"))));

        // 3-door cars...
        assertEquals(setOf(5), carIdsIn(cars.retrieve(equal(DOORS, 3))));

        // 2 or 3-door cars...
        assertEquals(setOf(2, 3, 5), carIdsIn(cars.retrieve(between(DOORS, 2, 3))));

        // 2 or 5-door cars...
        assertEquals(setOf(1, 2, 3, 4), carIdsIn(cars.retrieve(in(DOORS, 2, 5))));

        // Blue Ford cars...
        assertEquals(
                setOf(1, 2),
                carIdsIn(cars.retrieve(and(equal(COLOR, Car.Color.BLUE), equal(MANUFACTURER, "Ford")))));

        // NOT 3-door cars...
        assertEquals(setOf(1, 2, 3, 4), carIdsIn(cars.retrieve(not(equal(DOORS, 3)))));

        // Cars which have 5 doors and which are not red...
        assertEquals(
                setOf(1),
                carIdsIn(cars.retrieve(and(equal(DOORS, 5), not(equal(COLOR, Car.Color.RED))))));

        // Cars whose model starts with 'F'...
        assertEquals(setOf(1, 2, 3), carIdsIn(cars.retrieve(startsWith(MODEL, "F"))));

        // Cars whose model ends with 's'...
        assertEquals(setOf(1, 5), carIdsIn(cars.retrieve(endsWith(MODEL, "s"))));

        // Cars whose model contains 'i'...
        assertEquals(setOf(2, 4, 5), carIdsIn(cars.retrieve(contains(MODEL, "i"))));

        // Cars whose model is contained in 'Banana, Focus, Civic, Foobar'...
        assertEquals(
                setOf(1, 4),
                carIdsIn(cars.retrieve(isContainedIn(MODEL, "Banana, Focus, Civic, Foobar"))));

        // NOT 3-door cars, sorted by doors ascending...
        assertEquals(
                setOf(3, 2, 4, 1).toString(),
                carIdsIn(cars.retrieve(
                                not(equal(DOORS, 3)),
                                queryOptions(orderBy(ascending(DOORS), ascending(MODEL)))))
                        .toString());

        // NOT 3-door cars, sorted by doors ascending then price descending...
        assertEquals(
                setOf(3, 2, 1, 4),
                carIdsIn(
                        cars.retrieve(
                                not(equal(DOORS, 3)),
                                queryOptions(
                                        orderBy(ascending(DOORS),
                                                descending(PRICE))
                                )
                        )
                ));
    }

    static Set<Integer> carIdsIn(ResultSet<Map> resultSet) {
        return valuesOf(CAR_ID, resultSet);
    }

    protected Map buildNewCar(int carId, String manufacturer, String model, Car.Color color, int doors, double price, List<String> features) {
        return createMap(carId, manufacturer, model, color, doors, price, features);
    }

    @SuppressWarnings("unchecked")
    protected Map createMap(int carId, String manufacturer, String model, Car.Color color, int doors, double price, List<String> features) {
        Map map = new HashMap();
        map.put("CAR_ID", carId);
        map.put("MANUFACTURER", manufacturer);
        map.put("MODEL", model);
        map.put("COLOR", color);
        map.put("DOORS", doors);
        map.put("PRICE", price);
        map.put("FEATURES", features);
        return map;
    }


}
