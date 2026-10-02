# Boxcar IoC :: Reflection Example

A small Jakarta EE-style application tested with the reflection based `com.boxcar.Injector`, set up the way
a real project would be. The application (`src/main/java`) knows nothing about the injector; it is
written against the Jakarta APIs only:

* `GreetingService` — a `@Stateless` bean with an injected repository *interface*, an `@EJB` reference
  to `GreetingLog`, a container-provided dependency (`java.time.Clock`) and a `@PostConstruct` method.
* `InMemoryGreetingRepository` — the only implementation of `GreetingRepository`.
* `GreetingLog` — a `@Singleton`.

## Setup

One test-scoped dependency ([`pom.xml`](pom.xml)):

```xml
<dependency>
    <groupId>com.boxcar</groupId>
    <artifactId>boxcar-ioc-reflection</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

## Tests

[`TestInjector`](src/test/java/com/example/greetings/TestInjector.java) extends `Injector` and binds
the project's defaults in its constructor: a fixed `Clock` for what the container would provide, and
`GreetingRepository` to its implementation, because the reflection injector never scans the class path
and cannot find implementations of an interface on its own. Each test creates a fresh `TestInjector`
and overrides only what it is about — a later `bind` replaces an earlier one until the type is first
resolved.

[`GreetingServiceTest`](src/test/java/com/example/greetings/GreetingServiceTest.java) is **identical**
to the one in [`boxcar-ioc-processor-example`](../boxcar-ioc-processor-example): the tests do not
depend on which implementation is on the class path. The one line that differs between the two
modules is the repository binding in `TestInjector`.
