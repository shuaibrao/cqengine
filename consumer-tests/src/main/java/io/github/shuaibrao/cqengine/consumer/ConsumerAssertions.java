// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package io.github.shuaibrao.cqengine.consumer;

import com.googlecode.cqengine.ConcurrentIndexedCollection;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

final class ConsumerAssertions {

    private ConsumerAssertions() {
    }

    static File verifyCqengineArtifact() throws Exception {
        String mode = requiredProperty("consumer.artifactMode");
        String launchMode = requiredProperty("consumer.launchMode");
        int expectedJava = Integer.parseInt(requiredProperty("consumer.expectedJava"));
        require(Runtime.version().feature() == expectedJava,
                "Expected Java " + expectedJava + ", found " + Runtime.version());
        File producerRoot = new File(requiredProperty("consumer.producerRoot")).getCanonicalFile();
        URL codeSource = ConcurrentIndexedCollection.class.getProtectionDomain().getCodeSource().getLocation();
        require("file".equals(codeSource.getProtocol()), "CQEngine code source is not a file URL: " + codeSource);
        File cqengineJar = new File(new URI(codeSource.toString())).getCanonicalFile();
        require(cqengineJar.isFile(), "CQEngine was not loaded from a published JAR: " + cqengineJar);
        require(cqengineJar.getName().endsWith(".jar"), "CQEngine code source is not a JAR: " + cqengineJar);
        if ("all".equals(mode)) {
            require(cqengineJar.getName().endsWith("-all.jar"),
                    "All consumer loaded the wrong artifact: " + cqengineJar);
        }
        else if ("thin".equals(mode)) {
            require(!cqengineJar.getName().endsWith("-all.jar"),
                    "Thin consumer loaded the all classifier: " + cqengineJar);
        }
        else {
            throw new AssertionError("Unexpected artifact mode: " + mode);
        }
        require(!cqengineJar.toPath().startsWith(producerRoot.toPath().resolve("build/classes")),
                "CQEngine leaked producer classes onto the consumer classpath: " + cqengineJar);

        String resourceName = "com/googlecode/cqengine/ConcurrentIndexedCollection.class";
        Enumeration<URL> resources = ConcurrentIndexedCollection.class.getClassLoader().getResources(resourceName);
        List<URL> resourceUrls = new ArrayList<URL>();
        while (resources.hasMoreElements()) {
            resourceUrls.add(resources.nextElement());
        }
        require(resourceUrls.size() == 1,
                "Expected one CQEngine class resource, found " + resourceUrls.size() + ": " + resourceUrls);
        require(resourceUrls.get(0).toString().contains(cqengineJar.getName()),
                "CQEngine resource did not come from the resolved artifact: " + resourceUrls.get(0));
        Module consumerModule = ConsumerAssertions.class.getModule();
        Module cqengineModule = ConcurrentIndexedCollection.class.getModule();
        if ("module".equals(launchMode)) {
            require(consumerModule.isNamed()
                            && "io.github.shuaibrao.cqengine.consumer".equals(consumerModule.getName()),
                    "Consumer did not run as the expected named module: " + consumerModule);
            require(cqengineModule.isNamed() && "cqengine".equals(cqengineModule.getName()),
                    "CQEngine did not resolve as the expected named module: " + cqengineModule);
            require(System.getProperty("java.class.path", "").isEmpty(),
                    "Module-path probe has a classpath fallback: " + System.getProperty("java.class.path"));
            require("io.github.shuaibrao.cqengine.consumer".equals(System.getProperty("jdk.module.main")),
                    "Unexpected main module: " + System.getProperty("jdk.module.main"));
            require(!System.getProperty("jdk.module.path", "").isBlank(), "Module path is empty");
        }
        else {
            require(!consumerModule.isNamed(), "Classpath consumer unexpectedly ran as a named module");
            require(!cqengineModule.isNamed(), "Classpath CQEngine unexpectedly ran as a named module");
        }
        return cqengineJar;
    }

    static void verifyJvmArguments(boolean persistenceProbe) {
        List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> opens = arguments.stream()
                .filter(argument -> argument.equals("--add-opens") || argument.startsWith("--add-opens="))
                .toList();
        require(opens.isEmpty(), "Consumer process received forbidden module opens: " + opens);

        List<String> nativeAccess = arguments.stream()
                .filter(argument -> argument.equals("--enable-native-access")
                        || argument.startsWith("--enable-native-access="))
                .toList();
        String launchMode = requiredProperty("consumer.launchMode");
        String artifactMode = requiredProperty("consumer.artifactMode");
        List<String> expected;
        if (!persistenceProbe || Runtime.version().feature() != 25) {
            expected = List.of();
        }
        else if ("classpath".equals(launchMode)) {
            expected = List.of("--enable-native-access=ALL-UNNAMED");
        }
        else {
            String nativeModule = "thin".equals(artifactMode) ? "org.xerial.sqlitejdbc" : "cqengine";
            expected = List.of("--enable-native-access=" + nativeModule);
        }
        require(nativeAccess.equals(expected),
                "Expected native-access arguments " + expected + ", found " + nativeAccess);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static String requiredProperty(String name) {
        String value = System.getProperty(name);
        require(value != null && !value.isBlank(), "Missing system property: " + name);
        return value;
    }
}
