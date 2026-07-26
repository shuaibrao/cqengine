// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

public final class IndexedCollectionFunctionalShards {

    private static final int SHARD_COUNT = 4;
    private static final int EXPECTED_SCENARIO_COUNT = 7_654;

    private IndexedCollectionFunctionalShards() {
    }

    static Stream<IndexedCollectionFunctionalTest.Scenario> scenarios(int shardIndex) {
        return IndexedCollectionFunctionalTest.expandMacroScenarios(
                        shardIndex, SHARD_COUNT, EXPECTED_SCENARIO_COUNT)
                .stream()
                .map(arguments -> (IndexedCollectionFunctionalTest.Scenario) arguments.get(0));
    }

    static void run(IndexedCollectionFunctionalTest.Scenario scenario) {
        new IndexedCollectionFunctionalTest().testScenario(scenario);
    }

    public static class Shard1 {
        public static Stream<IndexedCollectionFunctionalTest.Scenario> scenarios() {
            return IndexedCollectionFunctionalShards.scenarios(0);
        }

        @ParameterizedTest(name = "{index}: {0}")
        @MethodSource("scenarios")
        public void testScenario(IndexedCollectionFunctionalTest.Scenario scenario) {
            run(scenario);
        }
    }

    public static class Shard2 {
        public static Stream<IndexedCollectionFunctionalTest.Scenario> scenarios() {
            return IndexedCollectionFunctionalShards.scenarios(1);
        }

        @ParameterizedTest(name = "{index}: {0}")
        @MethodSource("scenarios")
        public void testScenario(IndexedCollectionFunctionalTest.Scenario scenario) {
            run(scenario);
        }
    }

    public static class Shard3 {
        public static Stream<IndexedCollectionFunctionalTest.Scenario> scenarios() {
            return IndexedCollectionFunctionalShards.scenarios(2);
        }

        @ParameterizedTest(name = "{index}: {0}")
        @MethodSource("scenarios")
        public void testScenario(IndexedCollectionFunctionalTest.Scenario scenario) {
            run(scenario);
        }
    }

    public static class Shard4 {
        public static Stream<IndexedCollectionFunctionalTest.Scenario> scenarios() {
            return IndexedCollectionFunctionalShards.scenarios(3);
        }

        @ParameterizedTest(name = "{index}: {0}")
        @MethodSource("scenarios")
        public void testScenario(IndexedCollectionFunctionalTest.Scenario scenario) {
            run(scenario);
        }
    }
}
