// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.testutil;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;

public final class ExpectedExceptionExtension implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(
            Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) {
        ExpectedException annotation = invocationContext.getExecutable().getAnnotation(ExpectedException.class);
        Assertions.assertThrows(annotation.value(), invocation::proceed);
    }
}
