// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.benchmark;

public enum QueryScenario {
    UNIQUE_ZERO,
    UNIQUE_ONE,
    HASH_LARGE,
    NAVIGABLE_SMALL,
    COMPOUND_LARGE,
    STANDING_MEDIUM,
    RADIX_LARGE,
    REVERSED_RADIX_LARGE,
    INVERTED_RADIX_LARGE,
    SUFFIX_LARGE,
    FALLBACK_LARGE
}
