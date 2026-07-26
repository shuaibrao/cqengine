// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
@SuppressWarnings("requires-automatic")
module io.github.shuaibrao.cqengine.consumer {
    requires cqengine;
    requires java.management;
    requires java.sql;

    uses java.sql.Driver;

    exports io.github.shuaibrao.cqengine.consumer;
    opens io.github.shuaibrao.cqengine.consumer to cqengine, com.esotericsoftware.kryo, org.javassist;
}
