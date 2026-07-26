// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.query.parser.common;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.QueryFactory;

/**
 * Controls how a string parser turns a regular-expression clause into a query.
 *
 * <p>The compatibility policy uses {@code java.util.regex}. It has no execution deadline and is
 * suitable only when patterns and candidate values are trusted. Applications accepting untrusted
 * patterns should disable the clause or provide a policy backed by an independently approved,
 * bounded implementation.
 */
public interface RegexPolicy {

    /**
     * Preserves the historical {@code java.util.regex} behavior for trusted input.
     */
    RegexPolicy TRUSTED_JAVA_UTIL_REGEX = new RegexPolicy() {
        @Override
        public <O, A extends CharSequence> Query<O> createQuery(Attribute<O, A> attribute, String expression) {
            return QueryFactory.matchesRegex(attribute, expression);
        }

        @Override
        public String toString() {
            return "TRUSTED_JAVA_UTIL_REGEX";
        }
    };

    /**
     * Rejects every regular-expression clause parsed from a string query.
     */
    RegexPolicy DISABLED = new RegexPolicy() {
        @Override
        public <O, A extends CharSequence> Query<O> createQuery(Attribute<O, A> attribute, String expression) {
            throw new InvalidQueryException("Regular-expression queries are disabled by policy");
        }

        @Override
        public String toString() {
            return "DISABLED";
        }
    };

    /**
     * Creates the query which will evaluate a parsed expression.
     *
     * @param attribute parsed target attribute
     * @param expression decoded expression text
     * @return a non-null query implementing the application's approved policy
     */
    <O, A extends CharSequence> Query<O> createQuery(Attribute<O, A> attribute, String expression);
}
