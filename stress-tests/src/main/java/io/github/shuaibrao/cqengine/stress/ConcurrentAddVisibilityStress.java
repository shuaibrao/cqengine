// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.stress;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
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
@Outcome(id = "0, 1, 1", expect = Expect.ACCEPTABLE, desc = "Read preceded publication; final state is consistent.")
@Outcome(id = "1, 1, 1", expect = Expect.ACCEPTABLE, desc = "Read observed the published object.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Collection and index must agree after actor completion.")
@State
public class ConcurrentAddVisibilityStress {

    private final IndexedCollection<StressRecord> records = new ConcurrentIndexedCollection<>();
    private final StressRecord record = new StressRecord(1, 7, 0);

    public ConcurrentAddVisibilityStress() {
        records.addIndex(HashIndex.onAttribute(StressRecord.ID));
    }

    @Actor
    public void add() {
        if (!records.add(record)) {
            throw new IllegalStateException("The first add must modify the collection");
        }
    }

    @Actor
    public void read(III_Result result) {
        result.r1 = countById();
    }

    @Arbiter
    public void checkFinalState(III_Result result) {
        result.r2 = records.size();
        result.r3 = countById();
    }

    private int countById() {
        try (ResultSet<StressRecord> resultSet = records.retrieve(equal(StressRecord.ID, record.id()))) {
            return resultSet.size();
        }
    }
}
