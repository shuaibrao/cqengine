// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.testutil;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;

public final class TestAssertions {

    private TestAssertions() {
    }

    public static void assertArrayEquals(byte[] expected, byte[] actual) {
        Assertions.assertArrayEquals(expected, actual);
    }

    public static void assertArrayEquals(int[] expected, int[] actual) {
        Assertions.assertArrayEquals(expected, actual);
    }

    public static void assertArrayEquals(Object[] expected, Object[] actual) {
        Assertions.assertArrayEquals(expected, actual);
    }

    public static void assertEquals(Object expected, Object actual) {
        Assertions.assertEquals(expected, actual);
    }

    public static void assertEquals(long expected, long actual) {
        Assertions.assertEquals(expected, actual);
    }

    public static void assertEquals(double expected, double actual, double delta) {
        Assertions.assertEquals(expected, actual, delta);
    }

    public static void assertEquals(float expected, float actual, float delta) {
        Assertions.assertEquals(expected, actual, delta);
    }

    public static void assertEquals(String message, Object expected, Object actual) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(String message, long expected, long actual) {
        Assertions.assertEquals(expected, actual, message);
    }

    public static void assertEquals(String message, double expected, double actual, double delta) {
        Assertions.assertEquals(expected, actual, delta, message);
    }

    public static void assertEquals(String message, float expected, float actual, float delta) {
        Assertions.assertEquals(expected, actual, delta, message);
    }

    public static void assertFalse(boolean condition) {
        Assertions.assertFalse(condition);
    }

    public static void assertFalse(String message, boolean condition) {
        Assertions.assertFalse(condition, message);
    }

    public static void assertNotEquals(Object unexpected, Object actual) {
        Assertions.assertNotEquals(unexpected, actual);
    }

    public static void assertNotEquals(long unexpected, long actual) {
        Assertions.assertNotEquals(unexpected, actual);
    }

    public static void assertNotNull(Object actual) {
        Assertions.assertNotNull(actual);
    }

    public static void assertNotNull(String message, Object actual) {
        Assertions.assertNotNull(actual, message);
    }

    public static void assertNotSame(Object unexpected, Object actual) {
        Assertions.assertNotSame(unexpected, actual);
    }

    public static void assertNull(Object actual) {
        Assertions.assertNull(actual);
    }

    public static void assertNull(String message, Object actual) {
        Assertions.assertNull(actual, message);
    }

    public static void assertSame(Object expected, Object actual) {
        Assertions.assertSame(expected, actual);
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
        return Assertions.assertThrows(expectedType, executable);
    }

    public static <T extends Throwable> T assertThrows(
            String message, Class<T> expectedType, Executable executable) {
        return Assertions.assertThrows(expectedType, executable, message);
    }

    public static void assertTrue(boolean condition) {
        Assertions.assertTrue(condition);
    }

    public static void assertTrue(String message, boolean condition) {
        Assertions.assertTrue(condition, message);
    }

    public static void fail() {
        Assertions.fail();
    }

    public static void fail(String message) {
        Assertions.fail(message);
    }
}
