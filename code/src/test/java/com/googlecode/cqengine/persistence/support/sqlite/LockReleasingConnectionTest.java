// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.persistence.support.sqlite;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.googlecode.cqengine.testutil.TestAssertions.*;

public class LockReleasingConnectionTest {

    @Test
    public void closeClosesTargetAndUnlocksExactlyOnce() throws Exception {
        final AtomicInteger closeCalls = new AtomicInteger();
        CountingLock lock = new CountingLock();
        Connection connection = LockReleasingConnection.wrap(connection(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getName().equals("close")) {
                    closeCalls.incrementAndGet();
                }
                return defaultValue(method.getReturnType());
            }
        }), lock);

        connection.close();
        connection.close();

        assertEquals(1, closeCalls.get());
        assertEquals(1, lock.unlockCalls.get());
    }

    @Test
    public void closeUnlocksAndPreservesTargetFailure() throws Exception {
        final SQLException expected = new SQLException("close failed");
        CountingLock lock = new CountingLock();
        Connection connection = LockReleasingConnection.wrap(connection(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("close")) {
                    throw expected;
                }
                return defaultValue(method.getReturnType());
            }
        }), lock);

        try {
            connection.close();
            fail("Expected close to fail");
        }
        catch (SQLException actual) {
            assertSame(expected, actual);
        }

        assertEquals(1, lock.unlockCalls.get());
        connection.close();
        assertEquals(1, lock.unlockCalls.get());
    }

    @Test
    public void closePreservesTargetFailureAndSuppressesUnlockFailure() throws Exception {
        final SQLException closeFailure = new SQLException("close failed");
        final IllegalStateException unlockFailure = new IllegalStateException("unlock failed");
        Lock lock = new CountingLock() {
            @Override
            public void unlock() {
                throw unlockFailure;
            }
        };
        Connection connection = LockReleasingConnection.wrap(connection(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("close")) {
                    throw closeFailure;
                }
                return defaultValue(method.getReturnType());
            }
        }), lock);

        try {
            connection.close();
            fail("Expected close to fail");
        }
        catch (SQLException actual) {
            assertSame(closeFailure, actual);
            assertArrayEquals(new Throwable[] { unlockFailure }, actual.getSuppressed());
        }
    }

    @Test
    public void delegatedFailuresAreUnwrapped() throws Exception {
        final SQLException checkedFailure = new SQLException("commit failed");
        final IllegalStateException runtimeFailure = new IllegalStateException("read failed");
        CountingLock lock = new CountingLock();
        Connection connection = LockReleasingConnection.wrap(connection(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("commit")) {
                    throw checkedFailure;
                }
                if (method.getName().equals("getAutoCommit")) {
                    throw runtimeFailure;
                }
                return defaultValue(method.getReturnType());
            }
        }), lock);

        try {
            connection.commit();
            fail("Expected commit to fail");
        }
        catch (SQLException actual) {
            assertSame(checkedFailure, actual);
        }
        try {
            connection.getAutoCommit();
            fail("Expected getAutoCommit to fail");
        }
        catch (IllegalStateException actual) {
            assertSame(runtimeFailure, actual);
        }

        assertEquals(0, lock.unlockCalls.get());
        connection.close();
        assertEquals(1, lock.unlockCalls.get());
    }

    @Test
    public void closeAllowsAnotherThreadToAcquireTheReleasedLock() throws Exception {
        final ReentrantLock lock = new ReentrantLock();
        lock.lock();
        Connection connection = LockReleasingConnection.wrap(noOpConnection(), lock);

        connection.close();

        final AtomicBoolean acquired = new AtomicBoolean();
        Thread contender = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    acquired.set(lock.tryLock(5, TimeUnit.SECONDS));
                    if (acquired.get()) {
                        lock.unlock();
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        contender.start();
        contender.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse("Contender did not finish", contender.isAlive());
        assertTrue("Lock was not released", acquired.get());
    }

    @Test
    public void concurrentCloseClosesAndUnlocksExactlyOnce() throws Exception {
        final AtomicInteger closeCalls = new AtomicInteger();
        final CountDownLatch targetCloseEntered = new CountDownLatch(1);
        final CountDownLatch releaseTargetClose = new CountDownLatch(1);
        final CountingLock lock = new CountingLock();
        final Connection connection = LockReleasingConnection.wrap(connection(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getName().equals("close")) {
                    closeCalls.incrementAndGet();
                    targetCloseEntered.countDown();
                    try {
                        if (!releaseTargetClose.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting to release target close");
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return defaultValue(method.getReturnType());
            }
        }), lock);
        final int threadCount = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<Throwable>());
        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        connection.close();
                    }
                    catch (Throwable failure) {
                        failures.add(failure);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        assertTrue("Target close was not invoked", targetCloseEntered.await(5, TimeUnit.SECONDS));
        releaseTargetClose.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse("Close thread did not finish", thread.isAlive());
        }

        assertTrue(failures.toString(), failures.isEmpty());
        assertEquals(1, closeCalls.get());
        assertEquals(1, lock.unlockCalls.get());
    }

    static Connection noOpConnection() {
        return connection(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return defaultValue(method.getReturnType());
            }
        });
    }

    static Connection connection(InvocationHandler invocationHandler) {
        return (Connection) Proxy.newProxyInstance(
                LockReleasingConnectionTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                invocationHandler
        );
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    static class CountingLock implements Lock {
        final AtomicInteger unlockCalls = new AtomicInteger();

        @Override
        public void lock() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lockInterruptibly() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean tryLock() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unlock() {
            unlockCalls.incrementAndGet();
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }
}
