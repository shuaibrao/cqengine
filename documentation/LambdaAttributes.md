# CQEngine Lambda Attributes

CQEngine attributes can be created from lambda expressions and method references through the explicitly typed factory
methods in `QueryFactory`.

Import those methods with:

```java
import static com.googlecode.cqengine.query.QueryFactory.*;
```

## Always specify lambda types

Use `simpleAttribute`, `simpleNullableAttribute`, `multiValueAttribute` and `multiValueNullableAttribute`. These
factories accept `objectType`, `attributeType`, `attributeName` and the function. Java's supported reflection API does
not expose the erased generic arguments of synthetic lambda or method-reference classes. The explicit factories are
deterministic on every supported JVM and do not require module opens or JDK-internal access.

It is also useful to give each attribute a stable name. Cache attributes in `static final` fields instead of creating
them repeatedly.

### SimpleAttribute

```java
public static final Attribute<Car, Double> PRICE =
        simpleAttribute(Car.class, Double.class, "price", Car::getPrice);
```

### SimpleNullableAttribute

```java
public static final Attribute<Car, Double> PRICE =
        simpleNullableAttribute(Car.class, Double.class, "price", Car::getPrice);
```

### MultiValueAttribute

```java
public static final Attribute<Car, String> FEATURES =
        multiValueAttribute(Car.class, String.class, "features", Car::getFeatures);
```

### MultiValueNullableAttribute

```java
public static final Attribute<Car, String> FEATURES =
        multiValueNullableAttribute(Car.class, String.class, "features", Car::getFeatures);
```

### Virtual attributes

```java
public static final Attribute<Car, Boolean> IS_CHEAP =
        simpleAttribute(Car.class, Boolean.class, "isCheap", car -> car.getPrice() < 4000);
```

## Class-based function inference

The older inference overloads remain source- and binary-compatible for class-based implementations which retain
concrete generic signatures. This includes named and anonymous classes:

```java
SimpleFunction<Car, Double> function = new SimpleFunction<Car, Double>() {
    @Override
    public Double apply(Car car) {
        return car.getPrice();
    }
};

Attribute<Car, Double> price = attribute("price", function);
```

Inherited generic implementations are supported when a concrete subclass binds all required type variables. Raw or
unresolved generic implementations fail with an `IllegalStateException` which directs callers to the explicit overload.
Passing a lambda or method reference to an inference overload also fails deterministically with that guidance.

The inference overloads use only supported Java reflection over generic interfaces and superclasses. They do not use
`Unsafe`, privileged lookups, constant-pool access or module opens, and they remain useful for class-based functions,
so they are not deprecated.

CQEngine no longer publishes TypeTools as a transitive dependency. Applications which use TypeTools directly must
declare their own dependency. It was not part of any CQEngine public signature.
