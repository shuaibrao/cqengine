// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.index.support;

import com.googlecode.cqengine.index.support.CloseableRequestResources.CloseableResourceGroup;
import com.googlecode.cqengine.query.option.QueryOptions;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.googlecode.cqengine.testutil.TestAssertions.*;

public class CloseableRequestResourcesTest {

    @Test
    public void externalMonitorsCannotBlockResourceRegistration() throws Exception {
        CloseableRequestResources resources = new CloseableRequestResources();
        CloseableResourceGroup group = resources.addGroup();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            synchronized (resources) {
                Future<?> add = executor.submit(() -> resources.add(new CountingCloseable()));
                add.get(5, TimeUnit.SECONDS);
            }
            synchronized (group) {
                Future<?> add = executor.submit(() -> group.add(new CountingCloseable()));
                add.get(5, TimeUnit.SECONDS);
            }
        }
        finally {
            executor.shutdownNow();
            resources.close();
        }
    }

    @Test
    public void closeWithNoResourcesIsIdempotent() {
        CloseableRequestResources resources = new CloseableRequestResources();

        resources.close();
        resources.close();

        assertTrue(resources.requestResources.isEmpty());
    }

    @Test
    public void closeClosesMultipleGroupsWithoutConcurrentModification() {
        CloseableRequestResources resources = new CloseableRequestResources();
        CountingCloseable first = new CountingCloseable();
        CountingCloseable second = new CountingCloseable();
        resources.addGroup().add(first);
        resources.addGroup().add(second);

        resources.close();

        assertEquals(1, first.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
        assertTrue(resources.requestResources.isEmpty());
    }

    @Test
    public void closeClosesTheSameResourceIdentityOnlyOnce() {
        CloseableRequestResources resources = new CloseableRequestResources();
        CountingCloseable closeable = new CountingCloseable();
        resources.add(closeable);
        resources.add(closeable);

        resources.close();
        resources.close();

        assertEquals(1, closeable.closeCalls.get());
    }

    @Test
    public void closingGroupBeforeParentDoesNotCloseItsResourcesAgain() {
        CloseableRequestResources resources = new CloseableRequestResources();
        CloseableResourceGroup group = resources.addGroup();
        CountingCloseable closeable = new CountingCloseable();
        assertTrue(group.add(closeable));
        assertFalse(group.add(closeable));

        group.close();
        group.close();
        resources.close();

        assertEquals(1, closeable.closeCalls.get());
        assertTrue(resources.requestResources.isEmpty());
    }

    @Test
    public void closedGroupCanBeReusedAndReregistersWithParent() {
        CloseableRequestResources resources = new CloseableRequestResources();
        CloseableResourceGroup group = resources.addGroup();
        CountingCloseable first = new CountingCloseable();
        CountingCloseable second = new CountingCloseable();

        group.add(first);
        group.close();
        group.add(second);
        resources.close();

        assertEquals(1, first.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
    }

    @Test
    public void addWhilePreviousGroupResourcesAreClosingRemainsTracked() throws Exception {
        CloseableRequestResources resources = new CloseableRequestResources();
        CloseableResourceGroup group = resources.addGroup();
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountingCloseable second = new CountingCloseable();
        group.add(() -> {
            closeStarted.countDown();
            try {
                allowClose.await();
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        });

        Thread closeThread = new Thread(group::close);
        closeThread.start();
        try {
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            group.add(second);
        }
        finally {
            allowClose.countDown();
        }
        closeThread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(closeThread.isAlive());
        resources.close();

        assertEquals(1, second.closeCalls.get());
    }

    @Test
    public void addAfterParentCloseClosesTheUntrackableResource() {
        CloseableRequestResources resources = new CloseableRequestResources();
        CloseableResourceGroup group = resources.addGroup();
        CountingCloseable closeable = new CountingCloseable();
        resources.close();

        try {
            group.add(closeable);
            fail("Expected add to fail");
        }
        catch (IllegalStateException expected) {
            assertEquals("Request resources have already been closed", expected.getMessage());
        }

        assertEquals(1, closeable.closeCalls.get());
    }

    @Test
    public void addAfterParentCloseSuppressesUntrackableResourceFailure() {
        CloseableRequestResources resources = new CloseableRequestResources();
        CloseableResourceGroup group = resources.addGroup();
        RuntimeException closeFailure = new RuntimeException("untrackable resource close");
        FailingCloseable closeable = new FailingCloseable(closeFailure);
        resources.close();

        try {
            group.add(closeable);
            fail("Expected add to fail");
        }
        catch (IllegalStateException expected) {
            assertArrayEquals(new Throwable[] { closeFailure }, expected.getSuppressed());
        }

        assertEquals(1, closeable.closeCalls.get());
    }

    @Test
    public void closeAttemptsEveryResourceAndSuppressesLaterFailures() {
        CloseableRequestResources resources = new CloseableRequestResources();
        RuntimeException firstFailure = new RuntimeException("first");
        RuntimeException secondFailure = new RuntimeException("second");
        CountingCloseable successful = new CountingCloseable();
        FailingCloseable first = new FailingCloseable(firstFailure);
        FailingCloseable second = new FailingCloseable(secondFailure);
        resources.add(first);
        resources.add(successful);
        resources.add(second);

        RuntimeException actual = closeExpectingRuntimeFailure(resources);

        assertAggregated(actual, firstFailure, secondFailure);
        assertEquals(1, first.closeCalls.get());
        assertEquals(1, successful.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
        resources.close();
        assertEquals(1, first.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
    }

    @Test
    public void parentCloseAttemptsEveryFailingResourceGroup() {
        CloseableRequestResources resources = new CloseableRequestResources();
        RuntimeException firstFailure = new RuntimeException("first group");
        RuntimeException secondFailure = new RuntimeException("second group");
        FailingCloseable first = new FailingCloseable(firstFailure);
        FailingCloseable second = new FailingCloseable(secondFailure);
        resources.addGroup().add(first);
        resources.addGroup().add(second);

        RuntimeException actual = closeExpectingRuntimeFailure(resources);

        assertAggregated(actual, firstFailure, secondFailure);
        assertEquals(1, first.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
        assertTrue(resources.requestResources.isEmpty());
    }

    @Test
    public void checkedCloseFailureIsWrappedAfterAllResourcesAreClosed() {
        CloseableRequestResources resources = new CloseableRequestResources();
        IOException expected = new IOException("checked failure");
        CountingCloseable successful = new CountingCloseable();
        CheckedFailingCloseable failing = new CheckedFailingCloseable(expected);
        resources.add(failing);
        resources.add(successful);

        RuntimeException actual = closeExpectingRuntimeFailure(resources);

        assertTrue(actual instanceof IllegalStateException);
        assertSame(expected, actual.getCause());
        assertEquals(1, failing.closeCalls.get());
        assertEquals(1, successful.closeCalls.get());
    }

    @Test
    public void closeForQueryOptionsRemovesResourcesEvenWhenCloseFails() {
        QueryOptions queryOptions = new QueryOptions();
        CloseableRequestResources resources = CloseableRequestResources.forQueryOptions(queryOptions);
        RuntimeException firstFailure = new RuntimeException("first");
        RuntimeException secondFailure = new RuntimeException("second");
        FailingCloseable first = new FailingCloseable(firstFailure);
        FailingCloseable second = new FailingCloseable(secondFailure);
        resources.add(first);
        resources.add(second);

        RuntimeException actual;
        try {
            CloseableRequestResources.closeForQueryOptions(queryOptions);
            throw new AssertionError("Expected close to fail");
        }
        catch (RuntimeException failure) {
            actual = failure;
        }

        assertAggregated(actual, firstFailure, secondFailure);
        assertNull(queryOptions.get(CloseableRequestResources.class));
        assertEquals(1, first.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
    }

    static RuntimeException closeExpectingRuntimeFailure(CloseableRequestResources resources) {
        try {
            resources.close();
            throw new AssertionError("Expected close to fail");
        }
        catch (RuntimeException failure) {
            return failure;
        }
    }

    static void assertAggregated(RuntimeException actual, RuntimeException first, RuntimeException second) {
        assertTrue(actual == first || actual == second);
        RuntimeException expectedSuppressed = actual == first ? second : first;
        assertArrayEquals(new Throwable[] { expectedSuppressed }, actual.getSuppressed());
    }

    static class CountingCloseable implements Closeable {
        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    static class FailingCloseable implements Closeable {
        final AtomicInteger closeCalls = new AtomicInteger();
        final RuntimeException failure;

        FailingCloseable(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            throw failure;
        }
    }

    static class CheckedFailingCloseable implements Closeable {
        final AtomicInteger closeCalls = new AtomicInteger();
        final IOException failure;

        CheckedFailingCloseable(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            throw failure;
        }
    }
}
