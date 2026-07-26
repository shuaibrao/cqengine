// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

import com.esotericsoftware.kryo.util.Util;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertFalse;
import static com.googlecode.cqengine.testutil.TestAssertions.assertNull;

public class KryoUnsafeInitializationTest {

    @Test
    public void initializesSafelyInCleanConsumerProcess() throws Exception {
        Process process = new ProcessBuilder(
                PathToJava.executable(),
                "-cp",
                System.getProperty("java.class.path"),
                Probe.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(output, 0, exitCode);
        assertFalse(output, output.contains("Unsafe"));
    }

    public static class Probe {
        public static void main(String[] args) {
            assertNull(System.getProperty("kryo.unsafe"));
            KryoSerializer<ProbePojo> serializer = new KryoSerializer<ProbePojo>(
                    ProbePojo.class,
                    PersistenceConfig.DEFAULT_CONFIG);

            ProbePojo result = serializer.deserialize(serializer.serialize(new ProbePojo(42)));

            assertEquals(42, result.value);
            assertFalse(Util.isUnsafeAvailable());
            assertNull(System.getProperty("kryo.unsafe"));
        }
    }

    public static class ProbePojo {
        int value;

        public ProbePojo() {
        }

        ProbePojo(int value) {
            this.value = value;
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
