// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.stress;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.resultset.ResultSet;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

import static com.googlecode.cqengine.query.QueryFactory.equal;

@JCStressTest
@Outcome(id = "2, 2, 2", expect = Expect.ACCEPTABLE, desc = "Both distinct writes reached both indexes.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Distinct concurrent writes must not desynchronize indexes.")
@State
public class ConcurrentDistinctWriterStress {

    private final IndexedCollection<StressRecord> records = new ConcurrentIndexedCollection<>();

    public ConcurrentDistinctWriterStress() {
        records.addIndex(HashIndex.onAttribute(StressRecord.ID));
        records.addIndex(NavigableIndex.onAttribute(StressRecord.GROUP));
    }

    @Actor
    public void addFirst() {
        add(new StressRecord(1, 7, 0));
    }

    @Actor
    public void addSecond() {
        add(new StressRecord(2, 7, 0));
    }

    @Arbiter
    public void checkFinalState(III_Result result) {
        result.r1 = records.size();
        result.r2 = querySize(StressRecord.ID, 1) + querySize(StressRecord.ID, 2);
        result.r3 = querySize(StressRecord.GROUP, 7);
    }

    private void add(StressRecord record) {
        if (!records.add(record)) {
            throw new IllegalStateException("A distinct object was not added: " + record);
        }
    }

    private int querySize(com.googlecode.cqengine.attribute.Attribute<StressRecord, Integer> attribute, int value) {
        try (ResultSet<StressRecord> resultSet = records.retrieve(equal(attribute, value))) {
            return resultSet.size();
        }
    }
}
