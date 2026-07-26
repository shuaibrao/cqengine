// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0

package com.googlecode.cqengine.persistence.support.serialization;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.TreeSet;

import static com.googlecode.cqengine.persistence.support.serialization.KryoDeserializationMode.REGISTERED_TYPES;
import static com.googlecode.cqengine.testutil.TestAssertions.assertArrayEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertNull;
import static com.googlecode.cqengine.testutil.TestAssertions.assertSame;

public class KryoSerializerCompatibilityTest {

    private static final String RESOURCE =
            "/com/googlecode/cqengine/persistence/support/serialization/"
                    + "kryo-5.0.0-rc1-java21-wrapper-fixture.b64";
    private static final String EXPECTED_SHA256 =
            "0db3cb2f6ab435ae9268439e6471665851bd4cf3bf858b84d2aadd26cb497dc5";

    @Test
    public void readsImmutableLegacyFixture() throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(readResource(RESOURCE));
        assertEquals(EXPECTED_SHA256, readResource(RESOURCE + ".sha256").trim());
        assertEquals(EXPECTED_SHA256, sha256(bytes));

        KryoSerializerCompatibilityFixture fixture = serializer().deserialize(bytes);

        KryoSerializerCompatibilityFixtureTestSupport.assertFixture(fixture);
    }

    @Test
    public void roundTripsSupportedCollectionWrappers() {
        KryoSerializerCompatibilityFixture original = KryoSerializerCompatibilityFixture.create();
        KryoSerializer<KryoSerializerCompatibilityFixture> serializer = serializer();

        KryoSerializerCompatibilityFixture fixture = serializer.deserialize(serializer.serialize(original));

        KryoSerializerCompatibilityFixtureTestSupport.assertFixture(fixture);
    }

    @Test
    public void roundTripsRepresentativeObjectGraphsInBothTrustModes() {
        RepresentativeGraph original = RepresentativeGraph.create();
        assertRepresentativeGraph(roundTrip(
                original,
                new KryoSerializer<RepresentativeGraph>(
                        RepresentativeGraph.class,
                        PersistenceConfig.DEFAULT_CONFIG)));

        PersistenceConfig registeredConfig = KryoSerializerSecurityTest.config(
                REGISTERED_TYPES,
                false,
                KryoSerializerSecurityTest.classes(
                        RepresentativeNode.class,
                        SampleStatus.class,
                        Date.class,
                        SampleRecord.class,
                        ReverseStringComparator.class),
                16_384,
                100,
                100,
                1_000);
        assertRepresentativeGraph(roundTrip(
                original,
                new KryoSerializer<RepresentativeGraph>(RepresentativeGraph.class, registeredConfig)));
    }

    private static RepresentativeGraph roundTrip(
            RepresentativeGraph original,
            KryoSerializer<RepresentativeGraph> serializer) {
        return serializer.deserialize(serializer.serialize(original));
    }

    private static void assertRepresentativeGraph(RepresentativeGraph result) {
        assertNull(result.nullableValue);
        assertEquals(SampleStatus.ACTIVE, result.status);
        assertEquals(new Date(1_700_000_000_123L), result.date);
        assertArrayEquals(new int[] { 3, 1, 4 }, result.numbers);
        assertEquals(new SampleRecord("record", 17), result.record);
        TreeSet<String> expectedValues = new TreeSet<String>(new ReverseStringComparator());
        expectedValues.add("alpha");
        expectedValues.add("beta");
        assertEquals(expectedValues, result.sortedValues);
        assertEquals(ReverseStringComparator.class, result.sortedValues.comparator().getClass());
        assertEquals("root", result.node.name);
        assertSame(result.node, result.alias);
        assertSame(result.node, result.node.next);
    }

    private static KryoSerializer<KryoSerializerCompatibilityFixture> serializer() {
        return new KryoSerializer<KryoSerializerCompatibilityFixture>(
                KryoSerializerCompatibilityFixture.class,
                PersistenceConfig.DEFAULT_CONFIG);
    }

    enum SampleStatus {
        ACTIVE
    }

    record SampleRecord(String label, int value) {
    }

    static final class ReverseStringComparator implements Comparator<String> {
        @Override
        public int compare(String first, String second) {
            return second.compareTo(first);
        }
    }

    static final class RepresentativeNode {
        String name;
        RepresentativeNode next;
    }

    static final class RepresentativeGraph {
        String nullableValue;
        SampleStatus status;
        Date date;
        int[] numbers;
        SampleRecord record;
        TreeSet<String> sortedValues;
        RepresentativeNode node;
        RepresentativeNode alias;

        static RepresentativeGraph create() {
            RepresentativeGraph graph = new RepresentativeGraph();
            graph.nullableValue = null;
            graph.status = SampleStatus.ACTIVE;
            graph.date = new Date(1_700_000_000_123L);
            graph.numbers = new int[] { 3, 1, 4 };
            graph.record = new SampleRecord("record", 17);
            graph.sortedValues = new TreeSet<String>(new ReverseStringComparator());
            graph.sortedValues.add("alpha");
            graph.sortedValues.add("beta");
            graph.node = new RepresentativeNode();
            graph.node.name = "root";
            graph.node.next = graph.node;
            graph.alias = graph.node;
            return graph;
        }
    }

    static String readResource(String name) throws IOException {
        InputStream input = KryoSerializerCompatibilityTest.class.getResourceAsStream(name);
        if (input == null) {
            throw new IllegalStateException("Missing test resource: " + name);
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
