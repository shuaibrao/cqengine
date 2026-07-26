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
package com.googlecode.cqengine.resultset.common;

import com.googlecode.cqengine.resultset.ResultSet;

import java.util.Comparator;

/**
 * Stateless comparator singletons for {@link ResultSet}s based on {@link ResultSet#getRetrievalCost()} and
 * {@link com.googlecode.cqengine.resultset.ResultSet#getMergeCost()}.
 *
 * @author Niall Gallagher
 */
public class QueryCostComparators {

    @SuppressWarnings("rawtypes") // Backs the legacy public comparator return signature.
    private static final Comparator<ResultSet> RETRIEVAL_COST_COMPARATOR = new RetrievalCostComparator();
    @SuppressWarnings("rawtypes") // Backs the legacy public comparator return signature.
    private static final Comparator<ResultSet> MERGE_COST_COMPARATOR = new MergeCostComparator();

    @SuppressWarnings("rawtypes") // Retains the legacy public return signature.
    public static Comparator<ResultSet> getRetrievalCostComparator() {
        return RETRIEVAL_COST_COMPARATOR;
    }

    @SuppressWarnings("rawtypes") // Retains the legacy public return signature.
    public static Comparator<ResultSet> getMergeCostComparator() {
        return MERGE_COST_COMPARATOR;
    }

    @SuppressWarnings("rawtypes") // Implements the comparator type returned by the legacy public API.
    static class RetrievalCostComparator implements Comparator<ResultSet> {

        @Override
        public int compare(ResultSet o1, ResultSet o2) {
            final int o1RetrievalCost = o1.getRetrievalCost();
            final int o2RetrievalCost = o2.getRetrievalCost();
            if (o1RetrievalCost < o2RetrievalCost) {
                return -1;
            }
            else if (o1RetrievalCost > o2RetrievalCost) {
                return +1;
            }
            else {
                return 0;
            }
        }
    }

    @SuppressWarnings("rawtypes") // Implements the comparator type returned by the legacy public API.
    static class MergeCostComparator implements Comparator<ResultSet> {

        @Override
        public int compare(ResultSet o1, ResultSet o2) {
            final int o1MergeCost = o1.getMergeCost();
            final int o2MergeCost = o2.getMergeCost();
            if (o1MergeCost < o2MergeCost) {
                return -1;
            }
            else if (o1MergeCost > o2MergeCost) {
                return +1;
            }
            else {
                return 0;
            }
        }
    }
}
