// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.stress;

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.ObjectLockingIndexedCollection;
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
@Outcome(id = "0, 0, 0", expect = Expect.ACCEPTABLE, desc = "The remove followed the add.")
@Outcome(id = "1, 1, 1", expect = Expect.ACCEPTABLE, desc = "The add followed the remove.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Same-object mutations must leave collection and indexes consistent.")
@State
public class ObjectLockingMutationStress {

    private final IndexedCollection<StressRecord> records = new ObjectLockingIndexedCollection<>();
    private final StressRecord record = new StressRecord(1, 7, 0);

    public ObjectLockingMutationStress() {
        records.addIndex(HashIndex.onAttribute(StressRecord.ID));
        records.addIndex(NavigableIndex.onAttribute(StressRecord.GROUP));
    }

    @Actor
    public void add() {
        records.add(record);
    }

    @Actor
    public void remove() {
        records.remove(record);
    }

    @Arbiter
    public void checkFinalState(III_Result result) {
        result.r1 = records.size();
        result.r2 = querySize(StressRecord.ID, record.id());
        result.r3 = querySize(StressRecord.GROUP, record.group());
    }

    private int querySize(com.googlecode.cqengine.attribute.Attribute<StressRecord, Integer> attribute, int value) {
        try (ResultSet<StressRecord> resultSet = records.retrieve(equal(attribute, value))) {
            return resultSet.size();
        }
    }
}
