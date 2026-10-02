# Boxcar IoC :: TCK

The behavioural contract of `com.boxcar.Injector`, as code:

* `com.boxcar.ioc.fixtures.*` — Jakarta EE-style beans (`@Stateless`, `@Singleton`, `@Stateful`,
  `@ApplicationScoped`, `@Inject`, `@EJB`, `Provider`, `@PostConstruct`, `javax.*` legacy names)
  covering field, method and constructor injection, inheritance and generic superclasses, overriding
  rules, non-public members, cycles of every kind and lifecycle failures.
* `com.boxcar.ioc.tck.InjectorApi` — the public API every implementation provides. The two
  implementations cannot share a compile-time type because both are named `com.boxcar.Injector`
  and are meant to be interchangeable on the class path, so the TCK is written against this interface.
* `com.boxcar.ioc.tck.InjectorTck` — an abstract JUnit 5 test class. An implementation module
  subclasses it, implements its two abstract methods — `injector()`, a fresh `Injector` behind a
  small `InjectorApi` adapter, and `injectionExceptionType()`, the implementation's exception type
  for resolutions that cannot be satisfied — and inherits the whole suite.

Where a bean depends on an interface, the tests bind an implementation class explicitly
(`bind(OrderRepository.class, InMemoryOrderRepository.class)`): the reflection implementation cannot
discover implementations without scanning, and the generated one accepts the binding as an override.
Behaviour that only one implementation can offer is tested in that implementation's own module.

The processor module also uses the fixtures to demonstrate test-scoped generation: its test
compilation points the processor at the fixture packages of this jar with
`-Aboxcar.packages=com.boxcar.ioc.fixtures`.
