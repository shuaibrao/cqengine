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
package com.googlecode.cqengine.query.option;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import com.googlecode.cqengine.testutil.Car;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.googlecode.cqengine.query.QueryFactory.ascending;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertThrows;

public class OrderByOptionTest {
    @Test
    public void testEqualsAndHashCode() {
        EqualsVerifier.forClass(OrderByOption.class)
                .suppress(Warning.NULL_FIELDS, Warning.STRICT_INHERITANCE)
                .verify();
    }

    @Test
    public void snapshotsAndProtectsAttributeOrders() {
        List<AttributeOrder<Car>> source = new ArrayList<AttributeOrder<Car>>();
        AttributeOrder<Car> first = ascending(Car.CAR_ID);
        AttributeOrder<Car> second = ascending(Car.MANUFACTURER);
        source.add(first);
        source.add(second);
        List<AttributeOrder<Car>> expected = new ArrayList<AttributeOrder<Car>>(source);
        OrderByOption<Car> option = new OrderByOption<Car>(source);
        int originalHashCode = option.hashCode();

        source.clear();

        assertEquals(expected, option.getAttributeOrders());
        assertEquals(originalHashCode, option.hashCode());
        assertThrows(UnsupportedOperationException.class, () -> option.getAttributeOrders().clear());
    }
}
