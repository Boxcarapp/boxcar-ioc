package com.boxcar;

/*-
 * #%L
 * Boxcar IoC :: Reflection
 * %%
 * Copyright (C) 2026 Boxcar
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Substitutes the type variables of generic superclasses, so that {@code @Inject Repository<T> repo}
 * declared in {@code Base<T>} is seen as {@code Repository<User>} from {@code UserService extends
 * Base<User>}. The reflective counterpart of {@code Types.asMemberOf}.
 */
final class GenericTypes {

    private GenericTypes() {
    }

    /**
     * Type arguments bound along the superclass chain of {@code beanClass}, keyed by the superclasses' type variables.
     */
    static Map<TypeVariable<?>, Type> typeArguments(Class<?> beanClass) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        for (Class<?> current = beanClass; current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (current.getGenericSuperclass() instanceof ParameterizedType parameterized) {
                TypeVariable<?>[] variables = ((Class<?>) parameterized.getRawType()).getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < variables.length; i++) {
                    // Arguments may mention the subclass's own variables, which were bound in an earlier iteration.
                    bindings.put(variables[i], resolve(arguments[i], bindings));
                }
            }
        }
        return bindings;
    }

    /** {@code type} with every bound type variable replaced; unbound variables are left in place. */
    static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> variable) {
            Type bound = bindings.get(variable);
            return bound == null || bound == variable ? variable : resolve(bound, bindings);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] resolved = new Type[arguments.length];
            boolean changed = false;
            for (int i = 0; i < arguments.length; i++) {
                resolved[i] = resolve(arguments[i], bindings);
                changed |= resolved[i] != arguments[i];
            }
            return changed
                    ? new Parameterized(parameterized.getRawType(), parameterized.getOwnerType(), resolved)
                    : type;
        }
        if (type instanceof GenericArrayType array) {
            Type component = resolve(array.getGenericComponentType(), bindings);
            if (component == array.getGenericComponentType()) {
                return type;
            }
            return component instanceof Class<?> componentClass
                    ? componentClass.arrayType()
                    : new GenericArray(component);
        }
        return type;
    }

    /** The erasure of {@code type}: what an instance can be checked and looked up by. */
    static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        if (type instanceof GenericArrayType array) {
            return rawClass(array.getGenericComponentType()).arrayType();
        }
        if (type instanceof TypeVariable<?> variable) {
            return rawClass(variable.getBounds()[0]);
        }
        if (type instanceof WildcardType wildcard) {
            return rawClass(wildcard.getUpperBounds()[0]);
        }
        throw new IllegalArgumentException("Unsupported type " + type);
    }

    static boolean isVariableOrWildcard(Type type) {
        return type instanceof TypeVariable<?> || type instanceof WildcardType;
    }

    private record Parameterized(Type rawType, Type ownerType, Type[] actualTypeArguments)
            implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public String getTypeName() {
            return rawType.getTypeName() + Arrays.stream(actualTypeArguments).map(Type::getTypeName)
                    .collect(Collectors.joining(", ", "<", ">"));
        }

        @Override
        public String toString() {
            return getTypeName();
        }
    }

    private record GenericArray(Type genericComponentType) implements GenericArrayType {

        @Override
        public Type getGenericComponentType() {
            return genericComponentType;
        }

        @Override
        public String getTypeName() {
            return genericComponentType.getTypeName() + "[]";
        }

        @Override
        public String toString() {
            return getTypeName();
        }
    }
}
