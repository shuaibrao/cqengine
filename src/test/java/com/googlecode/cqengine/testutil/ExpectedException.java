// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.testutil;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ExpectedExceptionExtension.class)
public @interface ExpectedException {
    Class<? extends Throwable> value();
}
