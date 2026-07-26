/**
 * Copyright 2012-2015 Niall Gallagher
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
package com.googlecode.cqengine;

import com.googlecode.cqengine.persistence.offheap.OffHeapPersistence;
import com.googlecode.cqengine.persistence.onheap.OnHeapPersistence;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.testutil.MutableSetContract;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ObjectLockingIndexedCollection}. Note that tests for support behavior (such as query processing)
 * which applies to all implementations of {@link IndexedCollection} can be found in
 * {@link com.googlecode.cqengine.IndexedCollectionFunctionalTest}.
 * <p/>
 * The Jupiter behavior matrix in this class validates the mutable {@link Set} contract for on-heap and off-heap
 * collections.
 *
 * @author Niall Gallagher
 */
public class ObjectLockingIndexedCollectionTest {

    @TestFactory
    Stream<DynamicTest> mutableSetContract() {
        return Stream.concat(
                MutableSetContract.tests("on-heap ObjectLockingIndexedCollection", ObjectLockingIndexedCollectionTest::newOnHeapCollection),
                MutableSetContract.tests("off-heap ObjectLockingIndexedCollection", ObjectLockingIndexedCollectionTest::newOffHeapCollection));
    }

    private static Set<String> newOnHeapCollection() {
        return new ObjectLockingIndexedCollection<String>(
                OnHeapPersistence.onPrimaryKey(QueryFactory.selfAttribute(String.class)));
    }

    private static Set<String> newOffHeapCollection() {
        return new ObjectLockingIndexedCollection<String>(
                OffHeapPersistence.onPrimaryKey(QueryFactory.selfAttribute(String.class)));
    }

    @Test
    public void testConstructor() {
        ObjectLockingIndexedCollection<Integer> collection1 = new ObjectLockingIndexedCollection<Integer>();
        ObjectLockingIndexedCollection<Integer> collection2 = new ObjectLockingIndexedCollection<Integer>(new OnHeapPersistence<Integer, Integer>());
        ObjectLockingIndexedCollection<Integer> collection3 = new ObjectLockingIndexedCollection<Integer>(64);

        assertEquals(64, collection1.stripedLock.concurrencyLevel);
        assertEquals(64, collection2.stripedLock.concurrencyLevel);
        assertEquals(64, collection3.stripedLock.concurrencyLevel);
    }
}
