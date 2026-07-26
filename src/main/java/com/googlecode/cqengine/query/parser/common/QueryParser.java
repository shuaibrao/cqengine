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
package com.googlecode.cqengine.query.parser.common;

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.query.parser.common.valuetypes.*;
import com.googlecode.cqengine.resultset.ResultSet;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A service provider interface for parsers which can convert string queries to CQEngine native queries.
 * <p>
 * Subclasses can implement this to support string-based queries in various dialects,
 * such as SQL or a string representation of a CQEngine native query.
 *
 * @author Niall Gallagher
 */
public abstract class QueryParser<O> {

    protected final Class<O> objectType;
    protected final ParserLimits parserLimits;
    protected final RegexPolicy regexPolicy;
    protected final Map<String, Attribute<O, ?>> attributes = new HashMap<String, Attribute<O, ?>>();
    protected final Map<Class<?>, ValueParser<?>> valueParsers = new HashMap<Class<?>, ValueParser<?>>();
    protected volatile ValueParser<Object> fallbackValueParser = null;

    protected static final BaseErrorListener SYNTAX_ERROR_LISTENER = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e)
                throws ParseCancellationException {
            throw new InvalidQueryException("Failed to parse query at line " + line + ":" + charPositionInLine + ": " + msg);
        }
    };

    public QueryParser(Class<O> objectType) {
        this(objectType, ParserLimits.defaults(), RegexPolicy.TRUSTED_JAVA_UTIL_REGEX);
    }

    protected QueryParser(Class<O> objectType, ParserLimits parserLimits, RegexPolicy regexPolicy) {
        valueParsers.put(Boolean.class, new BooleanParser());
        valueParsers.put(Byte.class, new ByteParser());
        valueParsers.put(Character.class, new CharacterParser());
        valueParsers.put(Short.class, new ShortParser());
        valueParsers.put(Integer.class, new IntegerParser());
        valueParsers.put(Long.class, new LongParser());
        valueParsers.put(Float.class, new FloatParser());
        valueParsers.put(Double.class, new DoubleParser());
        valueParsers.put(BigInteger.class, new BigIntegerParser());
        valueParsers.put(BigDecimal.class, new BigDecimalParser());
        this.objectType = objectType;
        this.parserLimits = Objects.requireNonNull(parserLimits, "parserLimits");
        this.regexPolicy = Objects.requireNonNull(regexPolicy, "regexPolicy");
    }

    public <A> void registerAttribute(Attribute<O, A> attribute) {
        attributes.put(attribute.getAttributeName(), attribute);
    }

    public void registerAttributes(Map<String, ? extends Attribute<O, ?>> attributes) {
        registerAttributes(attributes.values());
    }

    public void registerAttributes(Iterable<? extends Attribute<O, ?>> attributes) {
        for (Attribute<O, ?> attribute : attributes) {
            registerAttribute(attribute);
        }
    }

    public <A> void registerValueParser(Class<A> valueType, ValueParser<A> valueParser) {
        valueParsers.put(valueType, valueParser);
    }

    public void registerFallbackValueParser(ValueParser<Object> fallbackValueParser) {
        this.fallbackValueParser = fallbackValueParser;
    }

    public Class<O> getObjectType() {
        return objectType;
    }

    /**
     * Returns the immutable limits configured for this parser.
     */
    public ParserLimits getParserLimits() {
        return parserLimits;
    }

    /**
     * Returns the regular-expression policy configured for this parser.
     */
    public RegexPolicy getRegexPolicy() {
        return regexPolicy;
    }

    /**
     * Applies the configured regular-expression policy to a parsed clause.
     */
    public <A extends CharSequence> Query<O> createRegexQuery(Attribute<O, A> attribute, String expression) {
        Query<O> query = regexPolicy.createQuery(attribute, expression);
        if (query == null) {
            throw new InvalidQueryException("Regex policy returned a null query");
        }
        return query;
    }

    protected ParserLimits validateQueryInput(String query) {
        if (query == null) {
            throw new InvalidQueryException("Query was null");
        }
        parserLimits.validateQueryLength(query);
        return parserLimits;
    }

    protected CommonTokenStream createTokenStream(TokenSource lexer, ParserLimits limits) {
        CommonTokenStream tokens = new CommonTokenStream(new BoundedTokenSource(lexer, limits.getMaxTokens()));
        tokens.fill();
        return tokens;
    }

    protected void enforceNestingLimit(
            Parser parser, ParserLimits limits, int queryRuleIndex, int simpleQueryRuleIndex) {
        parser.addParseListener(new QueryNestingDepthListener(
                limits.getMaxNestingDepth(), queryRuleIndex, simpleQueryRuleIndex));
    }

    public <A> Attribute<O, A> getAttribute(ParseTree attributeNameContext, Class<A> expectedSuperType) {
        String attributeName = parseValue(String.class, attributeNameContext.getText());
        Attribute<O, ?> attribute = attributes.get(attributeName);
        if (attribute == null) {
            throw new IllegalStateException("No such attribute has been registered with the parser: " + attributeName);
        }
        if (!expectedSuperType.isAssignableFrom(attribute.getAttributeType())) {
            throw new IllegalStateException("Non-" + expectedSuperType.getSimpleName() + " attribute used in a query which requires a " + expectedSuperType.getSimpleName() + " attribute: " + attribute.getAttributeName());
        }
        @SuppressWarnings("unchecked")
        Attribute<O, A> result = (Attribute<O, A>) attribute;
        return result;
    }

    public <A> A parseValue(Attribute<O, A> attribute, ParseTree parameterContext) {
        return parseValue(attribute.getAttributeType(), parameterContext.getText());
    }

    public <A> A parseValue(Class<A> valueType, ParseTree parameterContext) {
        return parseValue(valueType, parameterContext.getText());
    }

    public <A> A parseValue(Class<A> valueType, String text) {
        @SuppressWarnings("unchecked")
        ValueParser<A> valueParser = (ValueParser<A>) valueParsers.get(valueType);
        if (valueParser != null) {
            return valueParser.validatedParse(valueType, text);
        } else {
            ValueParser<Object> fallbackValueParser = this.fallbackValueParser;
            if (fallbackValueParser != null) {
                return valueType.cast(fallbackValueParser.parse(valueType, text));
            }
            else {
                throw new IllegalStateException("No value parser has been registered to parse type: " + valueType.getName());
            }
        }
    }

    /**
     * Parses the given query and its query options, encapsulating both in the object returned.
     * @param query The query to parse
     * @return An object encapsulating the parsed query and its query options
     */
    public abstract ParseResult<O> parse(String query);

    /**
     * Shortcut for parsing the given query and its query options, and then retrieving objects matching the
     * query from the given collection, using the parsed query options.
     * @param query The query to parse
     * @return The results of querying the collection with the parsed query and its query options
     */
    public ResultSet<O> retrieve(IndexedCollection<O> collection, String query) {
        ParseResult<O> parseResult = parse(query);
        return collection.retrieve(parseResult.getQuery(), parseResult.getQueryOptions());
    }


    /**
     * Shortcut for calling {@code parse(query).getQuery()}.
     * @param query The query to parse
     * @return The parsed query on its own, without any query options
     */
    public Query<O> query(String query) {
        return parse(query).getQuery();
    }

    /**
     * Shortcut for calling {@code parse(query).getQueryOptions()}.
     * @param query The query to parse
     * @return The query options, without the actual query
     */
    public QueryOptions queryOptions(String query) {
        return parse(query).getQueryOptions();
    }
}
