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
package com.googlecode.cqengine.index.support;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedSet;
import java.util.Spliterator;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link UnmodifiableNavigableSet}.
 *
 * @author Niall Gallagher
 */
public class UnmodifiableNavigableSetTest {

    @TestFactory
    Stream<DynamicTest> navigableSetContract() {
        return Stream.of(
                DynamicTest.dynamicTest("empty set behavior", () -> {
                    NavigableSet<String> set = setOf();
                    assertTrue(set.isEmpty());
                    assertEquals(0, set.size());
                    assertFalse(set.iterator().hasNext());
                    assertNull(set.lower("b"));
                    assertNull(set.floor("b"));
                    assertNull(set.ceiling("b"));
                    assertNull(set.higher("b"));
                    assertThrows(NoSuchElementException.class, set::first);
                    assertThrows(NoSuchElementException.class, set::last);
                    assertThrows(NoSuchElementException.class, set::getFirst);
                    assertThrows(NoSuchElementException.class, set::getLast);
                }),
                DynamicTest.dynamicTest("singleton navigation", () -> {
                    NavigableSet<String> set = setOf("c");
                    assertEquals(1, set.size());
                    assertEquals("c", set.first());
                    assertEquals("c", set.last());
                    assertNull(set.lower("c"));
                    assertEquals("c", set.floor("c"));
                    assertEquals("c", set.ceiling("c"));
                    assertNull(set.higher("c"));
                    assertEquals("c", set.lower("d"));
                    assertEquals("c", set.floor("d"));
                    assertEquals("c", set.ceiling("b"));
                    assertEquals("c", set.higher("b"));
                }),
                DynamicTest.dynamicTest("several elements retain known order and boundaries", () -> {
                    NavigableSet<String> set = setOf("d", "b", "a", "c");
                    assertEquals(Arrays.asList("a", "b", "c", "d"), new ArrayList<String>(set));
                    assertEquals("a", set.first());
                    assertEquals("d", set.last());
                    assertEquals("a", set.getFirst());
                    assertEquals("d", set.getLast());
                }),
                DynamicTest.dynamicTest("navigation covers exact holes and outside bounds", () -> {
                    NavigableSet<String> set = setOf("a", "c", "e");
                    assertNull(set.lower("a"));
                    assertEquals("a", set.floor("a"));
                    assertEquals("a", set.ceiling("a"));
                    assertEquals("c", set.higher("a"));
                    assertEquals("a", set.lower("b"));
                    assertEquals("a", set.floor("b"));
                    assertEquals("c", set.ceiling("b"));
                    assertEquals("c", set.higher("b"));
                    assertEquals("e", set.lower("z"));
                    assertEquals("e", set.floor("z"));
                    assertNull(set.ceiling("z"));
                    assertNull(set.higher("z"));
                }),
                DynamicTest.dynamicTest("descending and reversed views invert order and navigation", () -> {
                    NavigableSet<String> set = setOf("a", "c", "e");
                    NavigableSet<String> descending = set.descendingSet();
                    assertSame(descending, set.descendingSet());
                    assertSame(descending, set.reversed());
                    assertSame(set, descending.descendingSet());
                    assertSame(set, descending.reversed());
                    assertEquals(Arrays.asList("e", "c", "a"), new ArrayList<String>(descending));
                    assertEquals("e", descending.lower("c"));
                    assertEquals("c", descending.floor("c"));
                    assertEquals("c", descending.ceiling("c"));
                    assertEquals("a", descending.higher("c"));
                }),
                DynamicTest.dynamicTest("custom comparator is preserved by root and views", () -> {
                    Comparator<String> reverseOrder = Comparator.reverseOrder();
                    NavigableSet<String> set = setOf(reverseOrder, "a", "b", "c", "d");
                    assertSame(reverseOrder, set.comparator());
                    assertEquals(Arrays.asList("d", "c", "b", "a"), new ArrayList<String>(set));
                    assertEquals("d", set.first());
                    assertEquals("a", set.last());
                    assertEquals(Arrays.asList("d", "c", "b"),
                            new ArrayList<String>(set.subSet("d", true, "b", true)));
                    assertEquals(Arrays.asList("a", "b", "c", "d"),
                            new ArrayList<String>(set.descendingSet()));
                }),
                DynamicTest.dynamicTest("range views honor every inclusive and exclusive bound", () -> {
                    NavigableSet<String> set = setOf("a", "b", "c", "d");
                    assertEquals(Arrays.asList("b", "c"), list(set.subSet("b", true, "d", false)));
                    assertEquals(Arrays.asList("c", "d"), list(set.subSet("b", false, "d", true)));
                    assertEquals(Collections.singletonList("b"), list(set.subSet("b", true, "b", true)));
                    assertTrue(set.subSet("b", false, "b", false).isEmpty());
                    assertEquals(Collections.singletonList("a"), list(set.headSet("b", false)));
                    assertEquals(Arrays.asList("a", "b"), list(set.headSet("b", true)));
                    assertEquals(Collections.singletonList("d"), list(set.tailSet("c", false)));
                    assertEquals(Arrays.asList("c", "d"), list(set.tailSet("c", true)));
                }),
                DynamicTest.dynamicTest("SortedSet range methods retain legacy endpoint semantics", () -> {
                    NavigableSet<String> set = setOf("a", "b", "c", "d");
                    SortedSet<String> subSet = set.subSet("b", "d");
                    assertEquals(Arrays.asList("b", "c"), list(subSet));
                    assertEquals(Collections.singletonList("a"), list(set.headSet("b")));
                    assertEquals(Arrays.asList("c", "d"), list(set.tailSet("c")));
                    assertTrue(subSet.subSet("c", "c").isEmpty());
                }),
                DynamicTest.dynamicTest("invalid and out-of-range view bounds are rejected", () -> {
                    NavigableSet<String> set = setOf("a", "b", "c", "d");
                    assertThrows(IllegalArgumentException.class, () -> set.subSet("d", true, "b", true));
                    NavigableSet<String> middle = set.subSet("b", true, "d", false);
                    assertThrows(IllegalArgumentException.class, () -> middle.headSet("a", true));
                    assertThrows(IllegalArgumentException.class, () -> middle.tailSet("e", true));
                    assertThrows(IllegalArgumentException.class, () -> middle.subSet("a", true, "c", true));
                }),
                DynamicTest.dynamicTest("collection queries and arrays preserve known order", () -> {
                    NavigableSet<String> set = setOf("d", "b", "a", "c");
                    assertTrue(set.contains("b"));
                    assertFalse(set.contains("missing"));
                    assertTrue(set.containsAll(Arrays.asList("a", "c")));
                    assertFalse(set.containsAll(Arrays.asList("a", "missing")));
                    assertArrayEquals(new Object[] {"a", "b", "c", "d"}, set.toArray());
                    assertArrayEquals(new String[] {"a", "b", "c", "d"}, set.toArray(new String[0]));
                    assertArrayEquals(new String[] {"a", "b", "c", "d"}, set.toArray(String[]::new));
                    assertEquals("[a, b, c, d]", set.toString());
                }),
                DynamicTest.dynamicTest("typed arrays cover exact oversized empty and incompatible targets", () -> {
                    NavigableSet<String> set = setOf("a", "b", "c");
                    String[] exact = new String[3];
                    assertSame(exact, set.toArray(exact));
                    assertArrayEquals(new String[] {"a", "b", "c"}, exact);

                    String[] oversized = new String[] {"stale", "stale", "stale", "sentinel", "untouched"};
                    assertSame(oversized, set.toArray(oversized));
                    assertArrayEquals(new String[] {"a", "b", "c", null, "untouched"}, oversized);
                    assertThrows(ArrayStoreException.class, () -> set.toArray(new Integer[0]));

                    NavigableSet<String> empty = setOf();
                    Integer[] incompatibleButEmpty = new Integer[0];
                    assertSame(incompatibleButEmpty, empty.toArray(incompatibleButEmpty));
                }),
                DynamicTest.dynamicTest("equality and hash code cover Set partitions", () -> {
                    NavigableSet<String> set = setOf("a", "b", "c");
                    Set<String> expected = new HashSet<String>(Arrays.asList("a", "b", "c"));
                    assertTrue(set.equals(set));
                    assertEquals(expected, set);
                    assertEquals(set, expected);
                    assertEquals(expected.hashCode(), set.hashCode());
                    assertFalse(set.equals(null));
                    assertFalse(set.equals(Arrays.asList("a", "b", "c")));
                    assertFalse(set.equals(new HashSet<String>(Arrays.asList("a", "b"))));
                    assertFalse(set.equals(new HashSet<String>(Arrays.asList("a", "b", "c", "d"))));
                    assertFalse(set.equals(new HashSet<String>(Arrays.asList("a", "b", null))));
                }),
                DynamicTest.dynamicTest("null and wrong-type queries preserve delegate policy", () -> {
                    NavigableSet<String> set = setOf("a", "b", "c");
                    assertThrows(NullPointerException.class, () -> set.contains(null));
                    assertThrows(NullPointerException.class, () -> set.containsAll(Collections.singleton(null)));
                    assertThrows(ClassCastException.class, () -> set.contains(1));
                    assertThrows(ClassCastException.class, () -> set.containsAll(Collections.singleton(1)));
                    assertThrows(NullPointerException.class, () -> set.lower(null));
                }),
                DynamicTest.dynamicTest("iterators are ordered exhausted and unmodifiable", () -> {
                    NavigableSet<String> set = setOf("a", "b", "c");
                    Iterator<String> ascending = set.iterator();
                    assertEquals("a", ascending.next());
                    assertThrows(UnsupportedOperationException.class, ascending::remove);
                    assertEquals("b", ascending.next());
                    assertEquals("c", ascending.next());
                    assertFalse(ascending.hasNext());
                    assertThrows(NoSuchElementException.class, ascending::next);

                    Iterator<String> descending = set.descendingIterator();
                    assertEquals("c", descending.next());
                    assertThrows(UnsupportedOperationException.class, descending::remove);
                    assertEquals("b", descending.next());
                    assertEquals("a", descending.next());
                    assertFalse(descending.hasNext());
                    assertThrows(NoSuchElementException.class, descending::next);
                }),
                DynamicTest.dynamicTest("forEach streams and spliterator retain sorted encounter order", () -> {
                    NavigableSet<String> set = setOf("d", "b", "a", "c");
                    List<String> visited = new ArrayList<String>();
                    set.forEach(visited::add);
                    assertEquals(Arrays.asList("a", "b", "c", "d"), visited);
                    assertEquals(Arrays.asList("a", "b", "c", "d"), set.stream().toList());
                    assertEquals(Arrays.asList("a", "b", "c", "d"), set.parallelStream().toList());
                    Spliterator<String> spliterator = set.spliterator();
                    assertTrue(spliterator.hasCharacteristics(Spliterator.DISTINCT));
                    assertTrue(spliterator.hasCharacteristics(Spliterator.ORDERED));
                    assertTrue(spliterator.hasCharacteristics(Spliterator.SORTED));
                    assertNull(spliterator.getComparator());
                }),
                DynamicTest.dynamicTest("root view reflects backing-set changes", () -> {
                    TreeSet<String> backing = new TreeSet<String>(Arrays.asList("a", "c"));
                    NavigableSet<String> set = new UnmodifiableNavigableSet<String>(backing);
                    backing.add("b");
                    assertEquals(Arrays.asList("a", "b", "c"), list(set));
                    backing.remove("a");
                    assertEquals(Arrays.asList("b", "c"), list(set));
                }),
                DynamicTest.dynamicTest("derived and descending views remain live", () -> {
                    TreeSet<String> backing = new TreeSet<String>(Arrays.asList("b", "d", "f"));
                    NavigableSet<String> set = new UnmodifiableNavigableSet<String>(backing);
                    NavigableSet<String> middle = set.subSet("b", true, "f", false);
                    NavigableSet<String> descending = set.descendingSet();
                    backing.add("c");
                    backing.add("e");
                    assertEquals(Arrays.asList("b", "c", "d", "e"), list(middle));
                    assertEquals(Arrays.asList("f", "e", "d", "c", "b"), list(descending));
                    backing.remove("d");
                    assertFalse(middle.contains("d"));
                    assertFalse(descending.contains("d"));
                }),
                DynamicTest.dynamicTest("root rejects every mutation path", () ->
                        assertUnmodifiable(integerSet())),
                DynamicTest.dynamicTest("descending view rejects every mutation path", () ->
                        assertUnmodifiable(integerSet().descendingSet())),
                DynamicTest.dynamicTest("head view rejects every mutation path", () ->
                        assertUnmodifiable(integerSet().headSet(3, true))),
                DynamicTest.dynamicTest("tail view rejects every mutation path", () ->
                        assertUnmodifiable(integerSet().tailSet(2, true))),
                DynamicTest.dynamicTest("subset view rejects every mutation path", () ->
                        assertUnmodifiable(integerSet().subSet(1, true, 3, true))),
                DynamicTest.dynamicTest("legacy SortedSet views reject every mutation path", () -> {
                    assertUnmodifiable((NavigableSet<Integer>) integerSet().headSet(3));
                    assertUnmodifiable((NavigableSet<Integer>) integerSet().tailSet(2));
                    assertUnmodifiable((NavigableSet<Integer>) integerSet().subSet(1, 3));
                }),
                DynamicTest.dynamicTest("empty root rejects direct and no-op mutations", () ->
                        assertEmptyUnmodifiable(new UnmodifiableNavigableSet<Integer>(new TreeSet<Integer>()))),
                DynamicTest.dynamicTest("empty derived and descending views reject mutation", () -> {
                    assertEmptyUnmodifiable(integerSet().subSet(2, false, 2, false));
                    assertEmptyUnmodifiable(
                            new UnmodifiableNavigableSet<Integer>(new TreeSet<Integer>()).descendingSet());
                }),
                DynamicTest.dynamicTest("Java 21 sequenced access maps to navigable boundaries", () -> {
                    NavigableSet<Integer> set = integerSet();
                    assertEquals(1, set.getFirst());
                    assertEquals(3, set.getLast());
                    assertSame(set.descendingSet(), set.reversed());
                    assertEquals(Arrays.asList(3, 2, 1), list(set.reversed()));
                }));
    }

    private static NavigableSet<String> setOf(String... values) {
        return new UnmodifiableNavigableSet<String>(new TreeSet<String>(Arrays.asList(values)));
    }

    private static NavigableSet<String> setOf(Comparator<String> comparator, String... values) {
        TreeSet<String> set = new TreeSet<String>(comparator);
        set.addAll(Arrays.asList(values));
        return new UnmodifiableNavigableSet<String>(set);
    }

    private static NavigableSet<Integer> integerSet() {
        return new UnmodifiableNavigableSet<Integer>(new TreeSet<Integer>(Arrays.asList(1, 2, 3)));
    }

    private static <E> List<E> list(Iterable<E> values) {
        List<E> result = new ArrayList<E>();
        for (E value : values) {
            result.add(value);
        }
        return result;
    }

    private static void assertUnmodifiable(NavigableSet<Integer> set) {
        assertUnsupportedWithoutChange(set, () -> set.add(4));
        assertUnsupportedWithoutChange(set, () -> set.add(set.first()));
        assertUnsupportedWithoutChange(set, () -> set.addAll(Collections.singleton(4)));
        assertUnsupportedWithoutChange(set, () -> set.addAll(Collections.emptySet()));
        assertUnsupportedWithoutChange(set, () -> set.addFirst(4));
        assertUnsupportedWithoutChange(set, () -> set.addLast(4));
        assertUnsupportedWithoutChange(set, () -> set.remove(set.first()));
        assertUnsupportedWithoutChange(set, () -> set.remove(99));
        assertUnsupportedWithoutChange(set, () -> set.removeAll(Collections.singleton(set.first())));
        assertUnsupportedWithoutChange(set, () -> set.removeAll(Collections.emptySet()));
        assertUnsupportedWithoutChange(set, () -> set.retainAll(new HashSet<Integer>(set)));
        assertUnsupportedWithoutChange(set, () -> set.retainAll(Collections.emptySet()));
        assertUnsupportedWithoutChange(set, set::clear);
        assertUnsupportedWithoutChange(set, set::pollFirst);
        assertUnsupportedWithoutChange(set, set::pollLast);
        assertUnsupportedWithoutChange(set, set::removeFirst);
        assertUnsupportedWithoutChange(set, set::removeLast);
        assertUnsupportedWithoutChange(set, () -> {
            Iterator<Integer> iterator = set.iterator();
            iterator.next();
            iterator.remove();
        });
        assertUnsupportedWithoutChange(set, () -> {
            Iterator<Integer> iterator = set.descendingIterator();
            iterator.next();
            iterator.remove();
        });
        assertUnsupportedWithoutChange(set, () -> set.removeIf(value -> true));
        Set<Integer> before = new HashSet<Integer>(set);
        assertFalse(set.removeIf(value -> false));
        assertEquals(before, set);
        assertThrows(NullPointerException.class, () -> set.removeIf(null));
        assertEquals(before, set);
    }

    private static void assertEmptyUnmodifiable(NavigableSet<Integer> set) {
        assertUnsupportedWithoutChange(set, () -> set.add(1));
        assertUnsupportedWithoutChange(set, () -> set.addAll(Collections.singleton(1)));
        assertUnsupportedWithoutChange(set, () -> set.addAll(Collections.emptySet()));
        assertUnsupportedWithoutChange(set, () -> set.addFirst(1));
        assertUnsupportedWithoutChange(set, () -> set.addLast(1));
        assertUnsupportedWithoutChange(set, () -> set.remove(1));
        assertUnsupportedWithoutChange(set, () -> set.removeAll(Collections.emptySet()));
        assertUnsupportedWithoutChange(set, () -> set.retainAll(Collections.emptySet()));
        assertUnsupportedWithoutChange(set, set::clear);
        assertUnsupportedWithoutChange(set, set::pollFirst);
        assertUnsupportedWithoutChange(set, set::pollLast);
        assertUnsupportedWithoutChange(set, () -> set.iterator().remove());
        assertUnsupportedWithoutChange(set, () -> set.descendingIterator().remove());
        assertFalse(set.removeIf(value -> true));
        assertFalse(set.removeIf(value -> false));
        assertThrows(NoSuchElementException.class, set::removeFirst);
        assertThrows(NoSuchElementException.class, set::removeLast);
        assertTrue(set.isEmpty());
    }

    private static void assertUnsupportedWithoutChange(NavigableSet<Integer> set, Executable mutation) {
        Set<Integer> before = new HashSet<Integer>(set);
        assertThrows(UnsupportedOperationException.class, mutation);
        assertEquals(before, set);
    }
}
