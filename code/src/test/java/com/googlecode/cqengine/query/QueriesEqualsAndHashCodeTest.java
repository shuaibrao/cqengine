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
package com.googlecode.cqengine.query;

import com.googlecode.cqengine.query.comparative.LongestPrefix;
import com.googlecode.cqengine.query.comparative.Max;
import com.googlecode.cqengine.query.comparative.Min;
import com.googlecode.cqengine.query.logical.And;
import com.googlecode.cqengine.query.logical.LogicalQuery;
import com.googlecode.cqengine.query.logical.Not;
import com.googlecode.cqengine.query.logical.Or;
import com.googlecode.cqengine.query.simple.*;
import com.googlecode.cqengine.testutil.Car;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.googlecode.cqengine.query.QueryFactory.*;
import static java.util.Collections.singletonList;

/**
 * @author Niall Gallagher
 */
public class QueriesEqualsAndHashCodeTest {

    /**
     * Returns Query classes whose equals() and hashCode() methods can be validated by EqualsVerifier in a uniform way.
     */
    public static Stream<Class<?>> getQueryClassesForAutomatedValidation() {
        return Stream.of(
                Equal.class,
                In.class,
                Has.class,
                LessThan.class,
                GreaterThan.class,
                Between.class,
                StringStartsWith.class,
                StringEndsWith.class,
                StringContains.class,
                StringIsContainedIn.class,
                StringMatchesRegex.class,
                LongestPrefix.class,
                Min.class,
                Max.class
        );
    }

    /**
     * Parameterized test which validates a Query class using EqualsVerifier.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("getQueryClassesForAutomatedValidation")
    public void testQueryClass(Class<?> queryClass) {
        EqualsVerifier.forClass(queryClass)
                .withIgnoredFields("attributeIsSimple", "simpleAttribute")
                .withCachedHashCode("cachedHashCode", "calcHashCode", null)
                .suppress(Warning.NULL_FIELDS, Warning.STRICT_INHERITANCE, Warning.NO_EXAMPLE_FOR_CACHED_HASHCODE)
                .verify();
    }

    @Test
    public void testAnd() {
        EqualsVerifier.forClass(And.class)
                .withIgnoredFields("logicalQueries", "simpleQueries", "comparativeQueries", "hasLogicalQueries", "hasSimpleQueries", "hasComparativeQueries", "size")
                .withPrefabValues(And.class, and(equal(Car.CAR_ID, 1), equal(Car.CAR_ID, 2)), and(equal(Car.CAR_ID, 3), equal(Car.CAR_ID, 4)))
                .withPrefabValues(LogicalQuery.class, and(equal(Car.CAR_ID, 1), equal(Car.CAR_ID, 2)), and(equal(Car.CAR_ID, 3), equal(Car.CAR_ID, 4)))
                .withCachedHashCode("cachedHashCode", "calcHashCode", null)
                .suppress(Warning.NULL_FIELDS, Warning.STRICT_INHERITANCE, Warning.NO_EXAMPLE_FOR_CACHED_HASHCODE)
                .verify();
    }

    @Test
    public void testOr() {
        EqualsVerifier.forClass(Or.class)
                .withIgnoredFields("logicalQueries", "simpleQueries", "comparativeQueries", "hasLogicalQueries", "hasSimpleQueries", "hasComparativeQueries", "size")
                .withPrefabValues(Or.class, or(equal(Car.CAR_ID, 1), equal(Car.CAR_ID, 2)), or(equal(Car.CAR_ID, 3), equal(Car.CAR_ID, 4)))
                .withPrefabValues(LogicalQuery.class, or(equal(Car.CAR_ID, 1), equal(Car.CAR_ID, 2)), or(equal(Car.CAR_ID, 3), equal(Car.CAR_ID, 4)))
                .withCachedHashCode("cachedHashCode", "calcHashCode", null)
                .suppress(Warning.NULL_FIELDS, Warning.STRICT_INHERITANCE, Warning.NO_EXAMPLE_FOR_CACHED_HASHCODE)
                .verify();
    }

    @Test
    public void testNot() {
        EqualsVerifier.forClass(Not.class)
                .withIgnoredFields("logicalQueries", "childQueries", "simpleQueries", "comparativeQueries", "hasLogicalQueries", "hasSimpleQueries", "hasComparativeQueries", "size")
                .withPrefabValues(Not.class, not(equal(Car.CAR_ID, 1)), not(equal(Car.CAR_ID, 2)))
                .withPrefabValues(LogicalQuery.class, not(equal(Car.CAR_ID, 1)), not(equal(Car.CAR_ID, 2)))
                .withCachedHashCode("cachedHashCode", "calcHashCode", null)
                .suppress(Warning.NULL_FIELDS, Warning.STRICT_INHERITANCE, Warning.NO_EXAMPLE_FOR_CACHED_HASHCODE)
                .verify();
    }

    @Test
    public void logicalQueriesSnapshotAndProtectTheirChildCollections() {
        Query<Car> first = equal(Car.CAR_ID, 1);
        Query<Car> second = equal(Car.CAR_ID, 2);
        List<Query<Car>> expected = Arrays.asList(first, second, first);
        List<Query<Car>> children = new ArrayList<Query<Car>>(expected);
        And<Car> query = new And<Car>(children);
        int originalHashCode = query.hashCode();

        children.clear();

        TestAssertions.assertEquals(3, query.size());
        TestAssertions.assertEquals(expected, query.getChildQueries());
        TestAssertions.assertEquals(expected, query.getSimpleQueries());
        TestAssertions.assertEquals(originalHashCode, query.hashCode());
        TestAssertions.assertThrows(UnsupportedOperationException.class, () -> query.getChildQueries().clear());
        TestAssertions.assertThrows(UnsupportedOperationException.class, () -> query.getSimpleQueries().clear());
        TestAssertions.assertThrows(UnsupportedOperationException.class, () -> query.getLogicalQueries().clear());
        TestAssertions.assertThrows(UnsupportedOperationException.class, () -> query.getComparativeQueries().clear());
    }

    @Test
    public void testExistsIn() {
        EqualsVerifier.forClass(ExistsIn.class)
                .withIgnoredFields("attributeIsSimple", "simpleAttribute", "attribute")
                .withCachedHashCode("cachedHashCode", "calcHashCode", null)
                .suppress(Warning.NULL_FIELDS, Warning.STRICT_INHERITANCE, Warning.NO_EXAMPLE_FOR_CACHED_HASHCODE)
                .verify();
    }

    /**
     * Query class {@link All} has a non-standard hashCode implementation.
     */
    @Test
    public void testAll() {
        Query<String> allStrings1 = QueryFactory.all(String.class);
        Query<String> allStrings2 = QueryFactory.all(String.class);
        Query<Integer> allIntegers1 = QueryFactory.all(Integer.class);
        Query<Integer> allIntegers2 = QueryFactory.all(Integer.class);

        TestAssertions.assertEquals(allStrings1, allStrings1);
        TestAssertions.assertEquals(allStrings1, allStrings2);
        TestAssertions.assertEquals(allIntegers1, allIntegers1);
        TestAssertions.assertEquals(allIntegers1, allIntegers2);

        TestAssertions.assertNotEquals(allStrings1, allIntegers1);

        // HashCode is a constant in All...
        TestAssertions.assertEquals(765906512, allStrings1.hashCode());
        TestAssertions.assertEquals(765906512, allStrings2.hashCode());
        TestAssertions.assertEquals(765906512, allIntegers1.hashCode());
        TestAssertions.assertEquals(765906512, allIntegers2.hashCode());
    }

    /**
     * Query class {@link None} has a non-standard hashCode implementation.
     */
    @Test
    public void testNone() {
        Query<String> noneStrings1 = QueryFactory.none(String.class);
        Query<String> noneStrings2 = QueryFactory.none(String.class);
        Query<Integer> noneIntegers1 = QueryFactory.none(Integer.class);
        Query<Integer> noneIntegers2 = QueryFactory.none(Integer.class);

        TestAssertions.assertEquals(noneStrings1, noneStrings1);
        TestAssertions.assertEquals(noneStrings1, noneStrings2);
        TestAssertions.assertEquals(noneIntegers1, noneIntegers1);
        TestAssertions.assertEquals(noneIntegers1, noneIntegers2);

        TestAssertions.assertNotEquals(noneStrings1, noneIntegers1);

        // HashCode is a constant in None...
        TestAssertions.assertEquals(1357656699, noneStrings1.hashCode());
        TestAssertions.assertEquals(1357656699, noneStrings2.hashCode());
        TestAssertions.assertEquals(1357656699, noneIntegers1.hashCode());
        TestAssertions.assertEquals(1357656699, noneIntegers2.hashCode());
    }
}
