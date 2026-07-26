// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.query.parser.cqn.support;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;

public class DateMathParserTest {

    @Test
    public void appliesTheConfiguredTimeZoneAndSnapshotsMutableInputs() {
        Date now = Date.from(Instant.parse("2020-01-01T12:00:00Z"));
        TimeZone timeZone = TimeZone.getTimeZone("GMT+10:00");
        DateMathParser parser = new DateMathParser(timeZone, Locale.ROOT, now);
        now.setTime(0L);
        timeZone.setRawOffset(0);

        assertEquals(
                Date.from(Instant.parse("2019-12-31T14:00:00Z")),
                parser.validatedParse(Date.class, "\"/DAY\""));
    }

    @Test
    public void apacheParserSnapshotsItsConfiguredNow() throws Exception {
        Date now = Date.from(Instant.parse("2020-01-01T12:00:00Z"));
        ApacheSolrDataMathParser parser = new ApacheSolrDataMathParser();
        parser.setNow(now);
        now.setTime(0L);

        assertEquals(
                Date.from(Instant.parse("2020-01-01T12:00:00Z")),
                parser.parseMath("+0MILLISECOND"));
    }
}
