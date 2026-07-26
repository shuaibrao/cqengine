// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.resultset.filter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.NoSuchElementException;

import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertFalse;
import static com.googlecode.cqengine.testutil.TestAssertions.assertThrows;

public class MaterializedDeduplicatedIteratorTest {

    @Test
    public void nextSupportsTheIteratorContractWithoutAHasNextCall() {
        MaterializedDeduplicatedIterator<Integer> iterator =
                new MaterializedDeduplicatedIterator<Integer>(Arrays.asList(1, 1, 2).iterator());

        assertEquals(Integer.valueOf(1), iterator.next());
        assertEquals(Integer.valueOf(2), iterator.next());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    public void nextThrowsNoSuchElementExceptionAfterHasNextReportsExhaustion() {
        MaterializedDeduplicatedIterator<Integer> iterator =
                new MaterializedDeduplicatedIterator<Integer>(Arrays.<Integer>asList().iterator());

        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }
}
