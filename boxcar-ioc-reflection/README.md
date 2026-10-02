# Boxcar IoC :: Reflection

A container-free dependency injector for Jakarta EE code, implemented with reflection at runtime. It is one
of two interchangeable implementations of `com.boxcar.Injector`; the other,
[`boxcar-ioc-processor`](../boxcar-ioc-processor/README.md), generates the injector at
compile time. Both pass the shared [TCK](../boxcar-ioc-tck/README.md), so tests written against
one run unchanged against the other.

```java
package com.boxcar;

public class Injector {
    public Injector() { ... }
    public <T> T getInstance(Class<T> type) { ... }
    public <T> Injector bind(Class<T> type, T instance) { ... }
    public <T> Injector bind(Class<T> type, Class<? extends T> implementation) { ... }
    public static final class InjectionException extends RuntimeException { ... }
}
```

## Nothing is scanned

The point of testing outside the container is speed, and a container spends its start-up time
discovering beans: it scans every class in the deployment. This injector never does. It introspects
exactly three kinds of classes:

* the class passed to `getInstance`,
* the class passed to `bind(type, implementation)`,
* the classes reached through the injection points of a bean while it is being created.

A class is introspected once per JVM (constructor, `@Inject`/`@EJB` fields and methods,
`@PostConstruct` callbacks, along its superclass chain) and the result is cached, so a test suite
pays for each bean class once, however many `Injector`s it creates. Wiring a graph of seven beans
takes on the order of ten microseconds.

The price of not scanning is that the injector cannot find the implementation of an interface on
its own. Where a bean depends on an interface or abstract class, the test says which class or
instance to use:

```java
@Test
void placesOrders() {
    Injector injector = new Injector()
            .bind(OrderRepository.class, InMemoryOrderRepository.class)   // one of the production beans
            .bind(PaymentGateway.class, amount -> true);                  // container-provided in production

    OrderService orders = injector.getInstance(OrderService.class);

    assertTrue(orders.placeOrder("book", new BigDecimal("10.00")));
}
```

Forgetting a binding is reported by `Injector.InjectionException` naming the injection point, why it
could not be satisfied, and the `bind` call to make. The failed resolution is rolled back, so binding
and retrying on the same injector works.

A type can be bound again until it is first resolved, the later binding replacing the earlier one;
once an instance has been created, injected or returned, binding the type throws
`IllegalStateException`. `Injector` is not final, so a subclass can bind a project's defaults in its
constructor for tests to override (see the [root README](../README.md)).

## Usage

```xml
<dependency>
    <groupId>com.boxcar</groupId>
    <artifactId>boxcar-ioc-reflection</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

The module requires Java 17 or later and has no dependencies of its own: annotations are recognised
by name, so it works with whichever `jakarta.*` (or legacy `javax.*`) API jars the code under test
already uses. Private members are injected through `setAccessible`, which requires the tested code
to be on the class path or in a module opened to `com.boxcar`.

## Semantics

Identical to the processor implementation; see its [README](../boxcar-ioc-processor/README.md)
for the details. In short:

* Bean classes are concrete classes annotated with `@Singleton` (Jakarta Inject or EJB),
  `@Stateless`, `@Stateful` or `@ApplicationScoped`; each `Injector` holds one instance per bean
  class. Interfaces and abstract classes must be bound.
* `@Inject` constructors, fields and methods and `@EJB` fields and setters are injected in
  specification order, superclass members first, with generic superclass type variables
  substituted. A method overriding an `@Inject` method is injected once, and only if annotated.
* `Provider<T>` is injected as a lazy proxy of the declared `Provider` interface.
* Dependency cycles are closed by field or method injection after the instances exist; cycles that
  run only through constructors are reported with their path. `@PostConstruct` callbacks run after
  the whole graph is wired, dependencies first.

The differences are what only compile-time knowledge makes possible: the processor resolves
interfaces and generic types to their single implementation without bindings, reports ambiguity
candidates by name, and validates bean definitions at build time. Here, invalid definitions (two
`@Inject` constructors, static or final injection points, non-static inner classes, ...) are
reported by `InjectionException` when the class is first needed. Conversely, this implementation
does not require bean classes to be public or to live in a named package, and
`bind(type, implementation)` accepts any bean class, not only the ones known at build time.
