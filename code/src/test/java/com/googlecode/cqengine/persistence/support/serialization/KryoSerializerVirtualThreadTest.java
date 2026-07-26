// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.googlecode.cqengine.persistence.support.serialization.KryoDeserializationMode.REGISTERED_TYPES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KryoSerializerVirtualThreadTest {

    @Test
    @Timeout(30)
    public void virtualThreadsReuseAtMostTheBoundedNumberOfKryoInstances() throws Exception {
        int capacity = KryoSerializer.KRYO_POOL_CAPACITY;
        int taskCount = capacity * 4;
        AtomicInteger createdKryos = new AtomicInteger();
        CountDownLatch writesEntered = new CountDownLatch(capacity);
        CountDownLatch releaseWrites = new CountDownLatch(1);
        Serializer<PoolPojo> blockingSerializer = blockingSerializer(writesEntered, releaseWrites);
        KryoSerializer<PoolPojo> serializer = new KryoSerializer<PoolPojo>(
                PoolPojo.class,
                KryoSerializerSecurityTest.config(
                        REGISTERED_TYPES,
                        false,
                        KryoSerializerSecurityTest.classes(),
                        4096,
                        100,
                        100,
                        100)) {
            @Override
            protected Kryo createKryo(Class<?> objectType) {
                createdKryos.incrementAndGet();
                Kryo kryo = super.createKryo(objectType);
                kryo.getRegistration(PoolPojo.class).setSerializer(blockingSerializer);
                return kryo;
            }
        };

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Integer>> results = new ArrayList<Future<Integer>>(taskCount);
            for (int task = 0; task < taskCount; task++) {
                final int value = task;
                results.add(executor.submit(() -> {
                    byte[] bytes = serializer.serialize(new PoolPojo(value));
                    return serializer.deserialize(bytes).value;
                }));
            }

            assertTrue(
                    writesEntered.await(10, TimeUnit.SECONDS),
                    "The pool did not reach its configured concurrency");
            releaseWrites.countDown();
            for (int task = 0; task < taskCount; task++) {
                assertEquals(task, results.get(task).get(10, TimeUnit.SECONDS).intValue());
            }
            assertEquals(capacity, createdKryos.get());
        }
        finally {
            releaseWrites.countDown();
            executor.shutdownNow();
            assertTrue(
                    executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Virtual-thread executor did not terminate");
        }
    }

    private static Serializer<PoolPojo> blockingSerializer(
            CountDownLatch writesEntered, CountDownLatch releaseWrites) {
        return new Serializer<PoolPojo>() {
            @Override
            public void write(Kryo kryo, Output output, PoolPojo value) {
                writesEntered.countDown();
                try {
                    releaseWrites.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new KryoException("Interrupted while blocking a pool test write", e);
                }
                output.writeInt(value.value);
            }

            @Override
            public PoolPojo read(Kryo kryo, Input input, Class<? extends PoolPojo> type) {
                return new PoolPojo(input.readInt());
            }
        };
    }

    static final class PoolPojo {
        int value;

        PoolPojo() {
        }

        PoolPojo(int value) {
            this.value = value;
        }
    }
}
