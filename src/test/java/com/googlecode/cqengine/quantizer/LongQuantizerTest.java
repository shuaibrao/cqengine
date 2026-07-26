/**
 * Copyright 2012-2015 Niall Gallagher
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
package com.googlecode.cqengine.quantizer;

import com.googlecode.cqengine.testutil.ExpectedException;

import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;

/**
 * @author Niall Gallagher
 */
public class LongQuantizerTest {
    
    @Test
    public void testWithCompressionFactor_5() throws Exception {
        Quantizer<Long> quantizer = LongQuantizer.withCompressionFactor(5);
        // Note: comparing using toString, as double comparison with epsilon would not distinguish 0.0 from -0.0...
        TestAssertions.assertEquals("0", quantizer.getQuantizedValue(0L).toString());
        TestAssertions.assertEquals("0", quantizer.getQuantizedValue(4L).toString());
        TestAssertions.assertEquals("5", quantizer.getQuantizedValue(5L).toString());
        TestAssertions.assertEquals("5", quantizer.getQuantizedValue(9L).toString());
        TestAssertions.assertEquals("10", quantizer.getQuantizedValue(11L).toString());
        TestAssertions.assertEquals("0", quantizer.getQuantizedValue(-0L).toString());
        TestAssertions.assertEquals("0", quantizer.getQuantizedValue(-4L).toString());
        TestAssertions.assertEquals("-5", quantizer.getQuantizedValue(-5L).toString());
        TestAssertions.assertEquals("-5", quantizer.getQuantizedValue(-9L).toString());
        TestAssertions.assertEquals("-10", quantizer.getQuantizedValue(-11L).toString());
    }

    @Test
    @ExpectedException(IllegalArgumentException.class)
    public void testWithCompressionFactor_1() throws Exception {
        LongQuantizer.withCompressionFactor(1);
    }
}
