// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.testutil;

import org.junit.jupiter.api.DynamicTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class MutableSetContract {

    private MutableSetContract() {
    }

    public static Stream<DynamicTest> tests(String implementation, Supplier<? extends Set<String>> factory) {
        return Stream.of(
                test(implementation, "new set is empty", factory, set -> {
                    assertTrue(set.isEmpty());
                    assertEquals(0, set.size());
                    assertTrue(set.containsAll(Collections.emptySet()));
                    assertFalse(set.addAll(Collections.emptySet()));
                    assertFalse(set.removeAll(Collections.emptySet()));
                    assertFalse(set.retainAll(Collections.emptySet()));
                }),
                test(implementation, "size and emptiness track singleton transitions", factory, set -> {
                    assertTrue(set.add("a"));
                    assertEquals(1, set.size());
                    assertFalse(set.isEmpty());
                    assertTrue(set.remove("a"));
                    assertEquals(0, set.size());
                    assertTrue(set.isEmpty());
                }),
                test(implementation, "add reports structural changes", factory, set -> {
                    assertTrue(set.add("a"));
                    assertFalse(set.add("a"));
                    assertEquals(Collections.singleton("a"), set);
                }),
                test(implementation, "addAll preserves set semantics", factory, set -> {
                    assertTrue(set.addAll(Arrays.asList("a", "b", "a")));
                    assertFalse(set.addAll(Arrays.asList("a", "b")));
                    assertEquals(setOf("a", "b"), set);
                }),
                test(implementation, "contains and containsAll", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertTrue(set.contains("b"));
                    assertFalse(set.contains("missing"));
                    assertTrue(set.containsAll(Collections.emptySet()));
                    assertTrue(set.containsAll(set));
                    assertTrue(set.containsAll(Arrays.asList("a", "c")));
                    assertFalse(set.containsAll(Arrays.asList("a", "missing")));
                }),
                test(implementation, "null queries are absent or rejected", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertFalseOrThrowsNullPointer(() -> set.contains(null));
                    assertFalseOrThrowsNullPointer(() -> set.containsAll(Collections.singleton(null)));
                    assertFalseOrThrowsNullPointer(() -> set.remove(null));
                    assertFalseOrThrowsNullPointer(() -> set.removeAll(Collections.singleton(null)));
                    assertEquals(setOf("a", "b"), set);
                }),
                test(implementation, "wrong-type queries are absent or rejected", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertFalseOrThrowsClassCast(() -> set.contains(1));
                    assertFalseOrThrowsClassCast(() -> set.containsAll(Collections.singleton(1)));
                    assertFalseOrThrowsClassCast(() -> set.remove(1));
                    assertFalseOrThrowsClassCast(() -> set.removeAll(Collections.singleton(1)));
                    assertEquals(setOf("a", "b"), set);
                }),
                test(implementation, "null values are rejected without changing membership", factory, set -> {
                    set.add("a");
                    assertThrows(NullPointerException.class, () -> set.add(null));
                    assertThrows(NullPointerException.class, () -> set.addAll(Collections.singleton(null)));
                    assertEquals(Collections.singleton("a"), set);
                }),
                test(implementation, "null collection arguments are rejected", factory, set -> {
                    set.add("a");
                    assertThrows(NullPointerException.class, () -> set.addAll(null));
                    assertThrows(NullPointerException.class, () -> set.containsAll(null));
                    assertThrows(NullPointerException.class, () -> set.removeAll(null));
                    assertThrows(NullPointerException.class, () -> set.retainAll(null));
                    assertEquals(Collections.singleton("a"), set);
                }),
                test(implementation, "remove reports structural changes", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertTrue(set.remove("a"));
                    assertFalse(set.remove("a"));
                    assertEquals(Collections.singleton("b"), set);
                }),
                test(implementation, "removeAll removes the intersection", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertTrue(set.removeAll(Arrays.asList("b", "missing")));
                    assertFalse(set.removeAll(Collections.singleton("missing")));
                    assertEquals(setOf("a", "c"), set);
                }),
                test(implementation, "removeAll covers empty disjoint partial and complete intersections", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertFalse(set.removeAll(Collections.emptySet()));
                    assertFalse(set.removeAll(Arrays.asList("missing", "missing")));
                    assertTrue(set.removeAll(Arrays.asList("a", "a", "missing")));
                    assertEquals(setOf("b", "c"), set);
                    assertTrue(set.removeAll(Arrays.asList("b", "c", "extra")));
                    assertTrue(set.isEmpty());
                }),
                test(implementation, "retainAll keeps the intersection", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertTrue(set.retainAll(Arrays.asList("b", "c", "missing")));
                    assertFalse(set.retainAll(Arrays.asList("b", "c")));
                    assertEquals(setOf("b", "c"), set);
                }),
                test(implementation, "retainAll covers empty disjoint superset and duplicate inputs", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertFalse(set.retainAll(Arrays.asList("a", "a", "b", "c", "extra")));
                    assertTrue(set.retainAll(Arrays.asList("a", "a", "c")));
                    assertEquals(setOf("a", "c"), set);
                    assertTrue(set.retainAll(Collections.singleton("missing")));
                    assertTrue(set.isEmpty());
                    assertFalse(set.retainAll(Collections.emptySet()));
                }),
                test(implementation, "retainAll treats null and wrong types as non-members", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertTrue(set.retainAll(Collections.singleton(null)));
                    assertTrue(set.isEmpty());
                    set.addAll(Arrays.asList("a", "b"));
                    assertTrue(set.retainAll(Collections.singleton(1)));
                    assertTrue(set.isEmpty());
                }),
                test(implementation, "clear is idempotent", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    set.clear();
                    set.clear();
                    assertTrue(set.isEmpty());
                }),
                test(implementation, "iterator visits each element once", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    Set<String> visited = new HashSet<String>();
                    for (String value : set) {
                        assertTrue(visited.add(value));
                    }
                    assertEquals(setOf("a", "b", "c"), visited);
                }),
                test(implementation, "empty iterator is exhausted", factory, set -> {
                    Iterator<String> iterator = set.iterator();
                    try {
                        assertFalse(iterator.hasNext());
                        assertThrows(java.util.NoSuchElementException.class, iterator::next);
                    }
                    finally {
                        close(iterator);
                    }
                }),
                test(implementation, "iterator rejects remove before next and after a remove", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    Iterator<String> iterator = set.iterator();
                    try {
                        assertThrows(IllegalStateException.class, iterator::remove);
                        String removed = iterator.next();
                        iterator.remove();
                        assertFalse(set.contains(removed));
                        assertThrows(IllegalStateException.class, iterator::remove);
                        while (iterator.hasNext()) {
                            iterator.next();
                        }
                    }
                    finally {
                        close(iterator);
                    }
                }),
                test(implementation, "exhausted iterator rejects next", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    Iterator<String> iterator = set.iterator();
                    try {
                        while (iterator.hasNext()) {
                            iterator.next();
                        }
                        assertThrows(java.util.NoSuchElementException.class, iterator::next);
                    }
                    finally {
                        close(iterator);
                    }
                }),
                test(implementation, "iterator can remove the final element after exhaustion", factory, set -> {
                    set.add("a");
                    Iterator<String> iterator = set.iterator();
                    try {
                        assertEquals("a", iterator.next());
                        assertFalse(iterator.hasNext());
                        iterator.remove();
                        assertTrue(set.isEmpty());
                        assertThrows(IllegalStateException.class, iterator::remove);
                    }
                    finally {
                        close(iterator);
                    }
                }),
                test(implementation, "iterator remove updates the set", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    Iterator<String> iterator = set.iterator();
                    try {
                        String removed = iterator.next();
                        iterator.remove();
                        assertFalse(set.contains(removed));
                        assertEquals(2, set.size());
                        while (iterator.hasNext()) {
                            iterator.next();
                        }
                    }
                    finally {
                        close(iterator);
                    }
                }),
                test(implementation, "object array contains every element", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    Object[] result = set.toArray();
                    assertEquals(Object[].class, result.getClass());
                    assertEquals(setOf("a", "b", "c"), new HashSet<Object>(Arrays.asList(result)));
                }),
                test(implementation, "zero and exact-size typed arrays follow Collection contract", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    String[] zeroLength = new String[0];
                    String[] allocated = set.toArray(zeroLength);
                    assertNotSame(zeroLength, allocated);
                    assertEquals(setOf("a", "b"), new HashSet<String>(Arrays.asList(allocated)));

                    String[] exactSize = new String[2];
                    assertSame(exactSize, set.toArray(exactSize));
                    assertEquals(setOf("a", "b"), new HashSet<String>(Arrays.asList(exactSize)));
                }),
                test(implementation, "typed array follows Collection contract", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    String[] target = new String[] {"stale", "stale", "sentinel"};
                    String[] result = set.toArray(target);
                    assertSame(target, result);
                    assertEquals(setOf("a", "b"), new HashSet<String>(Arrays.asList(result[0], result[1])));
                    assertNull(result[2]);
                }),
                test(implementation, "incompatible typed arrays respect runtime component type", factory, set -> {
                    Integer[] emptyTarget = new Integer[0];
                    assertSame(emptyTarget, set.toArray(emptyTarget));
                    set.add("a");
                    assertThrows(ArrayStoreException.class, () -> set.toArray(new Integer[0]));
                }),
                test(implementation, "equals is symmetric with standard sets", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    Set<String> standard = setOf("a", "b", "c");
                    assertEquals(standard, set);
                    assertEquals(set, standard);
                    assertEquals(standard.hashCode(), set.hashCode());
                }),
                test(implementation, "equals rejects different members", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertFalse(set.equals(setOf("a", "c")));
                    assertFalse(set.equals(Arrays.asList("a", "b")));
                }),
                test(implementation, "equals covers identity null and size partitions", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertTrue(set.equals(set));
                    assertFalse(set.equals(null));
                    assertFalse(set.equals("not a set"));
                    assertFalse(set.equals(Collections.singleton("a")));
                    assertFalse(set.equals(setOf("a", "b", "c")));
                    assertFalse(set.equals(setOf("a", null)));
                }),
                test(implementation, "hash code is the sum of member hash codes", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertEquals("a".hashCode() + "b".hashCode() + "c".hashCode(), set.hashCode());
                }),
                test(implementation, "string representation contains each member once", factory, set -> {
                    assertEquals("[]", set.toString());
                    set.addAll(Arrays.asList("a", "b"));
                    String representation = set.toString();
                    assertTrue(representation.startsWith("["));
                    assertTrue(representation.endsWith("]"));
                    assertEquals(setOf("a", "b"),
                            new HashSet<String>(Arrays.asList(
                                    representation.substring(1, representation.length() - 1).split(", "))));
                }),
                test(implementation, "removeIf evaluates and removes matches", factory, set -> {
                    set.addAll(Arrays.asList("aa", "b", "cc"));
                    assertTrue(set.removeIf(value -> value.length() == 2));
                    assertEquals(Collections.singleton("b"), set);
                    assertFalse(set.removeIf(value -> value.length() == 2));
                }),
                test(implementation, "removeIf covers all matches and rejects null predicates", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertTrue(set.removeIf(value -> true));
                    assertTrue(set.isEmpty());
                    assertThrows(NullPointerException.class, () -> set.removeIf(null));
                }),
                test(implementation, "forEach observes every member", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    Set<String> visited = new LinkedHashSet<String>();
                    set.forEach(visited::add);
                    assertEquals(setOf("a", "b", "c"), visited);
                }),
                test(implementation, "stream observes every member", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertEquals(setOf("a", "b", "c"), set.stream().collect(java.util.stream.Collectors.toSet()));
                    assertEquals(3L, set.stream().count());
                }),
                test(implementation, "spliterator is distinct and complete", factory, set -> {
                    set.addAll(Arrays.asList("a", "b", "c"));
                    assertTrue(set.spliterator().hasCharacteristics(Spliterator.DISTINCT));
                    assertFalse(set.spliterator().hasCharacteristics(Spliterator.IMMUTABLE));
                    List<String> visited = new ArrayList<String>();
                    set.spliterator().forEachRemaining(visited::add);
                    assertEquals(setOf("a", "b", "c"), new HashSet<String>(visited));
                }),
                test(implementation, "CQEngine regression: self addAll is unchanged", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertFalse(set.addAll(set));
                    assertEquals(setOf("a", "b"), set);
                }),
                test(implementation, "CQEngine regression: self retainAll is unchanged", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertFalse(set.retainAll(set));
                    assertEquals(setOf("a", "b"), set);
                }),
                test(implementation, "CQEngine regression: self removeAll clears", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    assertTrue(set.removeAll(set));
                    assertTrue(set.isEmpty());
                }),
                test(implementation, "array snapshots do not mutate the set", factory, set -> {
                    set.addAll(Arrays.asList("a", "b"));
                    Object[] snapshot = set.toArray();
                    Arrays.fill(snapshot, "changed");
                    assertEquals(setOf("a", "b"), set);
                }));
    }

    private static DynamicTest test(
            String implementation,
            String behavior,
            Supplier<? extends Set<String>> factory,
            SetBehavior behaviorTest) {
        return DynamicTest.dynamicTest(implementation + ": " + behavior, () -> {
            Set<String> set = factory.get();
            try {
                behaviorTest.verify(set);
            }
            finally {
                close(set);
            }
        });
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    private static void assertFalseOrThrowsNullPointer(BooleanOperation operation) {
        try {
            assertFalse(operation.execute());
        }
        catch (NullPointerException tolerated) {
            // CQEngine rejects null values; Set permits either false or NullPointerException for null queries.
        }
    }

    private static void assertFalseOrThrowsClassCast(BooleanOperation operation) {
        try {
            assertFalse(operation.execute());
        }
        catch (ClassCastException tolerated) {
            // Set permits either false or ClassCastException for queries of an incompatible type.
        }
    }

    private static void close(Object resource) throws Exception {
        if (resource instanceof AutoCloseable) {
            ((AutoCloseable) resource).close();
        }
    }

    private interface SetBehavior {
        void verify(Set<String> set) throws Exception;
    }

    private interface BooleanOperation {
        boolean execute();
    }
}
