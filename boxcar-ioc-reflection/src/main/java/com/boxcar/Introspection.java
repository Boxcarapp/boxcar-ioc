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

import com.boxcar.BeanInfo.Dependency;
import com.boxcar.BeanInfo.FieldMember;
import com.boxcar.BeanInfo.Member;
import com.boxcar.BeanInfo.MethodMember;
import com.boxcar.Injector.InjectionException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Builds and caches {@link BeanInfo} for exactly the classes the injector is asked about.
 *
 * <p>Nothing is scanned: a class is introspected the first time it is requested, bound as an
 * implementation, or reached as the type of an injection point of another bean that is being
 * created. Introspection depends only on the class itself, so the cache is shared by all injectors
 * in the JVM and a test suite pays for each bean class once.
 *
 * <p>Follows the ordering and overriding rules of the Jakarta Inject specification: constructor,
 * then fields, then methods; superclass members before subclass members; a method overriding an
 * {@code @Inject} method is injected once, and only if it is itself annotated.
 */
final class Introspection {

    private static final ConcurrentHashMap<Class<?>, BeanInfo> CACHE = new ConcurrentHashMap<>();

    private Introspection() {
    }

    /** Whether the injector may instantiate {@code type}: a concrete class carrying a bean-defining annotation. */
    static boolean isBeanClass(Class<?> type) {
        if (type.isInterface() || type.isPrimitive() || type.isArray() || type.isEnum() || type.isAnnotation()
                || Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        return Annotations.isAnnotated(type, Annotations.BEAN_DEFINING);
    }

    /**
     * The (cached) description of a bean class.
     *
     * @throws InjectionException if the class cannot be used as a bean
     */
    static BeanInfo of(Class<?> type) {
        BeanInfo info = CACHE.get(type);
        if (info == null) {
            // Not computeIfAbsent: introspection is cheap and must not hold a map lock while it runs.
            info = introspect(type);
            CACHE.putIfAbsent(type, info);
        }
        return info;
    }

    /** The classes introspected so far in this JVM; exposed for tests of the injector's laziness. */
    static Set<Class<?>> introspectedClasses() {
        return Collections.unmodifiableSet(CACHE.keySet());
    }

    private static BeanInfo introspect(Class<?> type) {
        String name = name(type);
        if (type.isLocalClass() || type.isAnonymousClass()) {
            throw new InjectionException("Local and anonymous classes cannot be beans: " + name);
        }
        if (type.isMemberClass() && !Modifier.isStatic(type.getModifiers())) {
            throw new InjectionException("Bean class " + name
                    + " is a non-static inner class; the injector cannot instantiate it without an enclosing instance");
        }

        Map<TypeVariable<?>, Type> typeArguments = GenericTypes.typeArguments(type);
        Constructor<?> constructor = findConstructor(type, name);
        List<Dependency> constructorDependencies = parameterDependencies(constructor, name + "(...)", typeArguments);

        List<Class<?>> hierarchy = hierarchy(type);
        List<Member> members = new ArrayList<>();
        List<Method> postConstructs = new ArrayList<>();
        for (int i = 0; i < hierarchy.size(); i++) {
            Class<?> owner = hierarchy.get(i);
            List<Class<?>> subclasses = hierarchy.subList(i + 1, hierarchy.size());
            scanFields(owner, typeArguments, members);
            scanMethods(owner, subclasses, typeArguments, members);
            scanPostConstruct(owner, subclasses, postConstructs);
        }
        return new BeanInfo(type, constructor, constructorDependencies, List.copyOf(members),
                List.copyOf(postConstructs));
    }

    private static Constructor<?> findConstructor(Class<?> type, String name) {
        List<Constructor<?>> injectable = Arrays.stream(type.getDeclaredConstructors())
                .filter(c -> Annotations.isAnnotated(c, Annotations.CONSTRUCTOR_INJECT))
                .toList();
        if (injectable.size() > 1) {
            throw new InjectionException("Bean class " + name + " has " + injectable.size()
                    + " @Inject constructors; at most one is allowed");
        }
        if (injectable.size() == 1) {
            return accessible(injectable.get(0), "constructor of " + name);
        }
        try {
            return accessible(type.getDeclaredConstructor(), "constructor of " + name);
        } catch (NoSuchMethodException e) {
            throw new InjectionException("Bean class " + name
                    + " has neither an @Inject annotated constructor nor a no-argument constructor");
        }
    }

    /** The class and its superclasses, topmost first, excluding java.lang.Object. */
    private static List<Class<?>> hierarchy(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            chain.add(0, current);
        }
        return chain;
    }

    private static void scanFields(Class<?> owner, Map<TypeVariable<?>, Type> typeArguments, List<Member> members) {
        for (Field field : owner.getDeclaredFields()) {
            String annotation = Annotations.firstAnnotation(field, Annotations.INJECT);
            if (annotation == null || field.isSynthetic()) {
                continue;
            }
            String description = name(owner) + "." + field.getName();
            if (Modifier.isStatic(field.getModifiers())) {
                throw new InjectionException("@" + annotation + " field " + description
                        + " is static; static injection is not supported");
            }
            if (Modifier.isFinal(field.getModifiers())) {
                throw new InjectionException("@" + annotation + " field " + description
                        + " is final and cannot be injected");
            }
            accessible(field, "field " + description);
            members.add(new FieldMember(field, dependency(field.getGenericType(), description, typeArguments)));
        }
    }

    private static void scanMethods(Class<?> owner, List<Class<?>> subclasses, Map<TypeVariable<?>, Type> typeArguments,
            List<Member> members) {
        for (Method method : owner.getDeclaredMethods()) {
            String annotation = Annotations.firstAnnotation(method, Annotations.INJECT);
            if (annotation == null || method.isSynthetic()) {
                continue;
            }
            String description = name(owner) + "." + method.getName() + "(...)";
            if (Modifier.isStatic(method.getModifiers())) {
                throw new InjectionException("@" + annotation + " method " + description
                        + " is static; static injection is not supported");
            }
            if (Modifier.isAbstract(method.getModifiers())) {
                throw new InjectionException("@" + annotation + " method " + description + " is abstract");
            }
            if (method.getTypeParameters().length > 0) {
                throw new InjectionException("@" + annotation + " method " + description
                        + " declares type parameters, which injectable methods may not do");
            }
            if (annotation.equals("EJB") && method.getParameterCount() != 1) {
                throw new InjectionException("@EJB method " + description
                        + " must be a setter with exactly one parameter");
            }
            if (isOverriddenIn(method, subclasses)) {
                // Injected at most once per instance: the overriding method decides (and is skipped if it
                // is not itself annotated).
                continue;
            }
            accessible(method, "method " + description);
            members.add(new MethodMember(method, parameterDependencies(method, description, typeArguments)));
        }
    }

    private static void scanPostConstruct(Class<?> owner, List<Class<?>> subclasses, List<Method> postConstructs) {
        List<Method> callbacks = Arrays.stream(owner.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && Annotations.isAnnotated(m, Annotations.POST_CONSTRUCT))
                .toList();
        if (callbacks.size() > 1) {
            throw new InjectionException("Class " + name(owner) + " declares " + callbacks.size()
                    + " @PostConstruct methods ("
                    + callbacks.stream().map(Method::getName).sorted().collect(Collectors.joining(", "))
                    + "); at most one is allowed per class");
        }
        for (Method callback : callbacks) {
            String description = name(owner) + "." + callback.getName() + "()";
            if (Modifier.isStatic(callback.getModifiers())) {
                throw new InjectionException("@PostConstruct method " + description + " must not be static");
            }
            if (callback.getParameterCount() != 0) {
                throw new InjectionException("@PostConstruct method " + description + " must not take parameters");
            }
            if (Modifier.isAbstract(callback.getModifiers()) || isOverriddenIn(callback, subclasses)) {
                continue;
            }
            postConstructs.add(accessible(callback, "method " + description));
        }
    }

    /**
     * Whether a subclass declares a method overriding {@code method}. Bridge methods count, which is
     * what makes overrides of generic methods ({@code set(T)} by {@code set(Foo)}) detectable.
     */
    private static boolean isOverriddenIn(Method method, List<Class<?>> subclasses) {
        int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)) {
            return false;
        }
        boolean packagePrivate = !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers);
        for (Class<?> subclass : subclasses) {
            if (packagePrivate && !subclass.getPackageName().equals(method.getDeclaringClass().getPackageName())) {
                continue;
            }
            for (Method candidate : subclass.getDeclaredMethods()) {
                if (!Modifier.isStatic(candidate.getModifiers()) && candidate.getName().equals(method.getName())
                        && Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Dependency> parameterDependencies(java.lang.reflect.Executable executable, String description,
            Map<TypeVariable<?>, Type> typeArguments) {
        Parameter[] parameters = executable.getParameters();
        Type[] types = executable.getGenericParameterTypes();
        List<Dependency> dependencies = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            String parameterDescription = description + " parameter '" + parameters[i].getName() + "'";
            dependencies.add(dependency(types[i], parameterDescription, typeArguments));
        }
        return List.copyOf(dependencies);
    }

    private static Dependency dependency(Type genericType, String description,
            Map<TypeVariable<?>, Type> typeArguments) {
        Type declared = GenericTypes.resolve(genericType, typeArguments);
        Class<?> raw = GenericTypes.rawClass(declared);
        if (Annotations.PROVIDER.contains(raw.getName()) && declared instanceof ParameterizedType parameterized) {
            Type provided = parameterized.getActualTypeArguments()[0];
            if (!GenericTypes.isVariableOrWildcard(provided)) {
                return new Dependency(GenericTypes.rawClass(provided), raw, declared, description);
            }
        }
        return new Dependency(raw, null, declared, description);
    }

    private static <T extends AccessibleObject> T accessible(T member, String what) {
        try {
            member.setAccessible(true);
            return member;
        } catch (RuntimeException e) {
            throw new InjectionException("Cannot access " + what + "; is its module open to com.boxcar?", e);
        }
    }

    static String name(Class<?> type) {
        String canonical = type.getCanonicalName();
        return canonical != null ? canonical : type.getName();
    }
}
