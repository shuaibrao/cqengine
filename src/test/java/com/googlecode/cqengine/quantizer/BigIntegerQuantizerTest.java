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

import java.math.BigInteger;

/**
 * @author Niall Gallagher
 */
public class BigIntegerQuantizerTest {

    @Test
    public void testWithCompressionFactor_5() throws Exception {
        Quantizer<BigInteger> quantizer = BigIntegerQuantizer.withCompressionFactor(5);
        // Note: comparing using toString, as double comparison with epsilon would not distinguish 0.0 from -0.0...
        TestAssertions.assertEquals("0", quantizer.getQuantizedValue(BigInteger.valueOf(0)).toString());
        TestAssertions.assertEquals("0", quantizer.getQuantizedValue(BigInteger.valueOf(4)).toString());
        TestAssertions.assertEquals("5", quantizer.getQuantizedValue(BigInteger.valueOf(5)).toString());
        TestAssertions.assertEquals("5", quantizer.getQuantizedValue(BigInteger.valueOf(9)).toString());
        TestAssertions.assertEquals("10", quantizer.getQuantizedValue(BigInteger.valueOf(11)).toString());
        TestAssertions.assertEquals("0", quantizer.getQuantizedValue(BigInteger.valueOf(-0)).toString());
        TestAssertions.assertEquals("0", quantizer.getQuantizedValue(BigInteger.valueOf(-4)).toString());
        TestAssertions.assertEquals("-5", quantizer.getQuantizedValue(BigInteger.valueOf(-5)).toString());
        TestAssertions.assertEquals("-5", quantizer.getQuantizedValue(BigInteger.valueOf(-9)).toString());
        TestAssertions.assertEquals("-10", quantizer.getQuantizedValue(BigInteger.valueOf(-11)).toString());
    }

    @Test
    @ExpectedException(IllegalArgumentException.class)
    public void testWithCompressionFactor_1() throws Exception {
        BigIntegerQuantizer.withCompressionFactor(1);
    }


}
