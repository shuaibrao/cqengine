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
package com.googlecode.cqengine.resultset.filter;

import com.googlecode.cqengine.testutil.ExpectedException;

import com.googlecode.cqengine.query.option.QueryOptions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertFalse;
import static com.googlecode.cqengine.testutil.TestAssertions.assertNull;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;

public class FilteringIteratorTest {
    @Test
    public void testHasNextDoesNotAdvanceIterator(){
        List<String> testList = Arrays.asList("abc", "bcd", "cde");
        FilteringIterator<String> iterator = new FilteringIterator<String>(testList.iterator(), noQueryOptions()) {
            @Override
            public boolean isValid(String object, QueryOptions queryOptions) {
                return true;
            }
        };
        iterator.hasNext();
        iterator.hasNext();
        iterator.hasNext();
        assertEquals("abc", iterator.next());
    }

    @Test
    public void testNextPopulatedWithoutCallingHasNext(){
        List<String> testList = Arrays.asList("abc", "bcd", "cde");
        FilteringIterator<String> iterator = new FilteringIterator<String>(testList.iterator(), noQueryOptions()) {
            @Override
            public boolean isValid(String object, QueryOptions queryOptions) {
                return true;
            }
        };
        assertEquals("abc", iterator.next());
    }

    @Test
    public void testDelegatedIteratorHasNulls() {
        List<String> testList = Arrays.asList("abc", null, "cde");
        FilteringIterator<String> iterator = new FilteringIterator<String>(testList.iterator(), noQueryOptions()) {
            @Override
            public boolean isValid(String object, QueryOptions queryOptions) {
                return true;
            }
        };
        assertEquals("abc", iterator.next());
        assertNull(iterator.next());
        assertEquals("cde", iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void testFiltering() {
        List<String> testList = Arrays.asList("aaa", "bbb", "aab", "bba");
        FilteringIterator<String> iterator = new FilteringIterator<String>(testList.iterator(), noQueryOptions()) {
            @Override
            public boolean isValid(String object, QueryOptions queryOptions) {
                return object.startsWith("aa");
            }
        };
        assertEquals("aaa", iterator.next());
        assertEquals("aab", iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void testFilteringState() {
        List<String> testList = Arrays.asList("aaa", "bbb", "aab", "bba");
        FilteringIterator<String> iterator = new FilteringIterator<String>(testList.iterator(), noQueryOptions()) {
            @Override
            public boolean isValid(String object, QueryOptions queryOptions) {
                return false;
            }
        };

        assertFalse(iterator.hasNext());
        assertFalse(iterator.hasNext());
    }

    @Test
    public void testFilterNullValues() {
        List<String> testList = Arrays.asList("aaa", null, "aab", "bba");
        FilteringIterator<String> iterator = new FilteringIterator<String>(testList.iterator(), noQueryOptions()) {
            @Override
            public boolean isValid(String object, QueryOptions queryOptions) {
                return true;
            }
        };

        assertTrue(iterator.hasNext());
        assertEquals("first string value", "aaa", iterator.next());
        assertTrue(iterator.hasNext());
        assertNull("second null value", iterator.next());
        assertTrue(iterator.hasNext());
    }

    @Test
    @ExpectedException(NoSuchElementException.class)
    public void testEmptyDelegate() {
        List<String> testList = Arrays.asList();
        FilteringIterator<String> iterator = new FilteringIterator<String>(testList.iterator(), noQueryOptions()) {
            @Override
            public boolean isValid(String object, QueryOptions queryOptions) {
                return true;
            }
        };
        iterator.next();
    }
}
