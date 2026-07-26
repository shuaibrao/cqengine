// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package io.github.shuaibrao.cqengine.stress;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.resultset.ResultSet;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

import static com.googlecode.cqengine.query.QueryFactory.equal;

public final class ConcurrentCollectionSoak {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration INTERRUPT_TIMEOUT = Duration.ofSeconds(5);

    private ConcurrentCollectionSoak() {
    }

    public static void main(String[] arguments) throws Exception {
        Config config = Config.parse(arguments);
        Report report = run(config);
        System.out.print(report.asProperties());
    }

    private static Report run(Config config) throws Exception {
        IndexedCollection<StressRecord> records = new ConcurrentIndexedCollection<>();
        records.addIndex(HashIndex.onAttribute(StressRecord.ID));
        records.addIndex(NavigableIndex.onAttribute(StressRecord.GROUP));

        AtomicReferenceArray<StressRecord> expected = new AtomicReferenceArray<>(config.keySpace());
        for (int id = 0; id < config.keySpace(); id++) {
            StressRecord record = new StressRecord(id, initialGroup(id, config), 0);
            expected.set(id, record);
            if (!records.add(record)) {
                throw new IllegalStateException("Initial object was not added: " + record);
            }
        }

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong writeOperations = new AtomicLong();
        AtomicLong readOperations = new AtomicLong();
        int workerCount = Math.addExact(config.writers(), config.readers());
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>(workerCount);

        for (int writer = 0; writer < config.writers(); writer++) {
            int writerIndex = writer;
            workers.add(worker(
                    "cqengine-soak-writer-" + writer,
                    ready,
                    start,
                    stop,
                    failure,
                    () -> writeLoop(config, writerIndex, records, expected, stop, writeOperations)));
        }
        for (int reader = 0; reader < config.readers(); reader++) {
            int readerIndex = reader;
            workers.add(worker(
                    "cqengine-soak-reader-" + reader,
                    ready,
                    start,
                    stop,
                    failure,
                    () -> readLoop(config, readerIndex, records, stop, readOperations)));
        }

        workers.forEach(Thread::start);
        if (!ready.await(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            stop.set(true);
            start.countDown();
            stopWorkers(workers);
            throw new IllegalStateException("Workers did not become ready within " + START_TIMEOUT);
        }

        long started = System.nanoTime();
        long deadline = Math.addExact(started, TimeUnit.MILLISECONDS.toNanos(config.durationMillis()));
        start.countDown();
        while (!stop.get()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
            if (Thread.interrupted()) {
                stop.set(true);
                throw new InterruptedException("Soak coordinator was interrupted");
            }
        }
        stop.set(true);

        Throwable terminationFailure = stopWorkers(workers);
        Throwable consistencyFailure = null;
        String finalDigest = null;
        try {
            finalDigest = verifyFinalState(config, records, expected);
        }
        catch (Throwable throwable) {
            consistencyFailure = throwable;
        }

        Throwable workerFailure = failure.get();
        Throwable primaryFailure = firstNonNull(workerFailure, terminationFailure, consistencyFailure);
        if (primaryFailure != null) {
            addSuppressedIfDistinct(primaryFailure, workerFailure);
            addSuppressedIfDistinct(primaryFailure, terminationFailure);
            addSuppressedIfDistinct(primaryFailure, consistencyFailure);
            throw new IllegalStateException("Concurrent collection soak failed", primaryFailure);
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new Report(config, elapsedMillis, writeOperations.get(), readOperations.get(), finalDigest);
    }

    private static Thread worker(
            String name,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicBoolean stop,
            AtomicReference<Throwable> failure,
            Runnable action) {
        return Thread.ofPlatform().daemon(true).name(name).unstarted(() -> {
            ready.countDown();
            try {
                start.await();
                if (!stop.get()) {
                    action.run();
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (!stop.get()) {
                    captureFailure(failure, stop, interrupted);
                }
            }
            catch (Throwable throwable) {
                captureFailure(failure, stop, throwable);
            }
        });
    }

    private static void writeLoop(
            Config config,
            int writerIndex,
            IndexedCollection<StressRecord> records,
            AtomicReferenceArray<StressRecord> expected,
            AtomicBoolean stop,
            AtomicLong operations) {
        SplittableRandom random = new SplittableRandom(mixSeed(config.seed(), writerIndex));
        int ownedKeys = ((config.keySpace() - 1 - writerIndex) / config.writers()) + 1;
        while (!stop.get()) {
            int id = writerIndex + random.nextInt(ownedKeys) * config.writers();
            StressRecord current = expected.get(id);
            StressRecord replacement = new StressRecord(id, random.nextInt(config.groups()), current.version() + 1);
            if (!records.update(Set.of(current), Set.of(replacement))) {
                throw new IllegalStateException("Writer update did not modify id " + id);
            }
            expected.set(id, replacement);
            operations.incrementAndGet();
        }
    }

    private static void readLoop(
            Config config,
            int readerIndex,
            IndexedCollection<StressRecord> records,
            AtomicBoolean stop,
            AtomicLong operations) {
        SplittableRandom random = new SplittableRandom(mixSeed(config.seed(), config.writers() + readerIndex));
        while (!stop.get()) {
            if (random.nextBoolean()) {
                verifyIdRead(records, random.nextInt(config.keySpace()));
            }
            else {
                verifyGroupRead(records, random.nextInt(config.groups()), config.keySpace());
            }
            operations.incrementAndGet();
        }
    }

    private static void verifyIdRead(IndexedCollection<StressRecord> records, int id) {
        Set<StressRecord> seen = new HashSet<>();
        try (ResultSet<StressRecord> resultSet = records.retrieve(equal(StressRecord.ID, id))) {
            for (StressRecord record : resultSet) {
                if (record.id() != id) {
                    throw new IllegalStateException("ID index returned " + record + " for id " + id);
                }
                if (!seen.add(record)) {
                    throw new IllegalStateException("ID index returned the same object twice: " + record);
                }
            }
        }
    }

    private static void verifyGroupRead(IndexedCollection<StressRecord> records, int group, int keySpace) {
        Set<StressRecord> seen = new HashSet<>();
        try (ResultSet<StressRecord> resultSet = records.retrieve(equal(StressRecord.GROUP, group))) {
            for (StressRecord record : resultSet) {
                if (record.group() != group) {
                    throw new IllegalStateException("Group index returned " + record + " for group " + group);
                }
                if (record.id() < 0 || record.id() >= keySpace || !seen.add(record)) {
                    throw new IllegalStateException("Group index returned an invalid or duplicate object: " + record);
                }
            }
        }
    }

    private static String verifyFinalState(
            Config config,
            IndexedCollection<StressRecord> records,
            AtomicReferenceArray<StressRecord> expected) {
        if (records.size() != config.keySpace()) {
            throw new IllegalStateException(
                    "Collection size is " + records.size() + ", expected " + config.keySpace());
        }

        Map<Integer, StressRecord> actualById = new HashMap<>();
        for (StressRecord record : records) {
            StressRecord previous = actualById.put(record.id(), record);
            if (previous != null) {
                throw new IllegalStateException("Collection contains duplicate id " + record.id());
            }
        }
        for (int id = 0; id < config.keySpace(); id++) {
            StressRecord expectedRecord = expected.get(id);
            StressRecord actualRecord = actualById.get(id);
            if (!expectedRecord.equals(actualRecord)) {
                throw new IllegalStateException(
                        "Collection differs from completed writes for id " + id + ": " + actualRecord +
                                " != " + expectedRecord);
            }
            try (ResultSet<StressRecord> resultSet = records.retrieve(equal(StressRecord.ID, id))) {
                List<StressRecord> indexed = materialize(resultSet);
                if (!indexed.equals(List.of(expectedRecord))) {
                    throw new IllegalStateException("ID index differs from collection for id " + id + ": " + indexed);
                }
            }
        }

        for (int group = 0; group < config.groups(); group++) {
            Set<StressRecord> expectedGroup = new HashSet<>();
            for (StressRecord record : actualById.values()) {
                if (record.group() == group) {
                    expectedGroup.add(record);
                }
            }
            try (ResultSet<StressRecord> resultSet = records.retrieve(equal(StressRecord.GROUP, group))) {
                List<StressRecord> indexed = materialize(resultSet);
                if (indexed.size() != new HashSet<>(indexed).size() || !new HashSet<>(indexed).equals(expectedGroup)) {
                    throw new IllegalStateException(
                            "Group index differs from collection for group " + group + ": " + indexed.size() +
                                    " != " + expectedGroup.size());
                }
            }
        }
        return digest(actualById, config.keySpace());
    }

    private static List<StressRecord> materialize(ResultSet<StressRecord> resultSet) {
        List<StressRecord> records = new ArrayList<>();
        for (StressRecord record : resultSet) {
            records.add(record);
        }
        return records;
    }

    private static Throwable stopWorkers(List<Thread> workers) throws InterruptedException {
        joinUntil(workers, STOP_TIMEOUT);
        List<Thread> alive = workers.stream().filter(Thread::isAlive).toList();
        if (alive.isEmpty()) {
            return null;
        }
        alive.forEach(Thread::interrupt);
        joinUntil(alive, INTERRUPT_TIMEOUT);
        List<String> unresponsive = alive.stream().filter(Thread::isAlive).map(Thread::getName).toList();
        return unresponsive.isEmpty()
                ? null
                : new IllegalStateException("Workers did not stop after interrupt: " + unresponsive);
    }

    private static void joinUntil(List<Thread> workers, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread worker : workers) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
            worker.join(millis);
        }
    }

    private static void captureFailure(
            AtomicReference<Throwable> failure,
            AtomicBoolean stop,
            Throwable throwable) {
        Throwable existing = failure.compareAndExchange(null, throwable);
        if (existing != null) {
            synchronized (existing) {
                existing.addSuppressed(throwable);
            }
        }
        stop.set(true);
    }

    private static Throwable firstNonNull(Throwable... failures) {
        for (Throwable failure : failures) {
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private static void addSuppressedIfDistinct(Throwable primary, Throwable candidate) {
        if (candidate != null && candidate != primary) {
            primary.addSuppressed(candidate);
        }
    }

    private static int initialGroup(int id, Config config) {
        return Math.floorMod(Long.hashCode(mixSeed(config.seed(), id)), config.groups());
    }

    private static long mixSeed(long seed, int lane) {
        long value = seed + 0x9e3779b97f4a7c15L * (lane + 1L);
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static String digest(Map<Integer, StressRecord> records, int keySpace) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 2 + Long.BYTES);
            for (int id = 0; id < keySpace; id++) {
                StressRecord record = records.get(id);
                buffer.clear();
                buffer.putInt(record.id()).putInt(record.group()).putLong(record.version());
                digest.update(buffer.array());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Config(long durationMillis, long seed, int writers, int readers, int keySpace, int groups) {

        private static final Set<String> KEYS = Set.of(
                "duration-millis", "seed", "writers", "readers", "key-space", "groups");

        static Config parse(String[] arguments) {
            Map<String, String> values = new HashMap<>();
            for (String argument : arguments) {
                if (!argument.startsWith("--") || !argument.contains("=")) {
                    throw new IllegalArgumentException("Expected --name=value, found: " + argument);
                }
                int separator = argument.indexOf('=');
                String key = argument.substring(2, separator);
                String value = argument.substring(separator + 1);
                if (!KEYS.contains(key) || value.isBlank() || values.put(key, value) != null) {
                    throw new IllegalArgumentException("Unknown, blank or duplicate option: " + argument);
                }
            }
            if (!values.keySet().equals(KEYS)) {
                throw new IllegalArgumentException("Required options are " + KEYS + ", found " + values.keySet());
            }

            long durationMillis = parseLong(values, "duration-millis", 1, TimeUnit.HOURS.toMillis(24));
            long seed = parseLong(values, "seed", Long.MIN_VALUE, Long.MAX_VALUE);
            int writers = parseInt(values, "writers", 1, 64);
            int readers = parseInt(values, "readers", 1, 128);
            int keySpace = parseInt(values, "key-space", writers, 1_000_000);
            int groups = parseInt(values, "groups", 2, keySpace);
            return new Config(durationMillis, seed, writers, readers, keySpace, groups);
        }

        private static int parseInt(Map<String, String> values, String key, int minimum, int maximum) {
            long parsed = parseLong(values, key, minimum, maximum);
            return Math.toIntExact(parsed);
        }

        private static long parseLong(Map<String, String> values, String key, long minimum, long maximum) {
            long parsed;
            try {
                parsed = Long.parseLong(values.get(key));
            }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Option --" + key + " is not an integer", invalid);
            }
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                        "Option --" + key + " must be in [" + minimum + ", " + maximum + "]: " + parsed);
            }
            return parsed;
        }
    }

    private record Report(Config config, long elapsedMillis, long writes, long reads, String finalDigest) {

        String asProperties() {
            return String.format(
                    Locale.ROOT,
                    "formatVersion=1%nstatus=passed%nseed=%d%nduration.requestedMillis=%d%n" +
                            "duration.elapsedMillis=%d%nwriters=%d%nreaders=%d%nkeySpace=%d%ngroups=%d%n" +
                            "operations.writes=%d%noperations.reads=%d%nfinal.size=%d%nfinal.sha256=%s%n",
                    config.seed(),
                    config.durationMillis(),
                    elapsedMillis,
                    config.writers(),
                    config.readers(),
                    config.keySpace(),
                    config.groups(),
                    writes,
                    reads,
                    config.keySpace(),
                    finalDigest);
        }
    }
}
