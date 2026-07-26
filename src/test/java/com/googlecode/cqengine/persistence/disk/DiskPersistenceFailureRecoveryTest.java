// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.persistence.disk;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.query.option.QueryOptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DiskPersistenceFailureRecoveryTest {

    private static final SimpleAttribute<BlobItem, Integer> ID =
            new SimpleAttribute<BlobItem, Integer>(BlobItem.class, Integer.class, "id") {
                @Override
                public Integer getValue(BlobItem object, QueryOptions queryOptions) {
                    return object.id;
                }
            };

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(30)
    public void rollsBackAnUncommittedWalTransactionAfterProcessTermination() throws Exception {
        File database = Files.createFile(temporaryDirectory.resolve("crash-recovery.sqlite")).toFile();
        createDatabase(database, new BlobItem(1, 32), new BlobItem(2, 32));

        Path ready = database.toPath().resolveSibling("writer-ready");
        Path output = database.toPath().resolveSibling("writer-output.txt");
        Process process = startUncommittedWriter(database.toPath(), ready, output);
        try {
            awaitChildWriter(process, ready, output);
            process.destroyForcibly();
            Assertions.assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child process did not terminate");
        }
        finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }

        DiskPersistence<BlobItem, Integer> persistence = DiskPersistence.onPrimaryKeyInFile(ID, database);
        try {
            IndexedCollection<BlobItem> items = new ConcurrentIndexedCollection<BlobItem>(persistence);
            Assertions.assertEquals(2, items.size());
        }
        finally {
            persistence.close();
        }
        assertIntegrityCheck(database);
    }

    @Test
    @Timeout(10)
    public void rejectsCorruptDatabaseWithoutReplacingIt() throws Exception {
        File database = Files.createFile(temporaryDirectory.resolve("corrupt.sqlite")).toFile();
        createDatabase(database, new BlobItem(1, 32));
        byte[] corruptHeader = "not-a-sqlite-db!".getBytes(StandardCharsets.US_ASCII);
        try (RandomAccessFile file = new RandomAccessFile(database, "rw")) {
            file.seek(0L);
            file.write(corruptHeader);
        }
        long corruptLength = database.length();

        RuntimeException failure = openFailure(database);

        assertFailureContains(failure, "not a database", "malformed");
        Assertions.assertEquals(corruptLength, database.length());
        Assertions.assertArrayEquals(
                corruptHeader,
                Arrays.copyOf(Files.readAllBytes(database.toPath()), corruptHeader.length));
    }

    @Test
    @Timeout(10)
    public void rejectsTruncatedDatabaseWithoutRecreatingIt() throws Exception {
        File database = Files.createFile(temporaryDirectory.resolve("truncated.sqlite")).toFile();
        createDatabase(database, new BlobItem(1, 32));
        try (RandomAccessFile file = new RandomAccessFile(database, "rw")) {
            file.setLength(128L);
        }

        RuntimeException failure = openFailure(database);

        assertFailureContains(failure, "malformed", "not a database", "disk image");
        Assertions.assertEquals(128L, database.length());
    }

    @Test
    @Timeout(15)
    public void rollsBackWhenSQLiteReportsDiskFull() throws Exception {
        File database = Files.createFile(temporaryDirectory.resolve("full.sqlite")).toFile();
        PageLimitedDiskPersistence persistence = new PageLimitedDiskPersistence(database);
        try {
            IndexedCollection<BlobItem> items = new ConcurrentIndexedCollection<BlobItem>(persistence);
            Assertions.assertTrue(items.add(new BlobItem(1, 32)));
            persistence.limitGrowth = true;

            RuntimeException failure = null;
            try {
                items.add(new BlobItem(2, 1024 * 1024));
            }
            catch (RuntimeException expected) {
                failure = expected;
            }
            Assertions.assertNotNull(failure, "Expected SQLite to reject growth beyond max_page_count");
            assertFailureContains(failure, "database or disk is full", "SQLITE_FULL");
            Assertions.assertEquals(1, items.size(), "Failed write was not rolled back");

            persistence.limitGrowth = false;
            Assertions.assertTrue(items.add(new BlobItem(3, 32)), "Persistence did not recover after SQLITE_FULL");
            Assertions.assertEquals(2, items.size());
        }
        finally {
            persistence.close();
        }
        assertIntegrityCheck(database);
    }

    @Test
    @Timeout(10)
    public void rejectsReadOnlyStorageWithoutDamagingCommittedData() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("read-only"));
        FileStore fileStore = Files.getFileStore(directory);
        if (!fileStore.supportsFileAttributeView(PosixFileAttributeView.class)) {
            return;
        }
        Path database = directory.resolve("items.sqlite");
        createDatabase(database.toFile(), new BlobItem(1, 32));
        deleteSidecars(database);

        Set<PosixFilePermission> originalDirectoryPermissions = Files.getPosixFilePermissions(directory);
        Set<PosixFilePermission> originalDatabasePermissions = Files.getPosixFilePermissions(database);
        try {
            Files.setPosixFilePermissions(database, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ));
            Files.setPosixFilePermissions(directory, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));

            RuntimeException failure = openOrWriteFailure(database.toFile());
            assertFailureContains(failure, "readonly", "read-only", "permission denied");
        }
        finally {
            Files.setPosixFilePermissions(directory, originalDirectoryPermissions);
            Files.setPosixFilePermissions(database, originalDatabasePermissions);
        }

        DiskPersistence<BlobItem, Integer> reopened =
                DiskPersistence.onPrimaryKeyInFile(ID, database.toFile());
        try {
            Assertions.assertEquals(1, new ConcurrentIndexedCollection<BlobItem>(reopened).size());
        }
        finally {
            reopened.close();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"hold-uncommitted-write".equals(args[0])) {
            throw new IllegalArgumentException("Expected hold-uncommitted-write <database> <ready-file>");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + args[1])) {
            connection.setAutoCommit(false);
            String table = identityTable(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM \"" + table.replace("\"", "\"\"") + "\"");
            }
            Files.writeString(
                    Path.of(args[2]),
                    "ready\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    private Process startUncommittedWriter(Path database, Path ready, Path output) throws Exception {
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", javaName).toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                DiskPersistenceFailureRecoveryTest.class.getName(),
                "hold-uncommitted-write",
                database.toString(),
                ready.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
    }

    private static void awaitChildWriter(Process process, Path ready, Path output) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (!Files.isRegularFile(ready) && process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        if (!Files.isRegularFile(ready)) {
            String childOutput = Files.isRegularFile(output) ? Files.readString(output) : "<no output>";
            Assertions.fail("Child writer did not reach its uncommitted transaction: " + childOutput);
        }
    }

    private static void createDatabase(File database, BlobItem... items) {
        DiskPersistence<BlobItem, Integer> persistence = DiskPersistence.onPrimaryKeyInFile(ID, database);
        try {
            IndexedCollection<BlobItem> collection = new ConcurrentIndexedCollection<BlobItem>(persistence);
            Assertions.assertTrue(collection.addAll(Arrays.asList(items)));
        }
        finally {
            persistence.close();
        }
    }

    private static RuntimeException openFailure(File database) {
        DiskPersistence<BlobItem, Integer> persistence = DiskPersistence.onPrimaryKeyInFile(ID, database);
        try {
            new ConcurrentIndexedCollection<BlobItem>(persistence);
            Assertions.fail("Expected corrupt persistence to fail closed");
            throw new AssertionError("unreachable");
        }
        catch (RuntimeException expected) {
            return expected;
        }
        finally {
            persistence.close();
        }
    }

    private static RuntimeException openOrWriteFailure(File database) {
        DiskPersistence<BlobItem, Integer> persistence = DiskPersistence.onPrimaryKeyInFile(ID, database);
        try {
            IndexedCollection<BlobItem> items = new ConcurrentIndexedCollection<BlobItem>(persistence);
            items.add(new BlobItem(2, 32));
            Assertions.fail("Expected read-only persistence to reject initialization or writes");
            throw new AssertionError("unreachable");
        }
        catch (RuntimeException expected) {
            return expected;
        }
        finally {
            persistence.close();
        }
    }

    private static String identityTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'cqtbl_%' ORDER BY name")) {
            Assertions.assertTrue(result.next(), "No CQEngine identity table found");
            String table = result.getString(1);
            Assertions.assertFalse(result.next(), "Unexpected additional CQEngine table");
            return table;
        }
    }

    private static void assertIntegrityCheck(File database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            Assertions.assertTrue(result.next());
            Assertions.assertEquals("ok", result.getString(1));
            Assertions.assertFalse(result.next());
        }
    }

    private static void assertFailureContains(Throwable failure, String... expectedFragments) {
        Throwable current = failure;
        StringBuilder messages = new StringBuilder();
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        String normalized = messages.toString().toLowerCase(java.util.Locale.ROOT);
        for (String fragment : expectedFragments) {
            if (normalized.contains(fragment.toLowerCase(java.util.Locale.ROOT))) {
                return;
            }
        }
        throw new AssertionError("Failure did not contain any of " + Arrays.toString(expectedFragments), failure);
    }

    private static void deleteSidecars(Path database) throws Exception {
        Files.deleteIfExists(Path.of(database + "-wal"));
        Files.deleteIfExists(Path.of(database + "-shm"));
        Files.deleteIfExists(Path.of(database + "-journal"));
    }

    public static final class BlobItem {
        int id;
        byte[] payload;

        public BlobItem() {
        }

        BlobItem(int id, int payloadSize) {
            this.id = id;
            this.payload = new byte[payloadSize];
        }
    }

    private static final class PageLimitedDiskPersistence extends DiskPersistence<BlobItem, Integer> {
        volatile boolean limitGrowth;

        PageLimitedDiskPersistence(File database) {
            super(ID, database, new java.util.Properties());
        }

        @Override
        protected Connection getConnectionWithoutRWLock(Index<?> index, QueryOptions queryOptions) {
            Connection connection = super.getConnectionWithoutRWLock(index, queryOptions);
            if (!limitGrowth) {
                return connection;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet pageCount = statement.executeQuery("PRAGMA page_count")) {
                Assertions.assertTrue(pageCount.next());
                long pages = pageCount.getLong(1);
                try (ResultSet maximum = statement.executeQuery("PRAGMA max_page_count = " + pages)) {
                    Assertions.assertTrue(maximum.next());
                    Assertions.assertEquals(pages, maximum.getLong(1));
                }
                return connection;
            }
            catch (SQLException | RuntimeException failure) {
                try {
                    connection.close();
                }
                catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw new IllegalStateException("Failed to limit the SQLite test database", failure);
            }
        }
    }
}
