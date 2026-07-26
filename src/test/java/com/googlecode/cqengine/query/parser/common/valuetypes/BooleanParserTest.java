// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.query.parser.common.valuetypes;

import org.junit.jupiter.api.Test;

import static com.googlecode.cqengine.testutil.TestAssertions.assertFalse;
import static com.googlecode.cqengine.testutil.TestAssertions.assertThrows;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;

public class BooleanParserTest {

    private final BooleanParser parser = new BooleanParser();

    @Test
    public void acceptsEveryAsciiCaseCombination() {
        assertTrue(parser.parse(Boolean.class, "TrUe"));
        assertFalse(parser.parse(Boolean.class, "FaLsE"));
    }

    @Test
    public void rejectsUnicodeCaseFoldingLookalikes() {
        assertThrows(IllegalStateException.class, () -> parser.parse(Boolean.class, "fal\u017Fe"));
    }
}
