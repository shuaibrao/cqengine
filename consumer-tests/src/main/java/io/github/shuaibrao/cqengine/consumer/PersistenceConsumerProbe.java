// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package io.github.shuaibrao.cqengine.consumer;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import com.googlecode.cqengine.persistence.offheap.OffHeapPersistence;
import com.googlecode.cqengine.resultset.ResultSet;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.simpleAttribute;

public final class PersistenceConsumerProbe {

    private static final SimpleAttribute<ConsumerItem, Integer> ID = simpleAttribute(
            ConsumerItem.class,
            Integer.class,
            "id",
            item -> item.id);

    private PersistenceConsumerProbe() {
    }

    public static void main(String[] args) throws Exception {
        File cqengineJar = ConsumerAssertions.verifyCqengineArtifact();
        ConsumerAssertions.verifyJvmArguments(true);
        SQLiteNativeEvidence nativeEvidence = verifyJdbcServiceAndSQLite(cqengineJar);
        verifyDiskCreateCloseReopen();
        verifyOffHeapPersistence();
        writeNativeEvidence(nativeEvidence);
        System.out.println("persistence-consumer=ok mode=" + System.getProperty("consumer.artifactMode")
                + " java=" + Runtime.version().feature() + " sqlite=" + nativeEvidence.sqliteVersion()
                + " artifact=" + cqengineJar.getName());
    }

    private static SQLiteNativeEvidence verifyJdbcServiceAndSQLite(File cqengineJar) throws Exception {
        ConsumerAssertions.require(System.getProperty("org.sqlite.lib.path") == null,
                "Consumer probe must exercise extraction instead of org.sqlite.lib.path");
        ConsumerAssertions.require(System.getProperty("org.sqlite.lib.name") == null,
                "Consumer probe must exercise the driver's platform-selected native name");
        Path extractionDirectory = Path.of(ConsumerAssertions.requiredProperty("org.sqlite.tmpdir"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(extractionDirectory);
        try (var entries = Files.list(extractionDirectory)) {
            ConsumerAssertions.require(entries.findAny().isEmpty(),
                    "SQLite extraction directory was not isolated and empty: " + extractionDirectory);
        }

        Class<? extends Driver> sqliteDriver = ServiceLoader.load(Driver.class).stream()
                .map(ServiceLoader.Provider::type)
                .filter(type -> type.getName().equals("org.sqlite.JDBC"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SQLite JDBC service provider was not discoverable"));

        File sqliteCodeSource = new File(
                sqliteDriver.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();
        String expectedSqliteModule = "all".equals(System.getProperty("consumer.artifactMode"))
                ? "cqengine"
                : "org.xerial.sqlitejdbc";
        if ("module".equals(System.getProperty("consumer.launchMode"))) {
            ConsumerAssertions.require(expectedSqliteModule.equals(sqliteDriver.getModule().getName()),
                    "SQLite resolved in the wrong module: " + sqliteDriver.getModule());
        }
        if ("all".equals(System.getProperty("consumer.artifactMode"))) {
            ConsumerAssertions.require(sqliteCodeSource.equals(cqengineJar),
                    "All consumer loaded SQLite from a second artifact: " + sqliteCodeSource);
        }
        else {
            ConsumerAssertions.require(!sqliteCodeSource.equals(cqengineJar)
                            && sqliteCodeSource.getName().startsWith("sqlite-jdbc-"),
                    "Thin consumer did not load SQLite from its transitive driver JAR: " + sqliteCodeSource);
        }

        ClassLoader sqliteClassLoader = sqliteDriver.getClassLoader();
        String nativeFolder = invokeSQLiteString(
                sqliteClassLoader,
                "org.sqlite.util.OSInfo",
                "getNativeLibFolderPathForCurrentOS");
        String nativeLibraryName = System.mapLibraryName("sqlitejdbc");
        String nativeResource = "org/sqlite/native/" + nativeFolder + "/" + nativeLibraryName;
        String driverVersion = invokeSQLiteString(
                sqliteClassLoader,
                "org.sqlite.SQLiteJDBCLoader",
                "getVersion");
        ConsumerAssertions.require("3.53.2.1".equals(driverVersion),
                "Expected sqlite-jdbc 3.53.2.1, found " + driverVersion);

        List<URL> nativeResources = new ArrayList<URL>();
        Enumeration<URL> resourceEnumeration = sqliteClassLoader.getResources(nativeResource);
        while (resourceEnumeration.hasMoreElements()) {
            nativeResources.add(resourceEnumeration.nextElement());
        }
        ConsumerAssertions.require(nativeResources.size() == 1,
                "Expected exactly one current-platform SQLite native resource " + nativeResource
                        + ", found " + nativeResources);
        String resourceSha256 = sha256FromJar(sqliteCodeSource, nativeResource);

        String version;
        String integrity;
        SortedSet<String> compileOptions = new TreeSet<String>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            try (java.sql.ResultSet result = statement.executeQuery("select sqlite_version()")) {
                ConsumerAssertions.require(result.next(), "SQLite version query returned no row");
                version = result.getString(1);
                ConsumerAssertions.require(!result.next(), "SQLite version query returned more than one row");
            }
            ConsumerAssertions.require("3.53.2".equals(version),
                    "Expected SQLite 3.53.2, found " + version);

            try (java.sql.ResultSet result = statement.executeQuery("pragma integrity_check")) {
                ConsumerAssertions.require(result.next(), "SQLite integrity check returned no row");
                integrity = result.getString(1);
                ConsumerAssertions.require("ok".equals(integrity),
                        "SQLite integrity check failed: " + integrity);
                ConsumerAssertions.require(!result.next(), "SQLite integrity check returned more than one row");
            }

            try (java.sql.ResultSet result = statement.executeQuery("pragma compile_options")) {
                while (result.next()) {
                    compileOptions.add(result.getString(1));
                }
            }
            ConsumerAssertions.require(compileOptions.contains("ENABLE_FTS5"),
                    "SQLite was not compiled with FTS5: " + compileOptions);
            ConsumerAssertions.require(compileOptions.contains("THREADSAFE=1"),
                    "SQLite does not report THREADSAFE=1: " + compileOptions);
        }

        boolean nativeLoaded = invokeSQLiteBoolean(
                sqliteClassLoader,
                "org.sqlite.SQLiteJDBCLoader",
                "isNativeMode");
        ConsumerAssertions.require(nativeLoaded, "SQLite JDBC did not report native mode after opening a connection");

        List<Path> extractedFiles;
        try (var entries = Files.list(extractionDirectory)) {
            extractedFiles = entries
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith("-" + nativeLibraryName))
                    .toList();
        }
        ConsumerAssertions.require(extractedFiles.size() == 1,
                "Expected one extracted current-platform SQLite native, found " + extractedFiles);
        Path extractedNative = extractedFiles.get(0);
        Path extractionLock = extractedNative.resolveSibling(extractedNative.getFileName() + ".lck");
        ConsumerAssertions.require(Files.isRegularFile(extractionLock),
                "SQLite extraction lock was not created beside the native library");
        try (var entries = Files.list(extractionDirectory)) {
            ConsumerAssertions.require(entries.count() == 2,
                    "SQLite extraction produced files other than the native library and its lock");
        }
        String extractedSha256 = sha256(Files.newInputStream(extractedNative));
        ConsumerAssertions.require(resourceSha256.equals(extractedSha256),
                "Extracted SQLite native differs from " + nativeResource);

        String compileOptionsSha256 = sha256(String.join("\n", compileOptions));
        System.out.println("sqlite-native=verified os=" + System.getProperty("os.name")
                + " arch=" + System.getProperty("os.arch") + " resource=" + nativeResource
                + " extracted=true loaded=true integrity=" + integrity);
        System.out.println("sqlite-compile-options=" + String.join(",", compileOptions));
        return new SQLiteNativeEvidence(
                driverVersion,
                version,
                integrity,
                nativeFolder,
                nativeResource,
                resourceSha256,
                extractedSha256,
                compileOptions.size(),
                compileOptionsSha256,
                sqliteDriver.getModule().getName() == null ? "unnamed" : sqliteDriver.getModule().getName(),
                sqliteCodeSource.getName());
    }

    private static String invokeSQLiteString(
            ClassLoader classLoader,
            String className,
            String methodName) throws Exception {
        Method method = Class.forName(className, true, classLoader).getMethod(methodName);
        return (String) method.invoke(null);
    }

    private static boolean invokeSQLiteBoolean(
            ClassLoader classLoader,
            String className,
            String methodName) throws Exception {
        Method method = Class.forName(className, true, classLoader).getMethod(methodName);
        return (Boolean) method.invoke(null);
    }

    private static String sha256FromJar(File jarFile, String resourceName) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(resourceName);
            ConsumerAssertions.require(entry != null,
                    "SQLite code source does not contain current-platform native: " + resourceName);
            try (InputStream input = jar.getInputStream(entry)) {
                return sha256(input);
            }
        }
    }

    private static String sha256(String value) throws Exception {
        return sha256(new java.io.ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(InputStream input) throws Exception {
        try (InputStream closeable = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            while (true) {
                int count = closeable.read(buffer);
                if (count < 0) {
                    break;
                }
                digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }

    private static void writeNativeEvidence(SQLiteNativeEvidence evidence) throws Exception {
        Path output = Path.of(ConsumerAssertions.requiredProperty("consumer.nativeEvidenceFile"))
                .toAbsolutePath()
                .normalize();
        SortedMap<String, String> values = new TreeMap<String, String>();
        values.put("artifact.mode", ConsumerAssertions.requiredProperty("consumer.artifactMode"));
        values.put("java.feature", Integer.toString(Runtime.version().feature()));
        values.put("launch.mode", ConsumerAssertions.requiredProperty("consumer.launchMode"));
        values.put("native.extracted", "true");
        values.put("native.extracted.sha256", evidence.extractedSha256());
        values.put("native.folder", evidence.nativeFolder());
        values.put("native.loaded", "true");
        values.put("native.resource", evidence.nativeResource());
        values.put("native.resource.sha256", evidence.resourceSha256());
        values.put("os.arch", System.getProperty("os.arch"));
        values.put("os.name", System.getProperty("os.name"));
        values.put("sqlite.compile-option.ENABLE_FTS5", "true");
        values.put("sqlite.compile-option.THREADSAFE_1", "true");
        values.put("sqlite.compile-options.count", Integer.toString(evidence.compileOptionsCount()));
        values.put("sqlite.compile-options.sha256", evidence.compileOptionsSha256());
        values.put("sqlite.driver.artifact", evidence.sqliteDriverArtifact());
        values.put("sqlite.driver.module", evidence.sqliteDriverModule());
        values.put("sqlite.driver.version", evidence.sqliteDriverVersion());
        values.put("sqlite.integrity", evidence.integrity());
        values.put("sqlite.version", evidence.sqliteVersion());
        values.put("status", "verified");

        StringBuilder report = new StringBuilder();
        for (var entry : values.entrySet()) {
            ConsumerAssertions.require(entry.getValue() != null
                            && !entry.getValue().contains("\n")
                            && !entry.getValue().contains("\r"),
                    "Native evidence contains an unsafe value for " + entry.getKey());
            report.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, report, StandardCharsets.UTF_8);
        try {
            Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record SQLiteNativeEvidence(
            String sqliteDriverVersion,
            String sqliteVersion,
            String integrity,
            String nativeFolder,
            String nativeResource,
            String resourceSha256,
            String extractedSha256,
            int compileOptionsCount,
            String compileOptionsSha256,
            String sqliteDriverModule,
            String sqliteDriverArtifact) {
    }

    private static void verifyDiskCreateCloseReopen() throws Exception {
        Path directory = Files.createTempDirectory("cqengine-external-disk-");
        File database = directory.resolve("items.db").toFile();
        try {
            DiskPersistence<ConsumerItem, Integer> persistence =
                    DiskPersistence.onPrimaryKeyInFile(ID, database);
            try {
                IndexedCollection<ConsumerItem> items =
                        new ConcurrentIndexedCollection<ConsumerItem>(persistence);
                items.add(new ConsumerItem(11, "eleven"));
                items.add(new ConsumerItem(12, "twelve"));
                try (ResultSet<ConsumerItem> results = items.retrieve(equal(ID, 12))) {
                    ConsumerAssertions.require(results.size() == 1,
                            "Disk persistence query returned the wrong result");
                }
            }
            finally {
                persistence.close();
            }

            DiskPersistence<ConsumerItem, Integer> reopened =
                    DiskPersistence.onPrimaryKeyInFile(ID, database);
            try {
                IndexedCollection<ConsumerItem> items =
                        new ConcurrentIndexedCollection<ConsumerItem>(reopened);
                ConsumerAssertions.require(items.size() == 2,
                        "Reopened disk persistence lost objects");
                try (ResultSet<ConsumerItem> results = items.retrieve(equal(ID, 11))) {
                    ConsumerAssertions.require(results.size() == 1
                                    && results.uniqueResult().equals(new ConsumerItem(11, "eleven")),
                            "Reopened disk persistence returned the wrong object");
                }
            }
            finally {
                reopened.close();
            }
        }
        finally {
            Files.deleteIfExists(directory.resolve("items.db-wal"));
            Files.deleteIfExists(directory.resolve("items.db-shm"));
            Files.deleteIfExists(database.toPath());
            Files.deleteIfExists(directory);
        }
    }

    private static void verifyOffHeapPersistence() {
        OffHeapPersistence<ConsumerItem, Integer> persistence = OffHeapPersistence.onPrimaryKey(ID);
        try {
            IndexedCollection<ConsumerItem> items = new ConcurrentIndexedCollection<ConsumerItem>(persistence);
            items.add(new ConsumerItem(21, "twenty-one"));
            try (ResultSet<ConsumerItem> results = items.retrieve(equal(ID, 21))) {
                ConsumerAssertions.require(results.size() == 1,
                        "Off-heap persistence query returned the wrong result");
            }
        }
        finally {
            persistence.close();
        }
    }
}
