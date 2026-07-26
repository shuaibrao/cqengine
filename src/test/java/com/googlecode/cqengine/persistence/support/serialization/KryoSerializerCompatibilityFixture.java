// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public class KryoSerializerCompatibilityFixture {

    public List<String> arraysAsList;
    public Collection<String> unmodifiableCollection;
    public List<String> unmodifiableRandomAccessList;
    public List<String> unmodifiableList;
    public Set<String> unmodifiableSet;
    public SortedSet<String> unmodifiableSortedSet;
    public Map<String, Integer> unmodifiableMap;
    public SortedMap<String, Integer> unmodifiableSortedMap;
    public Collection<String> synchronizedCollection;
    public List<String> synchronizedRandomAccessList;
    public List<String> synchronizedList;
    public Set<String> synchronizedSet;
    public SortedSet<String> synchronizedSortedSet;
    public Map<String, Integer> synchronizedMap;
    public SortedMap<String, Integer> synchronizedSortedMap;

    public KryoSerializerCompatibilityFixture() {
    }

    static KryoSerializerCompatibilityFixture create() {
        KryoSerializerCompatibilityFixture fixture = new KryoSerializerCompatibilityFixture();
        fixture.arraysAsList = Arrays.asList(new String[]{"arrays-a", "arrays-b"});
        fixture.unmodifiableCollection = Collections.unmodifiableCollection(
                new ArrayList<String>(Arrays.asList("uc-a", "uc-b")));
        fixture.unmodifiableRandomAccessList = Collections.unmodifiableList(
                new ArrayList<String>(Arrays.asList("ur-a", "ur-b")));
        fixture.unmodifiableList = Collections.unmodifiableList(
                new LinkedList<String>(Arrays.asList("ul-a", "ul-b")));
        fixture.unmodifiableSet = Collections.unmodifiableSet(
                new LinkedHashSet<String>(Arrays.asList("us-a", "us-b")));
        fixture.unmodifiableSortedSet = Collections.unmodifiableSortedSet(
                new TreeSet<String>(Arrays.asList("uss-a", "uss-b")));
        fixture.unmodifiableMap = Collections.unmodifiableMap(linkedMap("um-a", 1, "um-b", 2));
        fixture.unmodifiableSortedMap = Collections.unmodifiableSortedMap(
                new TreeMap<String, Integer>(linkedMap("usm-a", 1, "usm-b", 2)));
        fixture.synchronizedCollection = Collections.synchronizedCollection(
                new ArrayList<String>(Arrays.asList("sc-a", "sc-b")));
        fixture.synchronizedRandomAccessList = Collections.synchronizedList(
                new ArrayList<String>(Arrays.asList("sr-a", "sr-b")));
        fixture.synchronizedList = Collections.synchronizedList(
                new LinkedList<String>(Arrays.asList("sl-a", "sl-b")));
        fixture.synchronizedSet = Collections.synchronizedSet(
                new LinkedHashSet<String>(Arrays.asList("ss-a", "ss-b")));
        fixture.synchronizedSortedSet = Collections.synchronizedSortedSet(
                new TreeSet<String>(Arrays.asList("sss-a", "sss-b")));
        fixture.synchronizedMap = Collections.synchronizedMap(linkedMap("sm-a", 1, "sm-b", 2));
        fixture.synchronizedSortedMap = Collections.synchronizedSortedMap(
                new TreeMap<String, Integer>(linkedMap("ssm-a", 1, "ssm-b", 2)));
        return fixture;
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
