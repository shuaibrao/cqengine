// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexedCollectionFunctionalShardsTest {

    @Test
    void methodSourcesPartitionEveryFunctionalScenarioExactlyOnce() {
        int[] expectedShardSizes = {1_914, 1_914, 1_913, 1_913};
        Set<Integer> scenarioNumbers = new HashSet<Integer>();

        for (int shardIndex = 0; shardIndex < expectedShardSizes.length; shardIndex++) {
            List<IndexedCollectionFunctionalTest.Scenario> shard =
                    IndexedCollectionFunctionalShards.scenarios(shardIndex).toList();
            assertEquals(expectedShardSizes[shardIndex], shard.size());
            for (IndexedCollectionFunctionalTest.Scenario scenario : shard) {
                assertEquals(shardIndex, (scenario.scenarioNumber - 1) % expectedShardSizes.length);
                assertTrue(scenarioNumbers.add(scenario.scenarioNumber));
            }
        }

        assertEquals(7_654, scenarioNumbers.size());
        assertTrue(scenarioNumbers.contains(1));
        assertTrue(scenarioNumbers.contains(7_654));
    }
}
