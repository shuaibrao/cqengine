// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.index.compound.support;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.testutil.Car;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertThrows;

public class CompoundValueObjectsTest {

    @Test
    public void compoundAttributeSnapshotsItsComponentAttributes() {
        List<Attribute<Car, ?>> source = new ArrayList<Attribute<Car, ?>>(
                Arrays.<Attribute<Car, ?>>asList(Car.CAR_ID, Car.MANUFACTURER, Car.CAR_ID));
        CompoundAttribute<Car> attribute = new CompoundAttribute<Car>(source);
        CompoundAttribute<Car> expected = new CompoundAttribute<Car>(
                Arrays.<Attribute<Car, ?>>asList(Car.CAR_ID, Car.MANUFACTURER, Car.CAR_ID));
        int originalHashCode = attribute.hashCode();

        source.clear();

        assertEquals(3, attribute.size());
        assertEquals(expected, attribute);
        assertEquals(originalHashCode, attribute.hashCode());
    }

    @Test
    public void compoundTupleSnapshotsAndProtectsItsValues() {
        List<Object> source = new ArrayList<Object>(Arrays.<Object>asList(1, "value", 1));
        CompoundValueTuple<Object> tuple = new CompoundValueTuple<Object>(source);
        int originalHashCode = tuple.hashCode();

        source.set(0, 2);

        assertEquals(new CompoundValueTuple<Object>(Arrays.<Object>asList(1, "value", 1)), tuple);
        assertEquals(originalHashCode, tuple.hashCode());
        Iterator<Object> iterator = tuple.getAttributeValues().iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
    }
}
