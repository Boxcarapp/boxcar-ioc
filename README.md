# Boxcar IoC

Container-free dependency injection for unit testing Jakarta EE code. Wires session beans and CDI
components together without starting a container, by runtime reflection or by compile-time code
generation. Test suites run in seconds, not minutes.

```java
Injector injector = new Injector().bind(PaymentGateway.class, amount -> true);
OrderService orders = injector.getInstance(OrderService.class);
```

## What this is, and what it is not

Boxcar IoC is a **test tool**. It exists so that a class written for an application server — annotated
with `@Stateless`, `@Singleton`, `@Stateful` or `@ApplicationScoped`, with `@Inject` and `@EJB`
members and a `@PostConstruct` method — can be instantiated in a plain JUnit test with its
dependencies wired, in microseconds, with no server, no deployment and no class path scanning. Where
the container would have provided something (an `EntityManager`, a `Validator`, an `Event<T>`, an
external service), the test binds a real object, a fake or a mock. Annotations are recognised by
name, under their `jakarta.*` and their legacy `javax.*` names alike, so code written for Java EE 8
or earlier works unchanged, and neither implementation depends on any Jakarta API jar.

It is **not** a Jakarta EE implementation, and it does not try to be one:

* **It is not a CDI or EJB container.** It implements a deliberately small subset of the injection
  rules of Jakarta Inject, CDI and EJB — enough for the common shape of enterprise code — and nothing
  else. If you expect it to pass the Jakarta EE TCKs, you will be disappointed.
* **No proxies, no interception.** Beans are plain instances of your classes; calls between them are
  plain method calls. Container-managed transactions (`@TransactionAttribute`, `@Transactional`),
  security (`@RolesAllowed`), interceptors, decorators and `@Asynchronous` are **not** applied. A test
  that depends on transaction boundaries has to demarcate them itself.
* **No scopes.** `@Stateless`, `@Stateful`, `@Singleton` and `@ApplicationScoped` all mean the same
  thing here: one instance per bean class per `Injector`. There are no request, session or
  conversation scopes; `@Dependent` is not recognised.
* **No qualifiers, alternatives, stereotypes, producers or events.** `@Named` and custom qualifiers
  are ignored; an interface with several implementations is bound by the test. `@Produces`,
  `@Alternative`, `@Specializes`, `@Observes`, `Instance<T>` and `Event<T>` are not supported;
  injection points of those types are simply left for the test to bind.
* **No container resources.** `@Resource`, `@PersistenceContext`, `@PersistenceUnit` and JNDI lookups
  are not injection points. Make the dependency an `@Inject` point (with a producer in production) and
  bind it in the test.
* **Not for production.** Nothing stops you, but there is no lifecycle beyond construction — no
  `@PreDestroy`, no passivation, no pooling — and an `Injector` is meant to live for one test.

The supported subset is listed precisely in the processor module's
[README](boxcar-ioc-processor/README.md#supported-subset); the behavioural contract both
implementations meet is written as code in the [TCK module](boxcar-ioc-tck/README.md) — Boxcar's own
compatibility kit, not Jakarta's.

## Two implementations

Two interchangeable implementations of the same class exist; pick one per project:

| Module | `com.boxcar.Injector` is... | Best when |
| --- | --- | --- |
| [`boxcar-ioc-processor`](boxcar-ioc-processor/README.md) | generated at compile time by an annotation processor that knows every bean, with a typed getter per bean (`getOrderService()`) | you want interfaces and generic types resolved to their implementations automatically, bean definitions validated at build time, and wiring you can read |
| [`boxcar-ioc-reflection`](boxcar-ioc-reflection/README.md) | a runtime library that introspects only the classes it is asked for and the ones they reference | you want no build integration; interfaces are bound to implementations by the test |
| [`boxcar-ioc-tck`](boxcar-ioc-tck/README.md) | (fixtures and an abstract test suite) | verifying that both behave the same |

Two example modules show each implementation set up the way a real project would use it, with the
same application and the **same tests** in both:
[`boxcar-ioc-processor-example`](boxcar-ioc-processor-example/README.md) and
[`boxcar-ioc-reflection-example`](boxcar-ioc-reflection-example/README.md). The only line that differs
between them is the one that tells the reflection injector which class implements the repository
interface. The two implementations share the class name `com.boxcar.Injector`, so a project — or an
example module — has exactly one of them on its class path.

Both implementations expose

```java
public <T> T getInstance(Class<T> type);
public <T> Injector bind(Class<T> type, T instance);
public <T> Injector bind(Class<T> type, Class<? extends T> implementation);
public static final class InjectionException extends RuntimeException;
```

and share the same semantics — the generated one additionally offers a typed getter per bean, at
the price of source compatibility with the reflection one. One instance per bean class per
`Injector`, Jakarta Inject ordering and overriding rules, `Provider<T>`, dependency cycles closed
through field and method injection after the instances exist, `@PostConstruct` once a bean's
dependencies (in a cycle: the whole cycle) are wired. A dependency that cannot be satisfied is
reported by `InjectionException` naming the injection point and the `bind` call to make; an
unchecked exception thrown by bean code (a constructor, an `@Inject` method or a `@PostConstruct`
callback) propagates unwrapped. Either way the failed resolution is rolled back, so a test can bind
what was missing and retry on the same injector.

`bind(type, instance)` accepts any object. `bind(type, implementation)` requires a bean class that is
assignable to `type` and different from it, and throws `IllegalArgumentException` otherwise. A type
can be bound again until it is first resolved, the later binding replacing the earlier one; once an
instance has been created, injected or returned, binding the type throws `IllegalStateException`. A
type reached only through a `Provider<T>` is not resolved until `get()` is called, so it can still be
bound, and the provider sees the new binding. `Injector` is not final, so a project can keep its
default bindings in a subclass and let individual tests override the few they care about:

```java
public class ServiceInjector extends Injector {
    public ServiceInjector() {
        bind(EntityManager.class, mock(EntityManager.class));
        bind(PaymentGateway.class, amount -> true);
    }
}

OrderService orders = new ServiceInjector()
        .bind(PaymentGateway.class, amount -> false)   // this test wants declined payments
        .getInstance(OrderService.class);
```

## Building

```
mvn verify
```

Requires JDK 17 or later, as does using either implementation: all artifacts are compiled with
`--release 17`. The TCK module is built first, then each implementation module runs the TCK against
its own `Injector` alongside its implementation-specific tests, and finally the two example modules
consume the freshly built artifacts exactly as a user would. "TCK" here means Boxcar's own
compatibility kit for its two implementations; it has nothing to do with the Jakarta EE TCKs.
