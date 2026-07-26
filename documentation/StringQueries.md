# SQL and CQN String Queries

CQEngine can turn strings into queries in SQL or CQN (CQEngine Native) syntax. Register the attributes which the parser
may address, then either parse a string into a `Query` or retrieve directly from an `IndexedCollection`.

```java
SQLParser<Car> sql = SQLParser.forPojoWithAttributes(
        Car.class, createAttributes(Car.class));

try (ResultSet<Car> results = sql.retrieve(
        cars,
        "SELECT * FROM cars WHERE manufacturer = 'Ford' AND price <= 5000.0 "
                + "ORDER BY price ASC")) {
    results.forEach(System.out::println);
}
```

The equivalent CQN shape uses the names of CQEngine query factories:

```java
CQNParser<Car> cqn = CQNParser.forPojoWithAttributes(
        Car.class, createAttributes(Car.class));

try (ResultSet<Car> results = cqn.retrieve(
        cars,
        "and(equal(\"manufacturer\", \"Ford\"), lessThanOrEqualTo(\"price\", 5000.0))")) {
    results.forEach(System.out::println);
}
```

Always close the returned `ResultSet`, preferably with try-with-resources. The programmatic `QueryFactory` API does not
pass through these string-parser policies.

## Parser resource limits

The SQL and CQN parsers apply finite limits before building a query:

| Resource | Default | Definition |
|---|---:|---|
| Query length | 65,536 | UTF-16 code units, checked before creating the ANTLR character stream |
| Tokens | 8,192 | Lexer tokens on every channel, excluding EOF |
| Nesting | 64 | Nested query expressions, including SQL `NOT` chains and redundant SQL parentheses |

Crossing a limit throws `InvalidQueryException`. The error identifies the configured limit without including the
rejected query. Malformed syntax continues to use the same exception contract.

`ParserLimits` is immutable and can be selected for each input boundary:

```java
ParserLimits limits = new ParserLimits(16_384, 2_048, 32);

SQLParser<Car> sql = SQLParser.forPojoWithAttributes(
        Car.class, createAttributes(Car.class), limits);

CQNParser<Car> cqn = CQNParser.forPojoWithAttributes(
        Car.class, createAttributes(Car.class), limits, RegexPolicy.DISABLED);
```

The defaults are compatibility-oriented ceilings, not universal request limits. Choose smaller values from the
application's real query corpus and retain a request-body limit at the transport boundary.

## CQN regular expressions

CQN's `matchesRegex` clause historically uses `java.util.regex.Pattern`. The default policy is named
`RegexPolicy.TRUSTED_JAVA_UTIL_REGEX` to make that compatibility choice explicit. Java regular-expression matching has
no deadline and patterns can cause excessive backtracking, so the default is suitable only when patterns and candidate
values are trusted.

Use `RegexPolicy.DISABLED` for untrusted string queries. If an untrusted boundary needs regular expressions, provide a
`RegexPolicy` which rejects unsupported expressions or creates a query backed by an independently approved bounded
engine. The policy receives the decoded expression and target attribute and must return a non-null query.

The policy applies to CQN parsing only. Direct `QueryFactory.matchesRegex(...)` calls are not intercepted.

## Scope of the limits

Length, token and nesting limits bound parsing work. They do not impose an execution deadline on application value
parsers, custom regex policies or evaluation of the resulting query. Treat those extensions and query execution as
separate resource boundaries when processing untrusted input.

## Bounded parser verification

The SQL and CQN regression suite exercises known-valid queries, known-malformed queries and 1,024 deterministic
generated inputs per dialect. Each dialect runs in a separate JVM with finite heap and stack sizes and a 20-second
parent-process deadline. The generated corpus includes grammar-biased mutations, arbitrary UTF-16 (including malformed
surrogate sequences), quote and numeric floods, token-heavy fragments and nested expressions on both sides of the
configured depth limit.

A generated input may parse successfully or fail with `InvalidQueryException`; a known-malformed input must retain the
exception contract. A timeout, VM failure, resource exhaustion or any other throwable fails the test. Failure output
records the dialect, fixed seed and case ordinal so the exact corpus position can be reproduced. This boundary covers
the built-in parsers and value parsers only; application value parsers and regex policies need equivalent isolation at
their own trust boundary.
