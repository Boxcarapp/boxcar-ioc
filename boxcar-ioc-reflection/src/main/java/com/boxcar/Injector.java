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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Container-free dependency injector for unit tests, driven by reflection.
 *
 * <p>Each {@code Injector} holds one instance per bean class, created lazily on first request together
 * with its transitive dependencies. Create a new {@code Injector} per test for isolation, and use
 * {@link #bind(Class, Object)} to substitute mocks or to supply dependencies that no bean provides, or
 * {@link #bind(Class, Class)} to choose which bean class implements a type. Binding a type again
 * replaces its earlier binding until the type is first resolved, so a test base class can extend
 * {@code Injector}, bind defaults in its constructor and leave individual tests to override them.
 *
 * <p>Only the classes that are requested, bound or reached through injection points are ever
 * introspected; the class path is never scanned. Bean classes are concrete classes annotated with
 * {@code @Singleton} (Jakarta Inject or EJB), {@code @Stateless}, {@code @Stateful} or
 * {@code @ApplicationScoped}; all are treated as one shared instance per injector. Interfaces and
 * abstract classes have to be bound to an implementation class or an instance.
 *
 * <p>A bean is registered right after construction and before its fields and methods are injected,
 * so a dependency cycle simply finds the partially initialised instance and is closed by field or
 * method injection. Cycles that run only through constructors cannot be created and are reported.
 * {@code @PostConstruct} callbacks run once the outermost {@code getInstance} call has wired
 * everything, dependencies first.
 */
public class Injector {

    /** Bean instances by implementation class, plus everything registered through {@link #bind(Class, Object)}. */
    private final Map<Class<?>, Object> instances = new HashMap<>();
    /** Implementation classes registered through {@link #bind(Class, Class)}, by requested type. */
    private final Map<Class<?>, Class<?>> implementations = new HashMap<>();
    /** Types whose instance has been created, injected or returned; their bindings can no longer be replaced. */
    private final Set<Class<?>> resolved = new HashSet<>();
    /** {@code @PostConstruct} callbacks of beans wired during the current resolution, dependencies first. */
    private final ArrayDeque<Runnable> postConstructQueue = new ArrayDeque<>();
    /** Beans being created, most recent first; used to turn cycles that run only through constructors into an error. */
    private final ArrayDeque<Frame> creating = new ArrayDeque<>();
    private boolean resolving;

    /** Creates an empty injector; nothing is introspected until a type is requested or bound. */
    public Injector() {
    }

    /**
     * Returns the bean of the given type, creating it and everything it depends on if necessary.
     *
     * @param <T> the requested type
     * @param type a bean class, or a type passed to {@link #bind(Class, Object)} or {@link #bind(Class, Class)}
     * @return the instance for {@code type}, the same one on every call to this injector
     * @throws InjectionException if the type is unknown or a dependency cannot be satisfied
     */
    public <T> T getInstance(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return type.cast(enter(() -> resolve(type)));
    }

    /**
     * Registers an existing object, typically a mock, to be returned and injected wherever {@code type} is
     * requested. Bindings take precedence over bean classes. Binding a type again replaces its earlier binding
     * until the type is first resolved: once an instance has been created, injected or returned, the binding is fixed.
     *
     * @param <T> the bound type
     * @param type the type to bind, typically an interface or a container-provided class
     * @param instance the object to return and inject for it
     * @return this injector, for chaining
     * @throws IllegalStateException if {@code type} has already been resolved or a resolution is in progress
     */
    public <T> Injector bind(Class<T> type, T instance) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(instance, "instance");
        synchronized (instances) {
            checkBindable(type);
            implementations.remove(type);
            instances.put(type, instance);
        }
        return this;
    }

    /**
     * Registers the bean class to instantiate wherever {@code type} is requested, for example to pick one of
     * several implementations of an interface. Replaces an earlier binding of {@code type} until the type is
     * first resolved.
     *
     * @param <T> the bound type
     * @param type the type to bind, typically an interface or abstract class
     * @param implementation the bean class to instantiate for it
     * @return this injector, for chaining
     * @throws IllegalArgumentException if {@code implementation} is not a bean class
     * @throws IllegalStateException if {@code type} has already been resolved or a resolution is in progress
     */
    public <T> Injector bind(Class<T> type, Class<? extends T> implementation) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(implementation, "implementation");
        if (!Introspection.isBeanClass(implementation)) {
            throw new IllegalArgumentException(implementation.getName()
                    + " is not a bean class (a concrete class annotated with @Stateless, @Singleton, ...);"
                    + " bind an instance instead");
        }
        if (type == implementation || !type.isAssignableFrom(implementation)) {
            throw new IllegalArgumentException("Cannot bind " + type.getName() + " to " + implementation.getName());
        }
        Introspection.of(implementation);
        synchronized (instances) {
            checkBindable(type);
            for (Class<?> next = implementations.get(implementation); next != null; next = implementations.get(next)) {
                if (next == type) {
                    throw new IllegalArgumentException("Binding " + type.getName() + " to " + implementation.getName()
                            + " would form a cycle");
                }
            }
            instances.remove(type);
            implementations.put(type, implementation);
        }
        return this;
    }

    private void checkBindable(Class<?> type) {
        if (resolving) {
            throw new IllegalStateException("Cannot bind " + type.getName() + " while a resolution is in progress");
        }
        if (resolved.contains(type)) {
            throw new IllegalStateException(type.getName() + " has already been resolved by this Injector"
                    + " (an instance was created, injected or returned); bind before the type is first used");
        }
    }

    /** Runs a resolution, and if it is the outermost one, fires the queued @PostConstruct callbacks afterwards. */
    private <T> T enter(Supplier<T> resolution) {
        synchronized (instances) {
            if (resolving) {
                return resolution.get();
            }
            resolving = true;
            Set<Class<?>> before = new HashSet<>(instances.keySet());
            Set<Class<?>> resolvedBefore = new HashSet<>(resolved);
            try {
                T result = resolution.get();
                Runnable callback;
                while ((callback = postConstructQueue.poll()) != null) {
                    callback.run();
                }
                return result;
            } catch (RuntimeException | Error e) {
                // Roll back so that a retry after bind() does not see half-wired instances, and so that the
                // bindings the discarded instances consumed can be replaced again.
                instances.keySet().retainAll(before);
                resolved.retainAll(resolvedBefore);
                postConstructQueue.clear();
                throw e;
            } finally {
                resolving = false;
            }
        }
    }

    private Object resolve(Class<?> type) {
        Object existing = existing(type);
        if (existing != null) {
            return existing;
        }
        if (!Introspection.isBeanClass(type)) {
            throw new InjectionException("No bean of type " + type.getName()
                    + " is known to this Injector and none was bound; use bind(" + type.getSimpleName()
                    + ".class, ...) to supply an implementation class or an instance"
                    + (isInstantiable(type)
                            ? " (" + type.getSimpleName() + " has none of the supported bean-defining annotations)"
                            : ""));
        }
        return create(type);
    }

    /** The instance created or bound for {@code type}, following a bound implementation class; null if none yet. */
    private Object existing(Class<?> type) {
        Object existing = instances.get(type);
        if (existing == null) {
            Class<?> bound = implementations.get(type);
            existing = bound != null ? resolve(bound) : null;
        }
        if (existing != null) {
            // Handed out: from now on the binding of this type cannot be replaced.
            resolved.add(type);
        }
        return existing;
    }

    private Object create(Class<?> type) {
        BeanInfo bean = Introspection.of(type);
        checkConstructorCycle(type);
        Frame frame = new Frame(type);
        creating.push(frame);
        try {
            Object[] arguments = values(bean.constructorDependencies());
            if (arguments.length > 0) {
                // Resolving constructor arguments may already have created this bean through a dependency cycle.
                Object existing = existing(type);
                if (existing != null) {
                    return existing;
                }
            }
            Object instance = newInstance(bean.constructor(), arguments);
            instances.put(type, instance);
            resolved.add(type);
            frame.constructing = false;
            for (Member member : bean.members()) {
                if (member instanceof FieldMember field) {
                    set(field.field(), instance, value(field.dependency()));
                } else if (member instanceof MethodMember method) {
                    invoke(method.method(), instance, values(method.dependencies()));
                } else {
                    throw new IllegalStateException("Unknown member type: " + member);
                }
            }
            for (Method callback : bean.postConstructs()) {
                postConstructQueue.add(() -> invoke(callback, instance));
            }
            return instance;
        } finally {
            creating.pop();
        }
    }

    /**
     * Creating {@code type} again while it is already being created is fine as long as some bean on the
     * way has left its constructor: that bean is registered, so the inner attempt can complete and the
     * outer one picks the result up after its own constructor arguments are resolved. If every bean on
     * the way is still waiting for constructor arguments, nothing can ever be instantiated.
     */
    private void checkConstructorCycle(Class<?> type) {
        List<String> path = new ArrayList<>();
        for (Frame frame : creating) {
            if (!frame.constructing) {
                return;
            }
            path.add(0, Introspection.name(frame.type));
            if (frame.type == type) {
                path.add(Introspection.name(type));
                throw new InjectionException("Dependency cycle through constructor injection cannot be satisfied: "
                        + String.join(" -> ", path)
                        + ". Inject one of the dependencies into a field or method, or as a Provider");
            }
        }
    }

    private static final class Frame {

        final Class<?> type;
        boolean constructing = true;

        Frame(Class<?> type) {
            this.type = type;
        }
    }

    private Object[] values(List<Dependency> dependencies) {
        Object[] values = new Object[dependencies.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = value(dependencies.get(i));
        }
        return values;
    }

    private Object value(Dependency dependency) {
        return dependency.isProvider() ? provider(dependency) : lookup(dependency);
    }

    private Object lookup(Dependency dependency) {
        Class<?> type = dependency.rawType();
        Object existing = existing(type);
        if (existing != null) {
            return existing;
        }
        if (!Introspection.isBeanClass(type)) {
            throw new InjectionException("Cannot inject " + dependency.description() + ": " + reason(dependency)
                    + ". Register an instance with Injector.bind(" + type.getSimpleName() + ".class, ...) first");
        }
        return create(type);
    }

    private static String reason(Dependency dependency) {
        Class<?> type = dependency.rawType();
        String name = dependency.declaredType().getTypeName();
        if (dependency.isProvider()) {
            name = dependency.rawType().getTypeName();
        }
        if (GenericTypes.isVariableOrWildcard(dependency.declaredType()) || type.isPrimitive() || type.isArray()) {
            return "type " + name + " cannot be satisfied by a bean";
        }
        if (type.isInterface()) {
            return "type " + name + " is an interface and no implementation is bound";
        }
        if (Modifier.isAbstract(type.getModifiers())) {
            return "type " + name + " is abstract and no implementation is bound";
        }
        return "type " + name + " is not a bean (it has none of the supported bean-defining annotations)";
    }

    private static boolean isInstantiable(Class<?> type) {
        return !type.isInterface() && !type.isPrimitive() && !type.isArray() && !type.isEnum() && !type.isAnnotation()
                && !Modifier.isAbstract(type.getModifiers());
    }

    /** A lazy {@code Provider} for the dependency, implemented as a proxy of the declared Provider interface. */
    private Object provider(Dependency dependency) {
        Class<?> providerType = dependency.providerType();
        ClassLoader loader = providerType.getClassLoader() != null
                ? providerType.getClassLoader()
                : Injector.class.getClassLoader();
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
              case "get":
                  return enter(() -> lookup(dependency));
              case "toString":
                  return "Provider<" + dependency.rawType().getName() + ">";
              case "hashCode":
                  return System.identityHashCode(proxy);
              case "equals":
                  return proxy == args[0];
              default:
                  if (method.isDefault()) {
                      return InvocationHandler.invokeDefault(proxy, method, args);
                  }
                  throw new UnsupportedOperationException(method.toString());
            }
        };
        return Proxy.newProxyInstance(loader, new Class<?>[] {providerType}, handler);
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new InjectionException("Cannot inject " + field.getDeclaringClass().getName() + "." + field.getName(),
                    e);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new InjectionException("Cannot invoke " + method.getDeclaringClass().getName() + "."
                    + method.getName(), e);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private static Object newInstance(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException e) {
            throw new InjectionException("Cannot instantiate " + constructor.getDeclaringClass().getName(), e);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    /** Rethrows what user code threw, so that test assertions see the original exception. */
    private static RuntimeException unwrap(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new InjectionException(cause.getMessage(), cause);
    }

    /** Thrown when a bean or one of its dependencies cannot be resolved, created or injected. */
    public static final class InjectionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InjectionException(String message) {
            super(message);
        }

        InjectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
