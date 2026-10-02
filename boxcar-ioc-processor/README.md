# Boxcar IoC :: Processor

An annotation processor that generates a small, container-free dependency injector for Jakarta EE code, so
that `@Stateless`, `@Singleton` and friends can be wired together in plain unit tests without an
application server. It is one of two interchangeable implementations of `com.boxcar.Injector`;
the other, [`boxcar-ioc-reflection`](../boxcar-ioc-reflection/README.md), does the same
work with reflection at runtime. Both pass the shared [TCK](../boxcar-ioc-tck/README.md).

At compile time the processor finds every bean class, resolves each `@Inject` point to the bean that
satisfies it, detects dependency cycles, and emits a single class:

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

Nothing is scanned at runtime and the generated class depends on the JDK only.

## Usage

```java
@Test
void placesOrders() {
    Injector injector = new Injector()
            .bind(PaymentGateway.class, amount -> true);   // container-provided in production

    OrderService orders = injector.getInstance(OrderService.class);

    assertTrue(orders.placeOrder("book", new BigDecimal("10.00")));
}
```

Each `Injector` owns one instance per bean class (a `Map<Class<?>, Object>`), created lazily on
first request together with its transitive dependencies. Create a new `Injector` per test for
isolation. `bind(type, instance)` registers an existing object, typically a mock, to be returned and
injected wherever its type is requested; `bind(type, implementation)` picks which bean class to
instantiate for a type, for example one of several implementations of an interface, and accepts only
the beans the injector was generated for (test sources included), rejecting anything else with
`IllegalArgumentException`. Bindings take precedence over what the processor resolved. A type can be
bound again until it is first resolved, the later binding replacing the earlier one; once an instance
has been created, injected or returned, binding the type throws `IllegalStateException`. `Injector`
is not final, so a subclass can bind a project's defaults in its constructor for tests to override
(see the [root README](../README.md)).

### Build setup

The processor requires JDK 17 or later to run and has no runtime dependencies. The source it
generates uses no language feature newer than Java 8, so the compilation it runs in may target any
`--release` from 8 up. There are two ways to run it:

**Alongside the beans** (the injector ends up next to your production classes):

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>com.boxcar</groupId>
                <artifactId>boxcar-ioc-processor</artifactId>
                <version>1.0-SNAPSHOT</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

**In the test compilation only**, pointing the processor at the already compiled beans on the class
path. The simplest setup is a test-scoped dependency: javac then discovers the processor through
`META-INF/services`, and because the processor shares the compile class path it can find every class
below the configured package prefixes on its own:

```xml
<dependency>
    <groupId>com.boxcar</groupId>
    <artifactId>boxcar-ioc-processor</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

```xml
<execution>
    <id>default-testCompile</id>
    <configuration>
        <compilerArgs>
            <arg>-Aboxcar.packages=com.acme</arg>
        </compilerArgs>
    </configuration>
</execution>
```

Prefixes are recursive: `com.acme` covers `com.acme.service.impl`. Several prefixes are separated by
commas. A prefix that is not found is reported with a warning.

If the processor is configured through `<annotationProcessorPaths>` instead, it runs from an
isolated class loader and the compile class path is not visible to it (the annotation processing API
offers no way to list it). Tell it where the classes are with `-Aboxcar.classpath`, a list of
directories and jars separated by the platform path separator or commas:

```xml
<compilerArgs>
    <arg>-Aboxcar.packages=com.acme</arg>
    <arg>-Aboxcar.classpath=${project.build.outputDirectory}</arg>
</compilerArgs>
```

Add the jars of other modules to the list when their beans should be managed too. Without a scannable
root the processor falls back to the compiler's own view of the package, which contains the package's
classes but not its subpackages, and says so in a warning.

Note that `<annotationProcessorPaths>` also switches off processor discovery for every *other*
processor on the class path (MapStruct, QueryDSL, Hibernate Validator, ...): with it, every processor
the compilation needs has to be listed. The test-scoped dependency setup above avoids this.

Test sources annotated as beans are picked up in either mode, which is handy for test doubles.

## Supported subset

Annotations are matched by name; both `jakarta.*` and the legacy `javax.*` names are recognised.

| Purpose | Annotations | Semantics |
| --- | --- | --- |
| Bean-defining (class) | `@jakarta.inject.Singleton`, `@jakarta.ejb.Singleton`, `@jakarta.ejb.Stateless`, `@jakarta.ejb.Stateful`, `@jakarta.enterprise.context.ApplicationScoped` | One shared instance per `Injector`, regardless of the annotation. |
| Injection point | `@jakarta.inject.Inject` on constructors, fields and methods; `@jakarta.ejb.EJB` on fields and setter methods | Constructor first, then fields, then methods; superclass members before subclass members. |
| Lazy dependency | `jakarta.inject.Provider<T>` | `get()` resolves through the injector on each call; breaks constructor cycles. |
| Lifecycle | `@jakarta.annotation.PostConstruct` | Invoked once the outermost `getInstance` call has wired everything, dependencies first. |

Bean classes must be concrete, public (including enclosing classes), static if nested, in a named
package, and have either one `@Inject` constructor or a no-argument constructor of any visibility.
Abstract annotated classes are not beans themselves but their members are injected into subclasses.

Method overriding follows the Jakarta Inject rules: a method overriding an `@Inject` method is
injected once, and only if it is itself annotated. The same applies to `@PostConstruct`. Type
variables of generic superclasses are substituted per bean, so `@Inject Repository<T>` in a base
class resolves to a different bean in `UserService extends Base<User>` and `OrderService extends
Base<Order>`.

Public members whose declared types are public too are accessed directly, so javac type-checks that
part of the wiring; everything else (non-public constructors, fields and methods, and members whose
type the generated class cannot name) is injected reflectively through `setAccessible`.

## Resolution rules

For an injection point of type `T`:

1. Exactly one bean assignable to `T` satisfies it.
2. If several beans qualify and one of them is exactly `T`, that one wins.
3. Otherwise the injection point is **ambiguous**, and if nothing qualifies it is **unsatisfied**.

Neither outcome of rule 3 is a compile-time diagnostic at all, not even a warning (a build with
`-Werror` must not fail over them): qualifiers are ignored and container-provided types
(`EntityManager`, `Logger`, `Event<...>`) have no bean, so tests supply them with `bind`, either an
instance or a bean class. If a test forgets, `getInstance` throws `Injector.InjectionException`
naming the injection point, the reason recorded at compile time, and the exact `bind` call to make;
the failed resolution is rolled back, so binding and retrying on the same injector works. The
exception is the better place for this information: it fires only for the part of the graph a test
actually reaches, instead of listing every container dependency in the code base on every build.

`getInstance(Class)` accepts a bean class, any supertype implemented by exactly one bean (or by the
bean of exactly that type), or any type passed to `bind`.

## Cycles

Instances are registered before their fields and methods are injected, so a cycle simply finds the
partially initialised instance and is closed by field or method injection after both objects exist.
Constructor arguments are resolved before construction, and the injector re-checks its map
afterwards because resolving them may already have created the bean through such a mixed cycle.

The processor runs a depth-first search over the dependency graph:

* A cycle that runs only through constructor parameters can never be created and is a **compile
  error**. Inject one side as a field, a method parameter, or a `Provider<T>`.
* Every other cycle is reported as a *note* and resolved lazily as described above.

Because `@PostConstruct` callbacks run after the whole graph is wired, they see fully injected
dependencies even inside cycles.

## Not supported

Qualifiers (`@Named`, custom qualifiers), `@Alternative`, `@Specializes`, interceptors, decorators,
producers, `@Resource`, `@PersistenceContext`, `Instance<T>`, `Event<T>` and other container
services. Injection points of those types are simply unresolved and can be bound manually. Beans
generated by other annotation processors in the same compilation appear after the injector has been
generated and are reported with a warning; compile them first and use `-Aboxcar.packages`.

## Building

```
mvn verify            # from the repository root; builds the TCK, both implementations and both examples
```

The module compiles the processor with `-proc:none` and then lets its test compilation discover the
processor from `target/classes`. The fixture beans live in the TCK jar, so the test compilation uses
`-Aboxcar.packages=com.boxcar.ioc.fixtures` to generate an `Injector` for all of them, which is
exactly how a test-scoped setup in a real project looks. `GeneratedInjectorTckTest` then runs the
shared TCK against that injector, `GeneratedInjectorTest` covers what only a compile-time injector
can do (interface and generic resolution without bindings, ambiguity candidates, non-public types),
and `InjectorProcessorTest` runs javac in-process to check diagnostics, the generated source and
both ways of scanning the class path.
