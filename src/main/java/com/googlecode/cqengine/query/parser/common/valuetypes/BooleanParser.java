/**
 * Copyright 2012-2015 Niall Gallagher
 * Modified by Shuaib Rao in 2026.
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
package com.googlecode.cqengine.query.parser.common.valuetypes;

import com.googlecode.cqengine.query.parser.common.ValueParser;

/**
 * @author Niall Gallagher
 */
public class BooleanParser extends ValueParser<Boolean> {

    static final String TRUE_STR = Boolean.TRUE.toString();
    static final String FALSE_STR = Boolean.FALSE.toString();

    @Override
    public Boolean parse(Class<? extends Boolean> valueType, String stringValue) {
        if (equalsAsciiIgnoreCase(TRUE_STR, stringValue)) {
            return true;
        }
        else if (equalsAsciiIgnoreCase(FALSE_STR, stringValue)) {
            return false;
        }
        else {
            throw new IllegalStateException("Could not parse value as boolean: " + stringValue);
        }
    }

    private static boolean equalsAsciiIgnoreCase(String expectedLowerCase, String actual) {
        if (actual == null || actual.length() != expectedLowerCase.length()) {
            return false;
        }
        for (int i = 0; i < expectedLowerCase.length(); i++) {
            char character = actual.charAt(i);
            if (character >= 'A' && character <= 'Z') {
                character = (char) (character + ('a' - 'A'));
            }
            if (character != expectedLowerCase.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
