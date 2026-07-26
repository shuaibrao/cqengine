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
package com.googlecode.cqengine.index.sqlite;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.sqlite.TemporaryDatabase.TemporaryInMemoryDatabase;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.CarFactory;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.googlecode.cqengine.query.QueryFactory.*;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SQLiteIdentityIndexTest {

    @RegisterExtension
    public TemporaryInMemoryDatabase temporaryDatabase = new TemporaryInMemoryDatabase();

    @Test
    public void testSerialization() {
        SQLiteIdentityIndex<Integer, Car> index = new SQLiteIdentityIndex<Integer, Car>(
                Car.CAR_ID
        );

        SimpleAttribute<Car, byte[]> serializingAttribute = index.new SerializingAttribute(Car.class, byte[].class);
        SimpleAttribute<byte[], Car> deserializingAttribute = index.new DeserializingAttribute(byte[].class, Car.class);

        Car c1 = CarFactory.createCar(1);
        byte[] s1 = serializingAttribute.getValue(c1, noQueryOptions());
        Car c2 = deserializingAttribute.getValue(s1, noQueryOptions());
        byte[] s2 = serializingAttribute.getValue(c2, noQueryOptions());
        TestAssertions.assertEquals(c1, c2);
        TestAssertions.assertArrayEquals(s1, s2);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void foreignKeyLookupUsesCallerOptionsAndClosesResults() {
        ResultSet<Car> results = mock(ResultSet.class);
        QueryOptions queryOptions = new QueryOptions();
        AtomicInteger retrieveCalls = new AtomicInteger();
        SQLiteIdentityIndex<Integer, Car> index = new SQLiteIdentityIndex<Integer, Car>(Car.CAR_ID) {
            @Override
            public ResultSet<Car> retrieve(Query<Car> query, QueryOptions actualQueryOptions) {
                assertSame(queryOptions, actualQueryOptions);
                retrieveCalls.incrementAndGet();
                return results;
            }
        };
        Car expected = CarFactory.createCar(1);
        when(results.uniqueResult()).thenReturn(expected);

        Car actual = index.getForeignKeyAttribute().getValue(1, queryOptions);

        assertSame(expected, actual);
        assertEquals(1, retrieveCalls.get());
        verify(results, times(1)).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void foreignKeyLookupClosesResultsWhenUniqueResultFails() {
        ResultSet<Car> results = mock(ResultSet.class);
        QueryOptions queryOptions = new QueryOptions();
        SQLiteIdentityIndex<Integer, Car> index = new SQLiteIdentityIndex<Integer, Car>(Car.CAR_ID) {
            @Override
            public ResultSet<Car> retrieve(Query<Car> query, QueryOptions actualQueryOptions) {
                assertSame(queryOptions, actualQueryOptions);
                return results;
            }
        };
        RuntimeException lookupFailure = new RuntimeException("non-unique");
        when(results.uniqueResult()).thenThrow(lookupFailure);

        RuntimeException actual;
        try {
            index.getForeignKeyAttribute().getValue(1, queryOptions);
            throw new AssertionError("Expected lookup to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(lookupFailure, actual);
        verify(results, times(1)).close();
    }

}
