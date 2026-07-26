// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.query.parser.common;

import com.googlecode.cqengine.query.parser.cqn.CQNParser;
import com.googlecode.cqengine.query.parser.sql.SQLParser;
import com.googlecode.cqengine.testutil.Car;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ParserProcessFuzzTest {

    private static final long CQN_SEED = 0x43514E5F46555A5AL;
    private static final long SQL_SEED = 0x53514C5F46555A5AL;
    private static final int GENERATED_CASES = 1_024;
    private static final long PROCESS_TIMEOUT_SECONDS = 20;

    @Test
    @Timeout(30)
    public void cqnParserRemainsBoundedForGeneratedInputs() throws Exception {
        runProbe(Dialect.CQN, CQN_SEED);
    }

    @Test
    @Timeout(30)
    public void sqlParserRemainsBoundedForGeneratedInputs() throws Exception {
        runProbe(Dialect.SQL, SQL_SEED);
    }

    private static void runProbe(Dialect dialect, long seed) throws Exception {
        Path output = Files.createTempFile("cqengine-parser-fuzz-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(
                    PathToJava.executable(),
                    "-ea",
                    "-Xms16m",
                    "-Xmx96m",
                    "-Xss512k",
                    "-XX:+ExitOnOutOfMemoryError",
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    System.getProperty("java.class.path"),
                    Probe.class.getName(),
                    dialect.name().toLowerCase(Locale.ROOT),
                    Long.toString(seed),
                    Integer.toString(GENERATED_CASES))
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminate(process);
                fail("Parser fuzz process timed out: " + probeDescription(dialect, seed) + "\n" + tail(output));
            }

            String processOutput = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), processOutput);
            assertTrue(
                    processOutput.contains("parser-fuzz=ok dialect="
                            + dialect.name().toLowerCase(Locale.ROOT)
                            + " seed="
                            + seed
                            + " generated="
                            + GENERATED_CASES),
                    processOutput);
        }
        finally {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            Files.deleteIfExists(output);
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String tail(Path output) throws IOException {
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        return String.join(System.lineSeparator(), lines.subList(Math.max(0, lines.size() - 30), lines.size()));
    }

    private static String probeDescription(Dialect dialect, long seed) {
        return "dialect="
                + dialect.name().toLowerCase(Locale.ROOT)
                + " seed="
                + seed
                + " generated="
                + GENERATED_CASES;
    }

    public static final class Probe {

        private static final ParserLimits LIMITS = new ParserLimits(2_048, 512, 32);

        private Probe() {
        }

        public static void main(String[] arguments) {
            if (arguments.length != 3) {
                throw new IllegalArgumentException("Expected dialect, seed and generated-case count");
            }
            Dialect dialect = Dialect.valueOf(arguments[0].toUpperCase(Locale.ROOT));
            long seed = Long.parseLong(arguments[1]);
            int generatedCases = Integer.parseInt(arguments[2]);
            if (generatedCases < 1 || generatedCases > GENERATED_CASES) {
                throw new IllegalArgumentException("Generated-case count is outside the test bound: " + generatedCases);
            }

            QueryParser<Car> parser = createParser(dialect);
            verifyKnownOutcomes(parser, dialect);
            Generator generator = new Generator(seed);
            for (int ordinal = 0; ordinal < generatedCases; ordinal++) {
                System.out.println("parser-fuzz=running dialect="
                        + arguments[0]
                        + " seed="
                        + seed
                        + " ordinal="
                        + ordinal);
                System.out.flush();
                exerciseGeneratedInput(parser, generatedInput(dialect, generator, ordinal), dialect, seed, ordinal);
            }
            System.out.println("parser-fuzz=ok dialect="
                    + arguments[0]
                    + " seed="
                    + seed
                    + " generated="
                    + generatedCases);
        }

        private static QueryParser<Car> createParser(Dialect dialect) {
            QueryParser<Car> parser;
            if (dialect == Dialect.CQN) {
                parser = new CQNParser<Car>(Car.class, LIMITS, RegexPolicy.DISABLED);
            }
            else {
                parser = new SQLParser<Car>(Car.class, LIMITS);
            }
            parser.registerAttributes(Arrays.asList(
                    Car.CAR_ID,
                    Car.MANUFACTURER,
                    Car.MODEL,
                    Car.COLOR,
                    Car.DOORS,
                    Car.PRICE,
                    Car.FEATURES));
            return parser;
        }

        private static void verifyKnownOutcomes(QueryParser<Car> parser, Dialect dialect) {
            List<String> validInputs;
            List<String> malformedInputs;
            if (dialect == Dialect.CQN) {
                validInputs = Arrays.asList(
                        "all(Car.class)",
                        "equal(\"manufacturer\", \"Ford\")",
                        "and(equal(\"manufacturer\", \"Ford\"), not(equal(\"doors\", 2)))");
                malformedInputs = Arrays.asList(
                        "",
                        "(",
                        "all(Car.class",
                        "all(Car.class) trailing",
                        "equal(\"unknown\", \"value\")",
                        "equal(\"doors\", \"not-an-integer\")",
                        "matchesRegex(\"model\", \".*\")",
                        "not(".repeat(40) + "all(Car.class)" + ")".repeat(40),
                        "x ".repeat(600),
                        " ".repeat(LIMITS.getMaxQueryLength() + 1));
            }
            else {
                validInputs = Arrays.asList(
                        "SELECT * FROM cars",
                        "SELECT * FROM cars WHERE manufacturer = 'Ford'",
                        "SELECT * FROM cars WHERE manufacturer = 'Ford' ORDER BY carId DESC");
                malformedInputs = Arrays.asList(
                        "",
                        "SELECT",
                        "SELECT * FROM cars WHERE manufacturer = '",
                        "SELECT * FROM cars trailing",
                        "SELECT * FROM cars WHERE unknown = 'value'",
                        "SELECT * FROM cars WHERE doors = 'not-an-integer'",
                        "SELECT * FROM cars WHERE " + "NOT ".repeat(40) + "manufacturer = 'Ford'",
                        "SELECT * FROM cars WHERE " + "(".repeat(40) + "manufacturer = 'Ford'" + ")".repeat(40),
                        "x ".repeat(600),
                        " ".repeat(LIMITS.getMaxQueryLength() + 1));
            }

            for (String input : validInputs) {
                try {
                    ParseResult<Car> result = parser.parse(input);
                    if (result == null || result.getQuery() == null || result.getQueryOptions() == null) {
                        throw new AssertionError("Valid input produced an incomplete parse result: " + escape(input));
                    }
                }
                catch (Throwable throwable) {
                    throw unexpected("valid", dialect, -1L, -1, input, throwable);
                }
            }
            for (String input : malformedInputs) {
                expectInvalid(parser, dialect, input);
            }
        }

        private static void expectInvalid(QueryParser<Car> parser, Dialect dialect, String input) {
            try {
                parser.parse(input);
            }
            catch (InvalidQueryException expected) {
                return;
            }
            catch (Throwable throwable) {
                throw unexpected("malformed", dialect, -1L, -1, input, throwable);
            }
            throw new AssertionError("Malformed input was accepted by "
                    + dialect.name().toLowerCase(Locale.ROOT)
                    + ": "
                    + escape(input));
        }

        private static void exerciseGeneratedInput(
                QueryParser<Car> parser, String input, Dialect dialect, long seed, int ordinal) {
            try {
                ParseResult<Car> result = parser.parse(input);
                if (result == null || result.getQuery() == null || result.getQueryOptions() == null) {
                    throw new AssertionError("Parser returned an incomplete result");
                }
            }
            catch (InvalidQueryException expected) {
                return;
            }
            catch (Throwable throwable) {
                throw unexpected("generated", dialect, seed, ordinal, input, throwable);
            }
        }

        private static AssertionError unexpected(
                String corpus,
                Dialect dialect,
                long seed,
                int ordinal,
                String input,
                Throwable throwable) {
            return new AssertionError(
                    "Unexpected parser outcome: corpus="
                            + corpus
                            + " dialect="
                            + dialect.name().toLowerCase(Locale.ROOT)
                            + " seed="
                            + seed
                            + " ordinal="
                            + ordinal
                            + " input="
                            + escape(input),
                    throwable);
        }

        private static String generatedInput(Dialect dialect, Generator generator, int ordinal) {
            switch (ordinal % 8) {
                case 0:
                    return grammarBiasedCharacters(generator, generator.nextInt(513));
                case 1:
                    return arbitraryUtf16(generator, generator.nextInt(513));
                case 2:
                    return mutate(validTemplate(dialect, generator), generator);
                case 3:
                    return repeatedFragments(dialect, generator);
                case 4:
                    return quotedCharacters(dialect, generator);
                case 5:
                    return numericCharacters(dialect, generator);
                case 6:
                    return nestedInput(dialect, generator);
                default:
                    return validInput(dialect, generator, ordinal);
            }
        }

        private static String grammarBiasedCharacters(Generator generator, int length) {
            String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_(),.*<>=!+-/|'\" \t\r\n";
            StringBuilder input = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                input.append(alphabet.charAt(generator.nextInt(alphabet.length())));
            }
            return input.toString();
        }

        private static String arbitraryUtf16(Generator generator, int length) {
            StringBuilder input = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                input.append((char) generator.nextInt(Character.MAX_VALUE + 1));
            }
            return input.toString();
        }

        private static String mutate(String template, Generator generator) {
            StringBuilder input = new StringBuilder(template);
            int mutations = 1 + generator.nextInt(24);
            for (int mutation = 0; mutation < mutations; mutation++) {
                int operation = generator.nextInt(4);
                int position = generator.nextInt(input.length() + 1);
                if (operation == 0 && input.length() > 0) {
                    input.deleteCharAt(Math.min(position, input.length() - 1));
                }
                else if (operation == 1 && input.length() < LIMITS.getMaxQueryLength()) {
                    input.insert(position, grammarBiasedCharacters(generator, 1 + generator.nextInt(12)));
                }
                else if (operation == 2 && input.length() > 0) {
                    input.setCharAt(Math.min(position, input.length() - 1), (char) generator.nextInt(128));
                }
                else if (input.length() > 1 && input.length() < LIMITS.getMaxQueryLength()) {
                    int start = generator.nextInt(input.length());
                    int end = start + generator.nextInt(Math.min(24, input.length() - start) + 1);
                    String fragment = input.substring(start, end);
                    int remaining = LIMITS.getMaxQueryLength() - input.length();
                    input.insert(position, fragment.substring(0, Math.min(fragment.length(), remaining)));
                }
            }
            return input.toString();
        }

        private static String repeatedFragments(Dialect dialect, Generator generator) {
            String[] fragments = dialect == Dialect.CQN
                    ? new String[] {"not(", "and(", "equal(", ",", ")", "queryOptions("}
                    : new String[] {"NOT ", "AND ", "OR ", "(", ")", "SELECT ", "WHERE ", ","};
            String fragment = fragments[generator.nextInt(fragments.length)];
            return fragment.repeat(1 + generator.nextInt(Math.max(1, LIMITS.getMaxQueryLength() / fragment.length())));
        }

        private static String quotedCharacters(Dialect dialect, Generator generator) {
            char quote = dialect == Dialect.CQN ? '\"' : '\'';
            int length = generator.nextInt(1_025);
            StringBuilder input = new StringBuilder(length + 2).append(quote);
            for (int index = 0; index < length; index++) {
                int choice = generator.nextInt(8);
                input.append(choice == 0 ? quote : choice == 1 ? '\\' : (char) generator.nextInt(128));
            }
            if (generator.nextInt(2) == 0) {
                input.append(quote);
            }
            return input.toString();
        }

        private static String numericCharacters(Dialect dialect, Generator generator) {
            String prefix = dialect == Dialect.CQN ? "equal(\"price\", " : "SELECT * FROM cars WHERE price = ";
            String alphabet = "0123456789eE.+-xNaIfnty_ ";
            int length = generator.nextInt(1_025);
            StringBuilder input = new StringBuilder(prefix).append(dialect == Dialect.CQN ? "" : "(");
            for (int index = 0; index < length; index++) {
                input.append(alphabet.charAt(generator.nextInt(alphabet.length())));
            }
            return input.append(')').toString();
        }

        private static String nestedInput(Dialect dialect, Generator generator) {
            int depth = generator.nextInt(129);
            if (dialect == Dialect.CQN) {
                return "not(".repeat(depth) + "all(Car.class)" + ")".repeat(depth);
            }
            return "SELECT * FROM cars WHERE "
                    + "(".repeat(depth)
                    + "manufacturer = 'Ford'"
                    + ")".repeat(depth);
        }

        private static String validTemplate(Dialect dialect, Generator generator) {
            String[] templates = dialect == Dialect.CQN
                    ? new String[] {
                        "all(Car.class)",
                        "equal(\"manufacturer\", \"Ford\")",
                        "between(\"price\", 1.0, 2000.0)",
                        "and(has(\"features\"), not(equal(\"doors\", 2)))"
                    }
                    : new String[] {
                        "SELECT * FROM cars",
                        "SELECT * FROM cars WHERE manufacturer = 'Ford'",
                        "SELECT * FROM cars WHERE price BETWEEN 1.0 AND 2000.0",
                        "SELECT * FROM cars WHERE features IS NOT NULL AND doors <> 2"
                    };
            return templates[generator.nextInt(templates.length)];
        }

        private static String validInput(Dialect dialect, Generator generator, int ordinal) {
            String value = "v" + ordinal + "_" + generator.nextInt(10_000);
            if (dialect == Dialect.CQN) {
                return "equal(\"manufacturer\", \"" + value + "\")";
            }
            return "SELECT * FROM cars WHERE manufacturer = '" + value + "'";
        }

        private static String escape(String input) {
            StringBuilder escaped = new StringBuilder(input.length());
            for (int index = 0; index < input.length(); index++) {
                char character = input.charAt(index);
                if (character >= 0x20 && character <= 0x7e && character != '\\') {
                    escaped.append(character);
                }
                else if (character == '\\') {
                    escaped.append("\\\\");
                }
                else {
                    escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                }
            }
            return escaped.toString();
        }
    }

    private enum Dialect {
        CQN,
        SQL
    }

    private static final class Generator {
        private long state;

        private Generator(long seed) {
            state = seed;
        }

        private long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            long value = state;
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            return value ^ (value >>> 31);
        }

        private int nextInt(int bound) {
            if (bound < 1) {
                throw new IllegalArgumentException("bound must be positive");
            }
            return (int) ((nextLong() >>> 1) % bound);
        }
    }

    private static final class PathToJava {
        private PathToJava() {
        }

        static String executable() {
            String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            return new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath();
        }
    }
}
