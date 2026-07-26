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
import com.googlecode.cqengine.index.AttributeIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.CarFactory;
import org.junit.jupiter.api.Test;

import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author niall.gallagher
 */
public class SimplifiedSQLiteIndexTest {

    @Test
    @SuppressWarnings("unchecked")
    public void foreignKeyLookupClosesResultsOnSuccess() {
        AttributeIndex<Integer, Car> primaryKeyIndex = mock(AttributeIndex.class);
        ResultSet<Car> results = mock(ResultSet.class);
        QueryOptions queryOptions = new QueryOptions();
        Car expected = CarFactory.createCar(1);
        when(results.uniqueResult()).thenReturn(expected);
        when(primaryKeyIndex.retrieve(any(Query.class), same(queryOptions))).thenReturn(results);
        SimpleAttribute<Integer, Car> foreignKeyAttribute =
                SimplifiedSQLiteIndex.createForeignKeyAttribute(Car.CAR_ID, primaryKeyIndex);

        Car actual = foreignKeyAttribute.getValue(1, queryOptions);

        assertSame(expected, actual);
        verify(results, times(1)).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void foreignKeyLookupPreservesUniqueResultFailureWhenCloseAlsoFails() {
        AttributeIndex<Integer, Car> primaryKeyIndex = mock(AttributeIndex.class);
        ResultSet<Car> results = mock(ResultSet.class);
        QueryOptions queryOptions = new QueryOptions();
        RuntimeException lookupFailure = new RuntimeException("non-unique");
        RuntimeException closeFailure = new RuntimeException("close");
        when(results.uniqueResult()).thenThrow(lookupFailure);
        doThrow(closeFailure).when(results).close();
        when(primaryKeyIndex.retrieve(any(Query.class), same(queryOptions))).thenReturn(results);
        SimpleAttribute<Integer, Car> foreignKeyAttribute =
                SimplifiedSQLiteIndex.createForeignKeyAttribute(Car.CAR_ID, primaryKeyIndex);

        RuntimeException actual;
        try {
            foreignKeyAttribute.getValue(1, queryOptions);
            throw new AssertionError("Expected lookup to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertSame(lookupFailure, actual);
        assertArrayEquals(new Throwable[] {closeFailure}, actual.getSuppressed());
        verify(results, times(1)).close();
    }
}
