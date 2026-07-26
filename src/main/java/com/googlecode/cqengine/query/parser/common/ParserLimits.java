// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.query.parser.common;

/**
 * Finite resource limits applied before and during string-query parsing.
 *
 * <p>Query length is measured in UTF-16 code units, as returned by {@link String#length()}.
 * The token limit counts lexer tokens on every channel, excluding EOF. The nesting limit counts
 * nested query expressions, including redundant SQL parentheses.
 */
public final class ParserLimits {

    public static final int DEFAULT_MAX_QUERY_LENGTH = 65_536;
    public static final int DEFAULT_MAX_TOKENS = 8_192;
    public static final int DEFAULT_MAX_NESTING_DEPTH = 64;

    private static final ParserLimits DEFAULTS =
            new ParserLimits(DEFAULT_MAX_QUERY_LENGTH, DEFAULT_MAX_TOKENS, DEFAULT_MAX_NESTING_DEPTH);

    private final int maxQueryLength;
    private final int maxTokens;
    private final int maxNestingDepth;

    /**
     * Creates finite parser limits.
     *
     * @param maxQueryLength maximum query length in UTF-16 code units
     * @param maxTokens maximum lexer tokens on all channels, excluding EOF
     * @param maxNestingDepth maximum nested query-expression depth
     */
    public ParserLimits(int maxQueryLength, int maxTokens, int maxNestingDepth) {
        this.maxQueryLength = requirePositive("maxQueryLength", maxQueryLength);
        this.maxTokens = requirePositive("maxTokens", maxTokens);
        this.maxNestingDepth = requirePositive("maxNestingDepth", maxNestingDepth);
    }

    /**
     * Returns the immutable default limits.
     */
    public static ParserLimits defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the maximum query length in UTF-16 code units.
     */
    public int getMaxQueryLength() {
        return maxQueryLength;
    }

    /**
     * Returns the maximum lexer-token count, excluding EOF.
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * Returns the maximum nested query-expression depth.
     */
    public int getMaxNestingDepth() {
        return maxNestingDepth;
    }

    void validateQueryLength(String query) {
        if (query.length() > maxQueryLength) {
            throw new InvalidQueryException(
                    "Query exceeds maximum length of " + maxQueryLength + " UTF-16 code units");
        }
    }

    private static int requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }
}
