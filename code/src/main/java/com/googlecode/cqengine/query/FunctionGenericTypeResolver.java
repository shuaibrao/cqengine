// Copyright 2026 Shuaib Rao
// SPDX-License-Identifier: Apache-2.0
package com.googlecode.cqengine.query;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericSignatureFormatError;
import java.lang.reflect.MalformedParameterizedTypeException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.Map;

/** Resolves reified generic interface arguments using only the supported reflection API. */
final class FunctionGenericTypeResolver {

    private FunctionGenericTypeResolver() {
    }

    static Class<?>[] resolveRawArguments(Class<?> functionType, Class<?> implementationType) {
        try {
            return findRawArguments(implementationType, functionType, Map.of());
        }
        catch (GenericSignatureFormatError | MalformedParameterizedTypeException | TypeNotPresentException e) {
            return null;
        }
    }

    private static Class<?>[] findRawArguments(
            Type currentType,
            Class<?> functionType,
            Map<TypeVariable<?>, Type> inheritedBindings) {
        Class<?> rawType = rawClass(currentType);
        if (rawType == null) {
            return null;
        }

        Map<TypeVariable<?>, Type> bindings = new HashMap<>(inheritedBindings);
        if (currentType instanceof ParameterizedType parameterizedType) {
            TypeVariable<?>[] parameters = rawType.getTypeParameters();
            Type[] arguments = parameterizedType.getActualTypeArguments();
            for (int i = 0; i < parameters.length; i++) {
                bindings.put(parameters[i], resolveType(arguments[i], inheritedBindings));
            }
        }

        if (rawType.equals(functionType)) {
            TypeVariable<?>[] parameters = functionType.getTypeParameters();
            Class<?>[] arguments = new Class<?>[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = erase(parameters[i], bindings);
            }
            return arguments;
        }

        Class<?>[] incompleteMatch = null;
        for (Type genericInterface : rawType.getGenericInterfaces()) {
            Class<?>[] match = findRawArguments(genericInterface, functionType, bindings);
            if (isComplete(match)) {
                return match;
            }
            if (match != null) {
                incompleteMatch = match;
            }
        }

        Type genericSuperclass = rawType.getGenericSuperclass();
        if (genericSuperclass != null) {
            Class<?>[] match = findRawArguments(genericSuperclass, functionType, bindings);
            if (isComplete(match)) {
                return match;
            }
            if (match != null) {
                incompleteMatch = match;
            }
        }
        return incompleteMatch;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> cls) {
            return cls;
        }
        return null;
    }

    private static Type resolveType(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type resolved = type;
        while (resolved instanceof TypeVariable<?> variable) {
            Type replacement = bindings.get(variable);
            if (replacement == null || replacement.equals(resolved)) {
                return resolved;
            }
            resolved = replacement;
        }
        return resolved;
    }

    private static Class<?> erase(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type resolved = resolveType(type, bindings);
        if (resolved instanceof Class<?> cls) {
            return cls;
        }
        if (resolved instanceof ParameterizedType parameterizedType) {
            return rawClass(parameterizedType);
        }
        if (resolved instanceof GenericArrayType arrayType) {
            Class<?> componentType = erase(arrayType.getGenericComponentType(), bindings);
            return componentType == null ? null : Array.newInstance(componentType, 0).getClass();
        }
        if (resolved instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            return upperBounds.length == 0 ? null : erase(upperBounds[0], bindings);
        }
        return null;
    }

    private static boolean isComplete(Class<?>[] arguments) {
        if (arguments == null) {
            return false;
        }
        for (Class<?> argument : arguments) {
            if (argument == null) {
                return false;
            }
        }
        return true;
    }
}
