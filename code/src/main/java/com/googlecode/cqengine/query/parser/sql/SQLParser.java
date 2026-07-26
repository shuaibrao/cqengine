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
package com.googlecode.cqengine.query.parser.sql;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.parser.common.InvalidQueryException;
import com.googlecode.cqengine.query.parser.common.ParseResult;
import com.googlecode.cqengine.query.parser.common.ParserLimits;
import com.googlecode.cqengine.query.parser.common.QueryParser;
import com.googlecode.cqengine.query.parser.common.RegexPolicy;
import com.googlecode.cqengine.query.parser.sql.grammar.SQLGrammarLexer;
import com.googlecode.cqengine.query.parser.sql.grammar.SQLGrammarParser;
import com.googlecode.cqengine.query.parser.sql.support.FallbackValueParser;
import com.googlecode.cqengine.query.parser.sql.support.SQLAntlrListener;
import com.googlecode.cqengine.query.parser.sql.support.StringParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.Map;

/**
 * A parser for SQL queries.
 *
 * @author Niall Gallagher
 */
public class SQLParser<O> extends QueryParser<O> {

    public SQLParser(Class<O> objectType) {
        this(objectType, ParserLimits.defaults());
    }

    /**
     * Creates a parser with finite resource limits.
     */
    public SQLParser(Class<O> objectType, ParserLimits parserLimits) {
        super(objectType, parserLimits, RegexPolicy.TRUSTED_JAVA_UTIL_REGEX);
        StringParser stringParser = new StringParser();
        valueParsers.put(String.class, stringParser);
        fallbackValueParser = new FallbackValueParser(stringParser);
    }

    @Override
    public ParseResult<O> parse(String query) {
        try {
            ParserLimits limits = validateQueryInput(query);
            SQLGrammarLexer lexer = new SQLGrammarLexer(CharStreams.fromString(query));
            lexer.removeErrorListeners();
            lexer.addErrorListener(SYNTAX_ERROR_LISTENER);

            CommonTokenStream tokens = createTokenStream(lexer, limits);

            SQLGrammarParser parser = new SQLGrammarParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(SYNTAX_ERROR_LISTENER);
            enforceNestingLimit(
                    parser, limits, SQLGrammarParser.RULE_query, SQLGrammarParser.RULE_simpleQuery);

            SQLGrammarParser.StartContext queryContext = parser.start();

            ParseTreeWalker walker = new ParseTreeWalker();
            SQLAntlrListener<O> listener = new SQLAntlrListener<O>(this);
            walker.walk(listener, queryContext);
            return new ParseResult<O>(listener.getParsedQuery(), listener.getQueryOptions());
        }
        catch (InvalidQueryException e) {
            throw e;
        }
        catch (Exception e) {
            throw new InvalidQueryException("Failed to parse query", e);
        }
    }

    /**
     * Creates a new SQLParser for the given POJO class.
     * @param pojoClass The type of object stored in the collection
     * @return a new SQLParser for the given POJO class
     */
    public static <O> SQLParser<O> forPojo(Class<O> pojoClass) {
        return new SQLParser<O>(pojoClass);
    }

    public static <O> SQLParser<O> forPojo(Class<O> pojoClass, ParserLimits parserLimits) {
        return new SQLParser<O>(pojoClass, parserLimits);
    }

    /**
     * Creates a new SQLParser for the given POJO class, and registers the given attributes with it.
     * @param pojoClass The type of object stored in the collection
     * @param attributes The attributes to register with the parser
     * @return a new SQLParser for the given POJO class
     */
    public static <O> SQLParser<O> forPojoWithAttributes(Class<O> pojoClass, Map<String, ? extends Attribute<O, ?>> attributes) {
        SQLParser<O> parser = forPojo(pojoClass);
        parser.registerAttributes(attributes);
        return parser;
    }

    public static <O> SQLParser<O> forPojoWithAttributes(
            Class<O> pojoClass,
            Map<String, ? extends Attribute<O, ?>> attributes,
            ParserLimits parserLimits) {
        SQLParser<O> parser = forPojo(pojoClass, parserLimits);
        parser.registerAttributes(attributes);
        return parser;
    }
}
