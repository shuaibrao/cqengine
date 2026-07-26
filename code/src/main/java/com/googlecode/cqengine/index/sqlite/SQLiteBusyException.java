/*
 * Copyright 2026 Shuaib Rao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.cqengine.index.sqlite;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Signals that SQLite could not acquire a required lock before its configured busy handler stopped waiting.
 *
 * <p>The primary code is {@link SQLiteErrorCode#SQLITE_BUSY}. The extended code distinguishes the base result from
 * recovery, snapshot and VFS timeout variants when SQLite supplies one. The original driver exception is retained as
 * the cause.
 */
public class SQLiteBusyException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final int primaryErrorCode;
    private final int extendedErrorCode;

    /**
     * Creates an exception for an SQLite busy result.
     *
     * @param message The CQEngine operation which could not complete.
     * @param cause The original sqlite-jdbc exception.
     */
    public SQLiteBusyException(String message, SQLiteException cause) {
        super(message + " (SQLite primary error code " + cause.getErrorCode()
                + ", extended error code " + cause.getResultCode().code + ")", cause);
        this.primaryErrorCode = cause.getErrorCode();
        this.extendedErrorCode = cause.getResultCode().code;
    }

    /** Returns SQLite's primary result code, which is {@code SQLITE_BUSY} ({@code 5}). */
    public int getPrimaryErrorCode() {
        return primaryErrorCode;
    }

    /** Returns the exact base or extended SQLite result code supplied by sqlite-jdbc. */
    public int getExtendedErrorCode() {
        return extendedErrorCode;
    }

    /** Returns the original sqlite-jdbc exception. */
    public SQLiteException getSQLiteException() {
        return (SQLiteException) getCause();
    }
}
