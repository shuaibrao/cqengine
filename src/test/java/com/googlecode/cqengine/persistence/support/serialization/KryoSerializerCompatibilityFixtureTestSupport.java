// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertThrows;

final class KryoSerializerCompatibilityFixtureTestSupport {

    private KryoSerializerCompatibilityFixtureTestSupport() {
    }

    static void assertFixture(KryoSerializerCompatibilityFixture fixture) {
        assertEquals(Arrays.asList("arrays-a", "arrays-b"), fixture.arraysAsList);
        assertEquals(Arrays.asList("uc-a", "uc-b"), new ArrayList<String>(fixture.unmodifiableCollection));
        assertEquals(Arrays.asList("ur-a", "ur-b"), fixture.unmodifiableRandomAccessList);
        assertEquals(Arrays.asList("ul-a", "ul-b"), fixture.unmodifiableList);
        assertEquals(new LinkedHashSet<String>(Arrays.asList("us-a", "us-b")), fixture.unmodifiableSet);
        assertEquals(new TreeSet<String>(Arrays.asList("uss-a", "uss-b")), fixture.unmodifiableSortedSet);
        assertEquals(linkedMap("um-a", 1, "um-b", 2), fixture.unmodifiableMap);
        assertEquals(new TreeMap<String, Integer>(linkedMap("usm-a", 1, "usm-b", 2)), fixture.unmodifiableSortedMap);
        assertEquals(Arrays.asList("sc-a", "sc-b"), new ArrayList<String>(fixture.synchronizedCollection));
        assertEquals(Arrays.asList("sr-a", "sr-b"), fixture.synchronizedRandomAccessList);
        assertEquals(Arrays.asList("sl-a", "sl-b"), fixture.synchronizedList);
        assertEquals(new LinkedHashSet<String>(Arrays.asList("ss-a", "ss-b")), fixture.synchronizedSet);
        assertEquals(new TreeSet<String>(Arrays.asList("sss-a", "sss-b")), fixture.synchronizedSortedSet);
        assertEquals(linkedMap("sm-a", 1, "sm-b", 2), fixture.synchronizedMap);
        assertEquals(new TreeMap<String, Integer>(linkedMap("ssm-a", 1, "ssm-b", 2)), fixture.synchronizedSortedMap);

        assertEquals(Collections.unmodifiableCollection(new ArrayList<Object>()).getClass(), fixture.unmodifiableCollection.getClass());
        assertEquals(Collections.unmodifiableList(new ArrayList<Object>()).getClass(), fixture.unmodifiableRandomAccessList.getClass());
        assertEquals(Collections.unmodifiableList(new LinkedList<Object>()).getClass(), fixture.unmodifiableList.getClass());
        assertEquals(Collections.unmodifiableSet(new LinkedHashSet<Object>()).getClass(), fixture.unmodifiableSet.getClass());
        assertEquals(Collections.unmodifiableSortedSet(new TreeSet<Object>()).getClass(), fixture.unmodifiableSortedSet.getClass());
        assertEquals(Collections.unmodifiableMap(new LinkedHashMap<Object, Object>()).getClass(), fixture.unmodifiableMap.getClass());
        assertEquals(Collections.unmodifiableSortedMap(new TreeMap<Object, Object>()).getClass(), fixture.unmodifiableSortedMap.getClass());
        assertEquals(Collections.synchronizedCollection(new ArrayList<Object>()).getClass(), fixture.synchronizedCollection.getClass());
        assertEquals(Collections.synchronizedList(new ArrayList<Object>()).getClass(), fixture.synchronizedRandomAccessList.getClass());
        assertEquals(Collections.synchronizedList(new LinkedList<Object>()).getClass(), fixture.synchronizedList.getClass());
        assertEquals(Collections.synchronizedSet(new LinkedHashSet<Object>()).getClass(), fixture.synchronizedSet.getClass());
        assertEquals(Collections.synchronizedSortedSet(new TreeSet<Object>()).getClass(), fixture.synchronizedSortedSet.getClass());
        assertEquals(Collections.synchronizedMap(new LinkedHashMap<Object, Object>()).getClass(), fixture.synchronizedMap.getClass());
        assertEquals(Collections.synchronizedSortedMap(new TreeMap<Object, Object>()).getClass(), fixture.synchronizedSortedMap.getClass());

        assertThrows(UnsupportedOperationException.class, () -> fixture.arraysAsList.add("arrays-c"));
        assertThrows(UnsupportedOperationException.class, () -> fixture.unmodifiableCollection.add("uc-c"));
        assertThrows(UnsupportedOperationException.class, () -> fixture.unmodifiableRandomAccessList.add("ur-c"));
        assertThrows(UnsupportedOperationException.class, () -> fixture.unmodifiableList.add("ul-c"));
        assertThrows(UnsupportedOperationException.class, () -> fixture.unmodifiableSet.add("us-c"));
        assertThrows(UnsupportedOperationException.class, () -> fixture.unmodifiableSortedSet.add("uss-c"));
        assertThrows(UnsupportedOperationException.class, () -> fixture.unmodifiableMap.put("um-c", 3));
        assertThrows(UnsupportedOperationException.class, () -> fixture.unmodifiableSortedMap.put("usm-c", 3));
    }

    private static LinkedHashMap<String, Integer> linkedMap(
            String firstKey,
            int firstValue,
            String secondKey,
            int secondValue) {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
        map.put(firstKey, firstValue);
        map.put(secondKey, secondValue);
        return map;
    }
}
