// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.disk;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.disk.DiskIndex;
import com.googlecode.cqengine.index.sqlite.support.DBUtils;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.CarFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertFalse;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;

public class LegacyDiskPersistenceCompatibilityTest {

    private static final String RESOURCE_ROOT =
            "/com/googlecode/cqengine/persistence/disk/cqengine-3.6.1-java21-legacy.sqlite";
    private static final String EXPECTED_SHA256 =
            "a017cfda6db02bd41cfcb9f3bd49b9d1d064bef87463235cd7434d12c0bb3c1e";

    @Test
    public void readsImmutableUpstreamDatabaseAndIndexes() throws Exception {
        Path directory = Files.createTempDirectory("cqengine-legacy-database-");
        Path database = directory.resolve("legacy.sqlite");
        try {
            restoreFixture(database);
            assertEquals(EXPECTED_SHA256, readResource(RESOURCE_ROOT + ".sha256").trim());
            assertEquals(EXPECTED_SHA256, sha256(Files.readAllBytes(database)));

            Map<String, Set<Integer>> expectedIdsByManufacturer = expectedIdsByManufacturer();
            verifyLegacySchemaAndIndex(database, expectedIdsByManufacturer);
            verifyCurrentReader(database, expectedIdsByManufacturer);
            verifyVersionTwoMigration(database);
        }
        finally {
            Files.deleteIfExists(directory.resolve("legacy.sqlite-wal"));
            Files.deleteIfExists(directory.resolve("legacy.sqlite-shm"));
            Files.deleteIfExists(directory.resolve("legacy.sqlite-journal"));
            Files.deleteIfExists(database);
            Files.deleteIfExists(directory);
        }
    }

    private static void restoreFixture(Path database) throws Exception {
        byte[] compressed = Base64.getMimeDecoder().decode(readResource(RESOURCE_ROOT + ".gz.b64"));
        try (GZIPInputStream input = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            Files.copy(input, database);
        }
    }

    private static void verifyLegacySchemaAndIndex(
            Path database,
            Map<String, Set<Integer>> expectedIdsByManufacturer) throws Exception {
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            try (java.sql.ResultSet result = statement.executeQuery("pragma integrity_check")) {
                assertTrue(result.next());
                assertEquals("ok", result.getString(1));
            }
            try (java.sql.ResultSet result = statement.executeQuery("select count(*) from cqtbl_carId")) {
                assertTrue(result.next());
                assertEquals(50, result.getInt(1));
            }

            Map<String, Set<Integer>> actualIdsByManufacturer = new HashMap<String, Set<Integer>>();
            try (java.sql.ResultSet result = statement.executeQuery(
                    "select value, objectKey from cqtbl_manufacturer order by value, objectKey")) {
                while (result.next()) {
                    actualIdsByManufacturer
                            .computeIfAbsent(result.getString(1), ignored -> new HashSet<Integer>())
                            .add(result.getInt(2));
                }
            }
            assertEquals(expectedIdsByManufacturer, actualIdsByManufacturer);
        }
    }

    private static void verifyCurrentReader(
            Path database,
            Map<String, Set<Integer>> expectedIdsByManufacturer) {
        DiskPersistence<Car, Integer> persistence =
                DiskPersistence.onPrimaryKeyInFile(Car.CAR_ID, database.toFile());
        try {
            IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>(persistence);
            assertEquals(50, cars.size());

            for (Car expected : CarFactory.createCollectionOfCars(50)) {
                try (ResultSet<Car> result = cars.retrieve(equal(Car.CAR_ID, expected.getCarId()))) {
                    assertEquals(1, result.size());
                    assertEquals(expected.toString(), result.uniqueResult().toString());
                }
            }

            cars.addIndex(DiskIndex.onAttribute(Car.MANUFACTURER));
            for (Map.Entry<String, Set<Integer>> expected : expectedIdsByManufacturer.entrySet()) {
                Set<Integer> actualIds = new HashSet<Integer>();
                try (ResultSet<Car> result = cars.retrieve(equal(Car.MANUFACTURER, expected.getKey()))) {
                    for (Car car : result) {
                        actualIds.add(car.getCarId());
                    }
                }
                assertEquals(expected.getValue(), actualIds);
            }
        }
        finally {
            persistence.close();
        }
    }

    private static void verifyVersionTwoMigration(Path database) throws Exception {
        String carIdTable = "cqtbl_" + DBUtils.createSQLiteIndexTableNameV2("carId", "");
        String manufacturerTable = "cqtbl_" + DBUtils.createSQLiteIndexTableNameV2("manufacturer", "");
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            Set<String> tables = new HashSet<String>();
            try (java.sql.ResultSet result = statement.executeQuery(
                    "select name from sqlite_master where type = 'table'")) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
            assertTrue(tables.contains(carIdTable));
            assertTrue(tables.contains(manufacturerTable));
            assertTrue(tables.contains("cqengine_sqlite_identifier_migrations_v2"));
            assertFalse(tables.contains("cqtbl_carId"));
            assertFalse(tables.contains("cqtbl_manufacturer"));

            try (java.sql.ResultSet result = statement.executeQuery(
                    "select count(*) from cqengine_sqlite_identifier_migrations_v2")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
    }

    private static Map<String, Set<Integer>> expectedIdsByManufacturer() {
        Map<String, Set<Integer>> expected = new HashMap<String, Set<Integer>>();
        for (Car car : CarFactory.createCollectionOfCars(50)) {
            expected.computeIfAbsent(car.getManufacturer(), ignored -> new HashSet<Integer>())
                    .add(car.getCarId());
        }
        return expected;
    }

    private static String readResource(String name) throws Exception {
        try (InputStream input = LegacyDiskPersistenceCompatibilityTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
