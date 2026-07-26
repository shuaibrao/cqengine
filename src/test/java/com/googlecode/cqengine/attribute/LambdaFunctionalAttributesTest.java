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
package com.googlecode.cqengine.attribute;

import com.googlecode.cqengine.attribute.support.MultiValueFunction;
import com.googlecode.cqengine.attribute.support.SimpleFunction;
import com.googlecode.cqengine.testutil.Car;
import com.googlecode.cqengine.testutil.CarFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.googlecode.cqengine.query.QueryFactory.attribute;
import static com.googlecode.cqengine.query.QueryFactory.multiValueAttribute;
import static com.googlecode.cqengine.query.QueryFactory.multiValueNullableAttribute;
import static com.googlecode.cqengine.query.QueryFactory.nullableAttribute;
import static com.googlecode.cqengine.query.QueryFactory.simpleAttribute;
import static com.googlecode.cqengine.query.QueryFactory.simpleNullableAttribute;
import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;
import static com.googlecode.cqengine.testutil.TestAssertions.assertTrue;
import static com.googlecode.cqengine.testutil.TestAssertions.fail;

/** Tests creation of CQEngine attributes from function objects, lambdas and method references. */
public class LambdaFunctionalAttributesTest {

    private static final String SYNTHETIC_INFERENCE_FAILURE =
            "Generic type inference is not supported for lambda expressions or method references because their " +
                    "synthetic runtime classes do not expose generic arguments. Use simpleAttribute, " +
                    "simpleNullableAttribute, multiValueAttribute or multiValueNullableAttribute to supply objectType " +
                    "and attributeType explicitly.";

    final SimpleFunction<Car, Integer> carIdFunction = new SimpleFunction<Car, Integer>() {
        @Override
        public Integer apply(Car car) {
            return car.getCarId();
        }
    };

    final MultiValueFunction<Car, String, List<String>> featuresFunction =
            new MultiValueFunction<Car, String, List<String>>() {
                @Override
                public List<String> apply(Car car) {
                    return car.getFeatures();
                }
            };

    @Test
    public void testAnonymousSimpleFunctionInference() {
        SimpleAttribute<Car, Integer> attribute = attribute(carIdFunction);

        assertAttributeTypes(attribute, Car.class, Integer.class);
        assertTrue(attribute.getAttributeName().startsWith(getClass().getName() + "$"));
    }

    @Test
    public void testAnonymousSimpleNullableFunctionInference() {
        SimpleNullableAttribute<Car, Integer> attribute = nullableAttribute(carIdFunction);

        assertAttributeTypes(attribute, Car.class, Integer.class);
        assertTrue(attribute.getAttributeName().startsWith(getClass().getName() + "$"));
    }

    @Test
    public void testAnonymousMultiValueFunctionInference() {
        MultiValueAttribute<Car, String> attribute = attribute(String.class, featuresFunction);

        assertAttributeTypes(attribute, Car.class, String.class);
        assertTrue(attribute.getAttributeName().startsWith(getClass().getName() + "$"));
    }

    @Test
    public void testAnonymousMultiValueNullableFunctionInference() {
        MultiValueNullableAttribute<Car, String> attribute = nullableAttribute(String.class, featuresFunction);

        assertAttributeTypes(attribute, Car.class, String.class);
        assertTrue(attribute.getAttributeName().startsWith(getClass().getName() + "$"));
    }

    @Test
    public void testNamedAndInheritedSimpleFunctionInference() {
        assertAttributeTypes(attribute(new NamedCarIdFunction()), Car.class, Integer.class);
        assertAttributeTypes(attribute(new InheritedCarIdFunction()), Car.class, Integer.class);
        assertAttributeTypes(attribute(new InterfaceCarIdFunction()), Car.class, Integer.class);
    }

    @Test
    public void testInheritedMultiValueFunctionInference() {
        MultiValueAttribute<Car, String> attribute = attribute(String.class, new InheritedFeaturesFunction());

        assertAttributeTypes(attribute, Car.class, String.class);
    }

    @Test
    public void testParameterizedAttributeTypeIsResolvedToItsRawClass() {
        SimpleFunction<Car, List<String>> function = new SimpleFunction<Car, List<String>>() {
            @Override
            public List<String> apply(Car car) {
                return car.getFeatures();
            }
        };

        SimpleAttribute<Car, List<String>> attribute = attribute(function);

        assertAttributeTypes(attribute, Car.class, List.class);
    }

    @Test
    public void testLambdaInferenceFailsDeterministically() {
        SimpleFunction<Car, Integer> function = car -> car.getCarId();

        assertSyntheticInferenceFailure(() -> attribute("carId", function));
    }

    @Test
    public void testMethodReferenceInferenceFailsDeterministically() {
        SimpleFunction<Car, Integer> function = Car::getCarId;

        assertSyntheticInferenceFailure(() -> attribute("carId", function));
    }

    @Test
    public void testMultiValueMethodReferenceInferenceFailsDeterministically() {
        MultiValueFunction<Car, String, List<String>> function = Car::getFeatures;

        assertSyntheticInferenceFailure(() -> attribute(String.class, "features", function));
    }

    @Test
    public void testExplicitSimpleOverloadsSupportLambdaAndMethodReference() {
        SimpleAttribute<Car, Integer> simple =
                simpleAttribute(Car.class, Integer.class, "carId", car -> car.getCarId());
        SimpleNullableAttribute<Car, Integer> nullable =
                simpleNullableAttribute(Car.class, Integer.class, "nullableCarId", Car::getCarId);
        SimpleFunction<Car, Integer> methodReference = Car::getCarId;
        SimpleAttribute<Car, Integer> legacyExplicitOverload =
                attribute(Car.class, Integer.class, "legacyCarId", methodReference);
        Car car = CarFactory.createCar(1);

        assertAttributeTypes(simple, Car.class, Integer.class);
        assertAttributeTypes(nullable, Car.class, Integer.class);
        assertAttributeTypes(legacyExplicitOverload, Car.class, Integer.class);
        assertEquals(Integer.valueOf(car.getCarId()), simple.getValue(car, null));
        assertEquals(Integer.valueOf(car.getCarId()), nullable.getValue(car, null));
    }

    @Test
    public void testExplicitMultiValueOverloadsSupportLambdaAndMethodReference() {
        MultiValueAttribute<Car, String> multi =
                multiValueAttribute(Car.class, String.class, "features", Car::getFeatures);
        MultiValueNullableAttribute<Car, String> nullable =
                multiValueNullableAttribute(Car.class, String.class, "nullableFeatures", car -> car.getFeatures());
        MultiValueFunction<Car, String, List<String>> lambda = car -> car.getFeatures();
        MultiValueAttribute<Car, String> legacyExplicitOverload =
                attribute(Car.class, String.class, "legacyFeatures", lambda);
        Car car = CarFactory.createCar(1);

        assertAttributeTypes(multi, Car.class, String.class);
        assertAttributeTypes(nullable, Car.class, String.class);
        assertAttributeTypes(legacyExplicitOverload, Car.class, String.class);
        assertEquals(car.getFeatures(), multi.getValues(car, null));
        assertEquals(car.getFeatures(), nullable.getNullableValues(car, null));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void testRawClassBasedFunctionFailsClearly() {
        SimpleFunction rawFunction = new SimpleFunction() {
            @Override
            public Object apply(Object object) {
                return object;
            }
        };

        try {
            attribute("raw", rawFunction);
            fail("Expected raw function inference to fail");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(
                    "Generic type inference requires a class-based function implementation which retains concrete " +
                            "generic arguments."));
            assertTrue(expected.getMessage().contains(
                    "Use simpleAttribute, simpleNullableAttribute, multiValueAttribute or " +
                            "multiValueNullableAttribute to supply objectType and attributeType explicitly."));
        }
    }

    private static void assertSyntheticInferenceFailure(Runnable invocation) {
        try {
            invocation.run();
            fail("Expected synthetic function inference to fail");
        }
        catch (IllegalStateException expected) {
            assertEquals(SYNTHETIC_INFERENCE_FAILURE, expected.getMessage());
        }
    }

    private static void assertAttributeTypes(
            Attribute<?, ?> attribute,
            Class<?> expectedObjectType,
            Class<?> expectedAttributeType) {
        assertEquals(expectedObjectType, attribute.getObjectType());
        assertEquals(expectedAttributeType, attribute.getAttributeType());
    }

    static final class NamedCarIdFunction implements SimpleFunction<Car, Integer> {
        @Override
        public Integer apply(Car car) {
            return car.getCarId();
        }
    }

    abstract static class BaseSimpleFunction<O, A> implements SimpleFunction<O, A> {
    }

    static final class InheritedCarIdFunction extends BaseSimpleFunction<Car, Integer> {
        @Override
        public Integer apply(Car car) {
            return car.getCarId();
        }
    }

    interface CarIdFunction extends SimpleFunction<Car, Integer> {
    }

    static final class InterfaceCarIdFunction implements CarIdFunction {
        @Override
        public Integer apply(Car car) {
            return car.getCarId();
        }
    }

    abstract static class BaseMultiValueFunction<O, A, I extends Iterable<A>>
            implements MultiValueFunction<O, A, I> {
    }

    static final class InheritedFeaturesFunction
            extends BaseMultiValueFunction<Car, String, List<String>> {
        @Override
        public List<String> apply(Car car) {
            return car.getFeatures();
        }
    }
}
