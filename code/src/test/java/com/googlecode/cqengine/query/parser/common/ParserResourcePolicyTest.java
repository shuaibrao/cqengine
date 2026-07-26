// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.query.parser.common;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.query.parser.cqn.CQNParser;
import com.googlecode.cqengine.query.parser.sql.SQLParser;
import com.googlecode.cqengine.testutil.Car;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;
import static com.googlecode.cqengine.testutil.TestAssertions.assertThrows;

public class ParserResourcePolicyTest {

    private static final String CQN_EQUAL = "equal(\"manufacturer\", \"Ford\")";
    private static final String SQL_EQUAL = "SELECT * FROM cars WHERE manufacturer = 'Ford'";

    @Test
    public void defaultLimitsAreFinite() {
        ParserLimits limits = ParserLimits.defaults();

        assertEquals(ParserLimits.DEFAULT_MAX_QUERY_LENGTH, limits.getMaxQueryLength());
        assertEquals(ParserLimits.DEFAULT_MAX_TOKENS, limits.getMaxTokens());
        assertEquals(ParserLimits.DEFAULT_MAX_NESTING_DEPTH, limits.getMaxNestingDepth());
    }

    @Test
    public void defaultLengthLimitIsAppliedByBothDialects() {
        String query = " ".repeat(ParserLimits.DEFAULT_MAX_QUERY_LENGTH + 1);
        String expectedMessage = "Query exceeds maximum length of "
                + ParserLimits.DEFAULT_MAX_QUERY_LENGTH
                + " UTF-16 code units";

        assertEquals(
                expectedMessage,
                assertThrows(
                                InvalidQueryException.class,
                                () -> cqnParser(ParserLimits.defaults(), RegexPolicy.TRUSTED_JAVA_UTIL_REGEX)
                                        .query(query))
                        .getMessage());
        assertEquals(
                expectedMessage,
                assertThrows(
                                InvalidQueryException.class,
                                () -> sqlParser(ParserLimits.defaults()).query(query))
                        .getMessage());
    }

    @Test
    public void limitsMustBePositive() {
        assertEquals(
                "maxQueryLength must be greater than zero",
                assertThrows(IllegalArgumentException.class, () -> new ParserLimits(0, 1, 1)).getMessage());
        assertEquals(
                "maxTokens must be greater than zero",
                assertThrows(IllegalArgumentException.class, () -> new ParserLimits(1, 0, 1)).getMessage());
        assertEquals(
                "maxNestingDepth must be greater than zero",
                assertThrows(IllegalArgumentException.class, () -> new ParserLimits(1, 1, 0)).getMessage());
    }

    @Test
    public void cqnLengthLimitAcceptsBoundaryAndRejectsNextCodeUnit() {
        assertEquals(
                QueryFactory.equal(Car.MANUFACTURER, "Ford"),
                cqnParser(new ParserLimits(CQN_EQUAL.length(), 100, 10), RegexPolicy.TRUSTED_JAVA_UTIL_REGEX)
                        .query(CQN_EQUAL));

        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> cqnParser(
                                new ParserLimits(CQN_EQUAL.length() - 1, 100, 10),
                                RegexPolicy.TRUSTED_JAVA_UTIL_REGEX)
                        .query(CQN_EQUAL));
        assertEquals(
                "Query exceeds maximum length of " + (CQN_EQUAL.length() - 1) + " UTF-16 code units",
                exception.getMessage());
    }

    @Test
    public void sqlLengthLimitAcceptsBoundaryAndRejectsNextCodeUnit() {
        assertEquals(
                QueryFactory.equal(Car.MANUFACTURER, "Ford"),
                sqlParser(new ParserLimits(SQL_EQUAL.length(), 100, 10)).query(SQL_EQUAL));

        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> sqlParser(new ParserLimits(SQL_EQUAL.length() - 1, 100, 10)).query(SQL_EQUAL));
        assertEquals(
                "Query exceeds maximum length of " + (SQL_EQUAL.length() - 1) + " UTF-16 code units",
                exception.getMessage());
    }

    @Test
    public void cqnTokenLimitFailsBeforeParsingAnUnboundedStream() {
        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> cqnParser(new ParserLimits(100, 1, 10), RegexPolicy.TRUSTED_JAVA_UTIL_REGEX)
                        .query(CQN_EQUAL));

        assertEquals("Query exceeds maximum token count of 1", exception.getMessage());
    }

    @Test
    public void sqlTokenLimitFailsBeforeParsingAnUnboundedStream() {
        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> sqlParser(new ParserLimits(100, 1, 10)).query(SQL_EQUAL));

        assertEquals("Query exceeds maximum token count of 1", exception.getMessage());
    }

    @Test
    public void cqnNestingLimitAcceptsBoundaryAndRejectsNextLevel() {
        String query = "not(not(equal(\"manufacturer\", \"Ford\")))";
        assertEquals(
                QueryFactory.not(QueryFactory.not(QueryFactory.equal(Car.MANUFACTURER, "Ford"))),
                cqnParser(new ParserLimits(100, 100, 3), RegexPolicy.TRUSTED_JAVA_UTIL_REGEX).query(query));

        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> cqnParser(new ParserLimits(100, 100, 2), RegexPolicy.TRUSTED_JAVA_UTIL_REGEX)
                        .query(query));
        assertEquals("Query exceeds maximum nesting depth of 2", exception.getMessage());
    }

    @Test
    public void sqlNestingLimitCoversNotChains() {
        String query = "SELECT * FROM cars WHERE NOT NOT manufacturer = 'Ford'";
        assertEquals(
                QueryFactory.not(QueryFactory.not(QueryFactory.equal(Car.MANUFACTURER, "Ford"))),
                sqlParser(new ParserLimits(100, 100, 3)).query(query));

        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> sqlParser(new ParserLimits(100, 100, 2)).query(query));
        assertEquals("Query exceeds maximum nesting depth of 2", exception.getMessage());
    }

    @Test
    public void sqlNestingLimitCoversRedundantParentheses() {
        String query = "SELECT * FROM cars WHERE ((manufacturer = 'Ford'))";
        assertEquals(
                QueryFactory.equal(Car.MANUFACTURER, "Ford"),
                sqlParser(new ParserLimits(100, 100, 3)).query(query));

        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> sqlParser(new ParserLimits(100, 100, 2)).query(query));
        assertEquals("Query exceeds maximum nesting depth of 2", exception.getMessage());
    }

    @Test
    public void cqnDefaultsToExplicitTrustedCompatibilityRegexPolicy() {
        CQNParser<Car> parser = cqnParser(ParserLimits.defaults(), RegexPolicy.TRUSTED_JAVA_UTIL_REGEX);

        assertSame(RegexPolicy.TRUSTED_JAVA_UTIL_REGEX, parser.getRegexPolicy());
        assertEquals(
                QueryFactory.matchesRegex(Car.MODEL, "Fo.*"),
                parser.query("matchesRegex(\"model\", \"Fo.*\")"));
    }

    @Test
    public void cqnCanDisableRegexQueries() {
        CQNParser<Car> parser = cqnParser(ParserLimits.defaults(), RegexPolicy.DISABLED);

        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> parser.query("matchesRegex(\"model\", \"Fo.*\")"));
        assertEquals("Regular-expression queries are disabled by policy", exception.getMessage());
    }

    @Test
    public void cqnCanDelegateRegexQueriesToAnApplicationPolicy() {
        AtomicReference<String> expressionSeen = new AtomicReference<String>();
        RegexPolicy applicationPolicy = new RegexPolicy() {
            @Override
            public <O, A extends CharSequence> Query<O> createQuery(
                    Attribute<O, A> attribute, String expression) {
                expressionSeen.set(expression);
                return QueryFactory.has(attribute);
            }
        };
        CQNParser<Car> parser = cqnParser(ParserLimits.defaults(), applicationPolicy);

        assertEquals(
                QueryFactory.has(Car.MODEL),
                parser.query("matchesRegex(\"model\", \"Fo\")"));
        assertEquals("Fo", expressionSeen.get());
    }

    @Test
    public void regexPolicyCannotReturnNull() {
        RegexPolicy nullPolicy = new RegexPolicy() {
            @Override
            public <O, A extends CharSequence> Query<O> createQuery(
                    Attribute<O, A> attribute, String expression) {
                return null;
            }
        };
        CQNParser<Car> parser = cqnParser(ParserLimits.defaults(), nullPolicy);

        InvalidQueryException exception = assertThrows(
                InvalidQueryException.class,
                () -> parser.query("matchesRegex(\"model\", \"Fo\")"));
        assertEquals("Regex policy returned a null query", exception.getMessage());
    }

    private static CQNParser<Car> cqnParser(ParserLimits limits, RegexPolicy regexPolicy) {
        CQNParser<Car> parser = new CQNParser<Car>(Car.class, limits, regexPolicy);
        parser.registerAttribute(Car.MANUFACTURER);
        parser.registerAttribute(Car.MODEL);
        return parser;
    }

    private static SQLParser<Car> sqlParser(ParserLimits limits) {
        SQLParser<Car> parser = new SQLParser<Car>(Car.class, limits);
        parser.registerAttribute(Car.MANUFACTURER);
        return parser;
    }
}
