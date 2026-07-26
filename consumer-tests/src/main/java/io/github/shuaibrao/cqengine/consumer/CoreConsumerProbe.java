// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package io.github.shuaibrao.cqengine.consumer;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.codegen.AttributeBytecodeGenerator;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.persistence.support.serialization.KryoDeserializationMode;
import com.googlecode.cqengine.persistence.support.serialization.KryoSerializer;
import com.googlecode.cqengine.persistence.support.serialization.PersistenceConfig;
import com.googlecode.cqengine.query.parser.cqn.CQNParser;
import com.googlecode.cqengine.resultset.ResultSet;

import java.io.File;
import java.util.Map;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.simpleAttribute;

public final class CoreConsumerProbe {

    private static final SimpleAttribute<ConsumerItem, Integer> ID = simpleAttribute(
            ConsumerItem.class,
            Integer.class,
            "id",
            item -> item.id);

    private CoreConsumerProbe() {
    }

    public static void main(String[] args) throws Exception {
        File cqengineJar = ConsumerAssertions.verifyCqengineArtifact();
        ConsumerAssertions.verifyJvmArguments(false);
        verifyCoreQueryAndExplicitLambda();
        verifyParserAndJavassist();
        verifyKryoModes();
        System.out.println("core-consumer=ok mode=" + System.getProperty("consumer.artifactMode")
                + " java=" + Runtime.version().feature() + " artifact=" + cqengineJar.getName());
    }

    private static void verifyCoreQueryAndExplicitLambda() {
        IndexedCollection<ConsumerItem> items = new ConcurrentIndexedCollection<ConsumerItem>();
        items.addIndex(HashIndex.onAttribute(ID));
        items.add(new ConsumerItem(1, "alpha"));
        items.add(new ConsumerItem(2, "beta"));

        try (ResultSet<ConsumerItem> results = items.retrieve(equal(ID, 2))) {
            ConsumerAssertions.require(results.size() == 1, "Indexed query returned the wrong size");
            ConsumerAssertions.require(results.uniqueResult().equals(new ConsumerItem(2, "beta")),
                    "Indexed query returned the wrong object");
        }

        items.add(new ConsumerItem(3, "gamma"));
        try (ResultSet<ConsumerItem> results = items.retrieve(equal(ID, 3))) {
            ConsumerAssertions.require(results.size() == 1,
                    "Collection was unusable after the first ResultSet was closed");
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyParserAndJavassist() {
        Map<String, ? extends Attribute<ConsumerItem, ?>> attributes =
                AttributeBytecodeGenerator.createAttributes(ConsumerItem.class);
        ConsumerAssertions.require(attributes.keySet().containsAll(java.util.Set.of("id", "name")),
                "Javassist did not generate the expected attributes: " + attributes.keySet());
        Attribute<ConsumerItem, Integer> generatedId =
                (Attribute<ConsumerItem, Integer>) attributes.get("id");
        ConsumerAssertions.require(generatedId.getClass().getClassLoader() == ConsumerItem.class.getClassLoader(),
                "Generated attribute was not defined in the application class loader");
        ConsumerAssertions.require(generatedId.getValues(new ConsumerItem(7, "seven"), null).iterator().next() == 7,
                "Generated attribute returned the wrong value");

        CQNParser<ConsumerItem> parser = CQNParser.forPojoWithAttributes(ConsumerItem.class, attributes);
        IndexedCollection<ConsumerItem> items = new ConcurrentIndexedCollection<ConsumerItem>();
        items.add(new ConsumerItem(7, "seven"));
        items.add(new ConsumerItem(8, "eight"));
        try (ResultSet<ConsumerItem> results = parser.retrieve(items, "equal(\"name\", \"seven\")")) {
            ConsumerAssertions.require(results.size() == 1 && results.uniqueResult().id == 7,
                    "CQN parser returned the wrong result");
        }
    }

    private static void verifyKryoModes() {
        ConsumerItem original = new ConsumerItem(9, "nine");
        KryoSerializer<ConsumerItem> trusted = new KryoSerializer<ConsumerItem>(
                ConsumerItem.class,
                PersistenceConfig.DEFAULT_CONFIG);
        ConsumerAssertions.require(original.equals(trusted.deserialize(trusted.serialize(original))),
                "Trusted-store Kryo round trip failed");

        PersistenceConfig registeredConfig = ConsumerItem.class.getAnnotation(PersistenceConfig.class);
        ConsumerAssertions.require(
                registeredConfig.deserializationMode() == KryoDeserializationMode.REGISTERED_TYPES,
                "Consumer item is not configured for registered-types mode");
        KryoSerializer<ConsumerItem> registered = new KryoSerializer<ConsumerItem>(
                ConsumerItem.class,
                registeredConfig);
        ConsumerAssertions.require(original.equals(registered.deserialize(registered.serialize(original))),
                "Registered-types Kryo round trip failed");
    }
}
