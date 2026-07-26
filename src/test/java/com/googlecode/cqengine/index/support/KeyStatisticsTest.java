// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.index.support;

import org.junit.jupiter.api.Test;

import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertNotSame;

public class KeyStatisticsTest {

    @Test
    public void equalCountsDoNotDependOnIntegerIdentity() {
        Integer firstCount = Integer.valueOf(Integer.MAX_VALUE);
        Integer secondCount = Integer.valueOf(Integer.MAX_VALUE);
        assertNotSame(firstCount, secondCount);

        KeyStatistics<String> first = new KeyStatistics<String>("key", firstCount);
        KeyStatistics<String> second = new KeyStatistics<String>("key", secondCount);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
