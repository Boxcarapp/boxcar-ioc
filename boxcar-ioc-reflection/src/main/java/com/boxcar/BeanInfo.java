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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Everything the injector needs to know about one bean class: how to construct it, which members to
 * inject (in specification order) and which lifecycle callbacks to invoke afterwards. Immutable and
 * cached per class by {@link Introspection}.
 */
record BeanInfo(
        Class<?> type,
        Constructor<?> constructor,
        List<Dependency> constructorDependencies,
        List<Member> members,
        List<Method> postConstructs) {

    sealed interface Member permits FieldMember, MethodMember {
    }

    record FieldMember(Field field, Dependency dependency) implements Member {
    }

    record MethodMember(Method method, List<Dependency> dependencies) implements Member {
    }

    /**
     * One thing a bean needs injected.
     *
     * @param rawType the erasure of the requested type: for {@code Provider<X>} injection points the
     *        erasure of {@code X}, otherwise of the declared type
     * @param providerType the {@code Provider} interface to implement, or null for a direct injection
     * @param declaredType the declared type with the bean's type arguments substituted, for messages
     * @param description human readable injection point, e.g. {@code com.acme.OrderService.repository}
     */
    record Dependency(Class<?> rawType, Class<?> providerType, Type declaredType, String description) {

        boolean isProvider() {
            return providerType != null;
        }
    }
}
