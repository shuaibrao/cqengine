/**
 * Copyright 2026 Shuaib Rao
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
package com.googlecode.cqengine.persistence.disk;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.disk.DiskIndex;
import com.googlecode.cqengine.index.sqlite.SQLiteBusyException;
import com.googlecode.cqengine.index.sqlite.support.DBQueries;
import com.googlecode.cqengine.index.sqlite.support.DBUtils;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.CarFactory;
import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.sqlite.SQLiteErrorCode;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;

public class DiskPersistenceWriterContentionTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void collectionUpdatesFailBoundedlyAndRecoverAfterWriterContention() throws Exception {
        final int busyTimeoutMillis = 25;
        final int writerCount = 3;
        Properties properties = new Properties();
        properties.setProperty(
                DiskPersistence.BUSY_TIMEOUT_PROPERTY,
                String.valueOf(busyTimeoutMillis));
        File persistenceFile = DiskPersistence.createTempFile();
        DiskPersistence<Car, Integer> persistence =
                DiskPersistence.onPrimaryKeyInFileWithProperties(
                        Car.CAR_ID, persistenceFile, properties);
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<Car>(persistence);
        cars.addIndex(DiskIndex.onAttribute(Car.MANUFACTURER));
        cars.addAll(CarFactory.createCollectionOfCars(100));
        Map<String, Integer> expectedManufacturerCounts = manufacturerCounts(100);

        Connection lockHolder = null;
        ExecutorService writers = Executors.newFixedThreadPool(writerCount);
        try {
            lockHolder = persistence.getConnection(null, noQueryOptions());
            DBQueries.createIndexTable(
                    "writer_contention_test", Integer.class, String.class, lockHolder);
            lockHolder.setAutoCommit(false);
            TestAssertions.assertEquals(
                    1,
                    DBQueries.bulkAdd(
                            Collections.singletonList(
                                    new DBQueries.Row<Integer, String>(1, "lock-holder")),
                            "writer_contention_test",
                            lockHolder));

            CountDownLatch ready = new CountDownLatch(writerCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<BusyFailure>> failures = new ArrayList<Future<BusyFailure>>();
            for (int id = 0; id < writerCount; id++) {
                Car car = CarFactory.createCar(id);
                failures.add(writers.submit(() -> {
                    ready.countDown();
                    TestAssertions.assertTrue(start.await(2, TimeUnit.SECONDS));
                    long startedNanos = System.nanoTime();
                    try {
                        cars.update(Collections.singleton(car), Collections.singleton(car));
                        throw new AssertionError("Contended collection update unexpectedly succeeded");
                    }
                    catch (SQLiteBusyException expected) {
                        return new BusyFailure(
                                expected,
                                TimeUnit.NANOSECONDS.toMillis(
                                        System.nanoTime() - startedNanos));
                    }
                }));
            }

            TestAssertions.assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            for (Future<BusyFailure> failure : failures) {
                BusyFailure busyFailure = failure.get(2, TimeUnit.SECONDS);
                TestAssertions.assertEquals(
                        SQLiteErrorCode.SQLITE_BUSY.code,
                        busyFailure.exception.getPrimaryErrorCode());
                TestAssertions.assertTrue(
                        "Busy failure returned too early: " + busyFailure.elapsedMillis,
                        busyFailure.elapsedMillis >= 10);
                TestAssertions.assertTrue(
                        "Busy failure exceeded its bound: " + busyFailure.elapsedMillis,
                        busyFailure.elapsedMillis < 2000);
            }
            assertCollectionAndIndexUnchanged(cars, expectedManufacturerCounts);

            lockHolder.rollback();
            lockHolder.setAutoCommit(true);
            lockHolder.close();
            lockHolder = null;

            for (int id = 0; id < writerCount; id++) {
                Car car = CarFactory.createCar(id);
                TestAssertions.assertTrue(cars.update(
                        Collections.singleton(car),
                        Collections.singleton(car)));
                TestAssertions.assertTrue(cars.contains(car));
                try (ResultSet<Car> matchingManufacturer = cars.retrieve(
                        equal(Car.MANUFACTURER, car.getManufacturer()))) {
                    TestAssertions.assertTrue(matchingManufacturer.contains(car));
                }
            }
            assertCollectionAndIndexUnchanged(cars, expectedManufacturerCounts);
        }
        finally {
            writers.shutdownNow();
            try {
                TestAssertions.assertTrue(writers.awaitTermination(2, TimeUnit.SECONDS));
            }
            finally {
                if (lockHolder != null) {
                    DBUtils.rollback(lockHolder);
                    DBUtils.closeQuietly(lockHolder);
                }
                try {
                    persistence.close();
                }
                finally {
                    TestAssertions.assertTrue(
                            "Failed to delete temp file: " + persistenceFile,
                            persistenceFile.delete());
                }
            }
        }
    }

    private static Map<String, Integer> manufacturerCounts(int numberOfCars) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (int id = 0; id < numberOfCars; id++) {
            String manufacturer = CarFactory.createCar(id).getManufacturer();
            Integer count = counts.get(manufacturer);
            counts.put(manufacturer, count == null ? 1 : count + 1);
        }
        return counts;
    }

    private static void assertCollectionAndIndexUnchanged(
            IndexedCollection<Car> cars,
            Map<String, Integer> expectedManufacturerCounts) {
        TestAssertions.assertEquals(100, cars.size());
        for (int id = 0; id < 100; id++) {
            TestAssertions.assertTrue(cars.contains(CarFactory.createCar(id)));
        }
        for (Map.Entry<String, Integer> entry : expectedManufacturerCounts.entrySet()) {
            try (ResultSet<Car> matchingManufacturer = cars.retrieve(
                    equal(Car.MANUFACTURER, entry.getKey()))) {
                TestAssertions.assertEquals(entry.getValue().intValue(), matchingManufacturer.size());
            }
        }
    }

    static final class BusyFailure {
        final SQLiteBusyException exception;
        final long elapsedMillis;

        BusyFailure(SQLiteBusyException exception, long elapsedMillis) {
            this.exception = exception;
            this.elapsedMillis = elapsedMillis;
        }
    }
}
