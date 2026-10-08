package com.boxcar.ioc.processor;

/*-
 * #%L
 * Boxcar IoC :: Processor
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic.Kind;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compile-time behaviour of {@link InjectorProcessor}: validation errors, warnings, notes and options. */
class InjectorProcessorTest {

    @TempDir
    Path workDir;

    @Test
    void constructorOnlyCycleIsAnError() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.A", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class A {
                            @jakarta.inject.Inject public A(B b) {}
                        }
                        """,
                "app.B", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class B {
                            @jakarta.inject.Inject public B(C c) {}
                        }
                        """,
                "app.C", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class C {
                            @jakarta.inject.Inject public C(A a) {}
                        }
                        """));

        assertFalse(result.success());
        List<String> errors = result.messages(Kind.ERROR);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("Dependency cycle through constructor injection"), errors.get(0));
        assertTrue(errors.get(0).contains("app.A -> app.B -> app.C -> app.A"), errors.get(0));
    }

    @Test
    void cycleWithFieldEdgeIsOnlyNote() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.A", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class A {
                            @jakarta.inject.Inject public A(B b) {}
                        }
                        """,
                "app.B", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class B {
                            @jakarta.inject.Inject A a;
                        }
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        assertTrue(result.ofKind(Kind.ERROR).isEmpty());
        assertTrue(result.hasMessage(Kind.NOTE, "closed after construction"), result.messages(Kind.NOTE).toString());
    }

    @Test
    void cycleMembersAreConstructedBeforeAnyIsInjectedAndInjectedBeforeAnyCallback() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.A", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class A {
                            @jakarta.inject.Inject public B b;
                            @jakarta.annotation.PostConstruct public void init() {}
                        }
                        """,
                "app.B", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class B {
                            @jakarta.inject.Inject public void setA(A a) {}
                            @jakarta.annotation.PostConstruct public void init() {}
                        }
                        """,
                "app.C", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class C {
                            @jakarta.inject.Inject public C(A a) {}
                        }
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        String injector = result.generatedInjector();
        // Members: both constructed and stored, then injected into each other, then the callbacks: B first,
        // because A depends on it.
        assertInOrder(injector,
                "private void createCycle() {",
                "A a = existing(A.class);",
                "boolean newA = a == null;",
                "a = new A();",
                "instances.put(A.class, a);",
                "B b = existing(B.class);",
                "b = new B();",
                "instances.put(B.class, b);",
                "if (newA) {",
                "a.b = b;",
                "if (newB) {",
                "b.setA(a);",
                "if (newB) {",
                "b.init();",
                "if (newA) {",
                "a.init();");
        // Each member's factory delegates to the group.
        assertInOrder(injector,
                "public A getA() {",
                "A a = existing(A.class);",
                "createCycle();",
                "a = (A) instances.get(A.class);");
        // A bean outside the cycle simply calls the factory of what it depends on.
        assertTrue(injector.contains("c = new C(getA());"), injector);
    }

    @Test
    void mixedConstructorAndFieldCycleConstructsTheConstructorDependencyFirst() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Gamma", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Gamma {
                            @jakarta.inject.Inject public Gamma(Delta delta) {}
                        }
                        """,
                "app.Delta", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Delta {
                            @jakarta.inject.Inject public Gamma gamma;
                        }
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        assertInOrder(result.generatedInjector(),
                "private void createCycle() {",
                "delta = new Delta();",
                "gamma = new Gamma(delta);",
                "delta.gamma = gamma;");
    }

    @Test
    void cycleGroupsAreNumberedWhenThereAreSeveral() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.A", ring("A", "B"), "app.B", ring("B", "C"), "app.C", ring("C", "A"),
                "app.P", ring("P", "Q"), "app.Q", ring("Q", "R"), "app.R", ring("R", "S"), "app.S", ring("S", "P")));

        assertTrue(result.success(), result.diagnostics().toString());
        String injector = result.generatedInjector();
        // The method's Javadoc names the members; a getter names the others only in a small group.
        assertInOrder(injector,
                "/**",
                " * Creates {@link A}, {@link B} and {@link C}, which depend on each other:",
                "private void createCycle1() {");
        assertInOrder(injector,
                "/** Created together with {@link B} and {@link C}, see {@link #createCycle1}. */",
                "public A getA() {",
                "createCycle1();");
        assertInOrder(injector,
                " * Creates {@link P}, {@link Q}, {@link R} and {@link S}, which depend on each other:",
                "private void createCycle2() {");
        assertInOrder(injector,
                "/** Created together with 3 other beans, see {@link #createCycle2}. */",
                "public P getP() {",
                "createCycle2();");
        assertFalse(injector.contains("createCycle()"), injector);
        assertFalse(injector.contains("{@link\n"), "an inline tag is never split over lines:\n" + injector);
    }

    /** A bean that closes a cycle by having the next bean of a ring injected into a field. */
    private static String ring(String name, String next) {
        return """
                package app;
                @jakarta.ejb.Stateless
                public class %s {
                    @jakarta.inject.Inject public %s %s;
                }
                """.formatted(name, next, next.toLowerCase());
    }

    @Test
    void acyclicBeansUseConstructorInjectionWithoutAnyCycleMachinery() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Config", """
                        package app;
                        @jakarta.inject.Singleton
                        public class Config {}
                        """,
                "app.Service", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Service {
                            @jakarta.inject.Inject
                            public Service(Config config, jakarta.inject.Provider<Config> lazy) {}
                            @jakarta.inject.Inject public Config self;
                        }
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        String injector = result.generatedInjector();
        assertInOrder(injector,
                "import app.Config;",
                "import app.Service;",
                "import jakarta.inject.Provider;",
                "public Service getService() {",
                "Service service = existing(Service.class);",
                "if (service != null) {",
                "return service;",
                "service = new Service(getConfig(), new Provider<Config>() {",
                "public Config get() {",
                "return getConfig();",
                "});",
                "instances.put(Service.class, service);",
                "service.self = getConfig();",
                "return service;");
        assertFalse(injector.contains("createCycle"), "no group method for acyclic beans");
    }

    @Test
    void interfaceAndUnboundDependenciesGetResolverMethodsAndLookupIsStatic() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Repo", """
                        package app;
                        public interface Repo {}
                        """,
                "app.JpaRepo", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class JpaRepo implements Repo {}
                        """,
                "app.Service", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Service {
                            @jakarta.inject.Inject public Repo repo;
                            @jakarta.inject.Inject public java.time.Clock clock;
                        }
                        """,
                "app.Other", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Other {
                            @jakarta.inject.Inject public java.time.Clock clock;
                        }
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        String injector = result.generatedInjector();
        // The interface resolves to its implementation unless the test bound something for it.
        assertInOrder(injector,
                "/** {@code Repo} is implemented by {@link JpaRepo}. */",
                "public Repo getRepo() {",
                "Repo repo = existing(Repo.class);",
                "return repo != null ? repo : getJpaRepo();");
        // A type without a bean has one resolver naming every injection point that needs it.
        assertInOrder(injector,
                "public Clock getClock() {",
                "Clock clock = existing(Clock.class);",
                "if (clock == null) {",
                "throw new InjectionException(\"No Clock was bound, but Other.clock, Service.clock needs one",
                "Injector.bind(Clock.class,",
                "...) first\");");
        assertTrue(injector.contains("service.repo = getRepo();"), injector);
        assertTrue(injector.contains("service.clock = getClock();"), injector);
        // getInstance dispatches straight to the getters of the types known at compile time, and nothing else.
        assertInOrder(injector,
                "public <T> T getInstance(Class<T> type) {",
                "if (type == JpaRepo.class) {",
                "return type.cast(getJpaRepo());",
                "if (type == Repo.class) {",
                "return type.cast(getRepo());",
                "T bound = existing(Objects.requireNonNull(type, \"type\"));",
                "throw new InjectionException(\"No bean of type \" + type.getName()");
        assertFalse(injector.contains("lookup("), "no dispatch layer between getInstance and the getters");
        assertFalse(injector.contains("Map<Class<?>, Class<?>> BINDINGS"), "no runtime lookup table");
        assertFalse(injector.contains("if (type == Clock.class)"), "an unbound type is only reachable through bind");
    }

    /** Asserts that the fragments occur in {@code text} in the given order, each after the previous one. */
    private static void assertInOrder(String text, String... fragments) {
        int from = 0;
        for (String fragment : fragments) {
            int index = text.indexOf(fragment, from);
            assertTrue(index >= 0, "expected \"" + fragment + "\" after position " + from + " in:\n" + text);
            from = index + fragment.length();
        }
    }

    @Test
    void providerEdgesDoNotFormConstructorCycles() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.A", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class A {
                            @jakarta.inject.Inject public A(jakarta.inject.Provider<B> b) {}
                        }
                        """,
                "app.B", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class B {
                            @jakarta.inject.Inject public B(A a) {}
                        }
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        assertTrue(result.ofKind(Kind.NOTE).isEmpty(), "no cycle at all once the provider edge is ignored");
    }

    @Test
    void beanClassesMustBePublicStaticAndInstantiable() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Hidden", """
                        package app;
                        @jakarta.ejb.Stateless
                        class Hidden {}
                        """,
                "app.Outer", """
                        package app;
                        public class Outer {
                            @jakarta.inject.Singleton
                            public class Inner {}
                            @jakarta.inject.Singleton
                            public interface Contract {}
                            @jakarta.inject.Singleton
                            public enum Choice { ONE }
                        }
                        """,
                "app.TwoConstructors", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class TwoConstructors {
                            @jakarta.inject.Inject public TwoConstructors(String a) {}
                            @jakarta.inject.Inject public TwoConstructors(Integer b) {}
                        }
                        """,
                "app.NoUsableConstructor", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class NoUsableConstructor {
                            public NoUsableConstructor(String a) {}
                        }
                        """,
                "Unpackaged", """
                        @jakarta.ejb.Stateless
                        public class Unpackaged {}
                        """));

        assertFalse(result.success());
        List<String> errors = result.messages(Kind.ERROR);
        assertTrue(errors.stream().anyMatch(m -> m.contains("app.Hidden must be public")), errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("Unpackaged is in the default package")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("app.Outer.Inner is a non-static inner class")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(
                m -> m.contains("@Singleton can only be applied to classes; app.Outer.Contract is interface")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("app.Outer.Choice is enum")), errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("has 2 @Inject constructors")), errors.toString());
        assertTrue(errors.stream().anyMatch(
                m -> m.contains("neither an @Inject annotated constructor nor a no-argument constructor")),
                errors.toString());
        assertEquals(7, errors.size(), errors.toString());
    }

    @Test
    void injectionPointsAreValidated() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Dep", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Dep {}
                        """,
                "app.Bean", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Bean {
                            @jakarta.inject.Inject static Dep staticField;
                            @jakarta.inject.Inject final Dep finalField = null;
                            @jakarta.inject.Inject static void staticMethod(Dep d) {}
                            @jakarta.inject.Inject <T> void genericMethod(Dep d) {}
                            @jakarta.ejb.EJB void notASetter(Dep a, Dep b) {}
                            @jakarta.annotation.PostConstruct void first() {}
                            @jakarta.annotation.PostConstruct void second() {}
                        }
                        """,
                "app.Other", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Other {
                            @jakarta.annotation.PostConstruct void withArgs(Dep d) {}
                        }
                        """));

        assertFalse(result.success());
        List<String> errors = result.messages(Kind.ERROR);
        assertTrue(errors.stream().anyMatch(m -> m.contains("Bean.staticField is static")), errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("Bean.finalField is final")), errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("Bean.staticMethod(...) is static")), errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("Bean.genericMethod(...) declares type parameters")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("@EJB method app.Bean.notASetter(...) must be a setter")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("declares 2 @PostConstruct methods (first, second)")),
                errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("Other.withArgs() must not take parameters")),
                errors.toString());
        assertEquals(7, errors.size(), errors.toString());
    }

    @Test
    void unresolvableInjectionPointsAreSilentAtCompileTimeAndReportedAtRuntime() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Bean", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Bean {
                            @jakarta.inject.Inject java.util.logging.Logger logger;
                            @jakarta.inject.Inject jakarta.inject.Provider<Runnable> runnable;
                        }
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty(),
                "a test binds these; -Werror builds must not fail: " + result.diagnostics());

        // The reason is carried into the generated code, where InjectionException reports it when the point is still
        // unbound.
        String injector = result.generatedInjector();
        assertNotNull(injector);
        assertInOrder(injector,
                "public Logger getLogger() {",
                "throw new InjectionException(\"No Logger was bound, but Bean.logger needs one");
        assertInOrder(injector,
                "set(field(Bean.class, \"runnable\"), bean, new Provider<Runnable>() {",
                "public Runnable get() {",
                "return getRunnable();",
                "});");
        assertInOrder(injector,
                "public Runnable getRunnable() {",
                "throw new InjectionException(\"No Runnable was bound, but Bean.runnable needs one");
    }

    @Test
    void typesSharingSimpleNamesStayQualifiedAndGetQualifiedFactoryNames() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "billing.Config", """
                        package billing;
                        @jakarta.inject.Singleton
                        public class Config {}
                        """,
                "shipping.Config", """
                        package shipping;
                        @jakarta.inject.Singleton
                        public class Config {}
                        """,
                "app.Service", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Service {
                            @jakarta.inject.Inject public billing.Config billing;
                            @jakarta.inject.Inject public shipping.Config shipping;
                            @jakarta.inject.Inject public java.lang.Thread thread;
                        }
                        """,
                "app.Thread", """
                        package app;
                        @jakarta.inject.Singleton
                        public class Thread {}
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        String injector = result.generatedInjector();
        assertFalse(injector.contains("import billing.Config;"), injector);
        assertFalse(injector.contains("import shipping.Config;"), injector);
        assertTrue(injector.contains("service.billing = getBillingConfig();"), injector);
        assertTrue(injector.contains("service.shipping = getShippingConfig();"), injector);
        assertFalse(injector.contains("import app.Thread;"), "must not shadow java.lang.Thread");
        assertTrue(injector.contains("public app.Thread getThread() {"), injector);
        assertTrue(injector.contains("service.thread = getThread_2();"), "the java.lang.Thread injection point");
    }

    @Test
    void nothingIsGeneratedWithoutBeans() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Plain", """
                        package app;
                        public class Plain {
                            @jakarta.inject.Inject String notABean;
                        }
                        """));

        assertTrue(result.success());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertNull(result.generatedInjector());
    }

    @Test
    void missingTypesDoNotCrashTheProcessor() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Bean", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Bean {
                            @jakarta.inject.Inject DoesNotExist missing;
                        }
                        """));

        assertFalse(result.success());
        List<String> errors = result.messages(Kind.ERROR);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("DoesNotExist"), errors.get(0));
        assertNull(result.generatedInjector(), "generation is deferred until the type resolves");
    }

    @Test
    void packagesOptionScansPrefixesRecursivelyThroughTheClasspathOption() throws Exception {
        Compilation.Result stage1 = compileLibrary();

        // Stage 2: a "test" compilation with no annotated sources of its own. The processor runs in-process
        // here, so its class loader cannot see the library classes; -Aboxcar.classpath points it at them,
        // and the single prefix "lib" must cover lib.nested and lib.nested.deeper too. One jar, one directory.
        Path tests = Files.createDirectories(workDir.resolve("tests"));
        Compilation.Result stage2 = Compilation.compileWithProcessor(tests, Map.of("test.ServiceTest", SERVICE_TEST),
                List.of(stage1.classes()),
                "-Aboxcar.packages=lib, lib.missing",
                "-Aboxcar.classpath=" + stage1.classes() + File.pathSeparator + jarOf(stage1.classes())
                        + File.pathSeparator + workDir.resolve("does-not-exist"));

        assertTrue(stage2.success(), stage2.diagnostics().toString());
        List<String> warnings = stage2.messages(Kind.WARNING);
        assertTrue(stage2.hasMessage(Kind.WARNING, "Package lib.missing listed in -Aboxcar.packages was not found"),
                warnings.toString());
        assertTrue(stage2.hasMessage(Kind.WARNING, "does-not-exist does not exist"), warnings.toString());
        assertEquals(2, warnings.size(), warnings.toString());
        String injector = stage2.generatedInjector();
        assertNotNull(injector);
        assertTrue(injector.contains("return repo != null ? repo : getJpaRepo();"), injector);
        assertTrue(injector.contains("lib.nested.Deep"), injector);
        assertTrue(injector.contains("lib.nested.deeper.Deepest"), injector);
        assertTrue(injector.contains("lib.Outer.Nested"),
                "nested static bean classes are found through their outer class");
        assertEquals(1, countOccurrences(injector, "public Outer.Nested getNested() {"), "and only once");

        // Parent is the platform loader so that the fixture Injector on the test class path is not picked up instead.
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {stage2.classes().toUri().toURL(), stage1.classes().toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Method run = loader.loadClass("test.ServiceTest").getMethod("run");
            assertEquals("jpa", run.invoke(null));
        }
    }

    @Test
    void packagesOptionScansThroughTheProcessorClassLoaderWhenOnTheClassPath() throws Exception {
        Compilation.Result stage1 = compileLibrary();

        // The processor on the compile class path (Maven's default) sees the whole class path through its own
        // loader, so no -Aboxcar.classpath is needed. Simulated by loading the processor from a loader that
        // also contains the library classes.
        Path tests = Files.createDirectories(workDir.resolve("tests"));
        Compilation.Result stage2 = Compilation.compileWithProcessorLoadedFrom(tests, List.of(stage1.classes()),
                Map.of("test.ServiceTest", SERVICE_TEST), List.of(stage1.classes()), "-Aboxcar.packages=lib");

        assertTrue(stage2.success(), stage2.diagnostics().toString());
        assertTrue(stage2.ofKind(Kind.WARNING).isEmpty(), stage2.messages(Kind.WARNING).toString());
        String injector = stage2.generatedInjector();
        assertNotNull(injector);
        assertTrue(injector.contains("lib.nested.deeper.Deepest"), injector);
    }

    @Test
    void unscannablePackageFallsBackToItsOwnClassesWithWarning() throws Exception {
        Compilation.Result stage1 = compileLibrary();

        // No classpath option and an in-process processor: "lib" is visible to javac but on no scannable root.
        Path tests = Files.createDirectories(workDir.resolve("tests"));
        Compilation.Result stage2 = Compilation.compileWithProcessor(tests, Map.of("test.ServiceTest", SERVICE_TEST),
                List.of(stage1.classes()), "-Aboxcar.packages=lib");

        assertTrue(stage2.success(), stage2.diagnostics().toString());
        assertTrue(stage2.hasMessage(Kind.WARNING, "Package lib listed in -Aboxcar.packages is not on a class path"
                + " root the processor can scan, so only its own classes were considered, not its subpackages"),
                stage2.messages(Kind.WARNING).toString());
        String injector = stage2.generatedInjector();
        assertNotNull(injector);
        assertTrue(injector.contains("lib.JpaRepo"), injector);
        assertFalse(injector.contains("lib.nested.Deep"), "subpackages cannot be reached without a scannable root");
    }

    private static final String SERVICE_TEST = """
            package test;
            public class ServiceTest {
                public static Object run() {
                    return new com.boxcar.Injector().getInstance(lib.Service.class).repo().name();
                }
            }
            """;

    /** "Production" beans compiled on their own, without the processor: three package levels and a nested bean. */
    private Compilation.Result compileLibrary() throws IOException {
        Path production = Files.createDirectories(workDir.resolve("production"));
        Compilation.Result stage1 = Compilation.withoutProcessor(production, Map.of(
                "lib.Repo", """
                        package lib;
                        public interface Repo { String name(); }
                        """,
                "lib.JpaRepo", """
                        package lib;
                        @jakarta.ejb.Stateless
                        public class JpaRepo implements Repo {
                            public String name() { return "jpa"; }
                        }
                        """,
                "lib.Service", """
                        package lib;
                        @jakarta.ejb.Stateless
                        public class Service {
                            @jakarta.inject.Inject private Repo repo;
                            public Repo repo() { return repo; }
                        }
                        """,
                "lib.Outer", """
                        package lib;
                        public class Outer {
                            @jakarta.inject.Singleton
                            public static class Nested {}
                        }
                        """,
                "lib.nested.Deep", """
                        package lib.nested;
                        @jakarta.inject.Singleton
                        public class Deep {}
                        """,
                "lib.nested.deeper.Deepest", """
                        package lib.nested.deeper;
                        @jakarta.inject.Singleton
                        public class Deepest {}
                        """,
                "lib.nested.package-info", """
                        package lib.nested;
                        """));
        assertTrue(stage1.success(), stage1.diagnostics().toString());
        return stage1;
    }

    private Path jarOf(Path classes) throws IOException {
        Path jar = workDir.resolve("library.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> files = Files.walk(classes)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        for (int index = text.indexOf(fragment); index >= 0; index = text.indexOf(fragment, index + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void generatedCodeUsesDirectAccessForPublicMembersAndReflectionOtherwise() throws IOException {
        Compilation.Result result = Compilation.withProcessor(workDir, Map.of(
                "app.Dep", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Dep {}
                        """,
                "app.Bean", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Bean {
                            @jakarta.inject.Inject public Dep publicField;
                            @jakarta.inject.Inject private Dep privateField;
                            @jakarta.inject.Inject public void publicSetter(Dep dep) {}
                            @jakarta.inject.Inject void packageSetter(Dep dep) {}
                            @jakarta.annotation.PostConstruct public void init() {}
                        }
                        """));

        assertTrue(result.success(), result.diagnostics().toString());
        String injector = result.generatedInjector();
        assertTrue(injector.contains("bean.publicField = getDep();"), injector);
        assertTrue(injector.contains("set(field(Bean.class, \"privateField\"), bean, getDep());"), injector);
        assertTrue(injector.contains("bean.publicSetter(getDep());"), injector);
        assertTrue(injector.contains("invoke(method(Bean.class, \"packageSetter\", Dep.class), bean, getDep());"),
                injector);
        assertTrue(injector.contains("bean.init();"), injector);
        assertFalse(injector.contains("->"), "generated code uses no lambdas");
        assertFalse(injector.contains("::"), "generated code uses no method references");
        assertFalse(injector.contains("java.util.Map<"), "JDK types are imported like everything else");
    }

    @Test
    void generatedCodeCompilesAtOlderSourceLevels() throws IOException {
        // The generated class is compiled at the user's -source level, not the processor's, so it must not use
        // anything newer than Java 8. Java 11 is the oldest level a Jakarta EE 10 project can be compiled at.
        Compilation.Result release11 = compileBeanAtRelease("11");
        assertTrue(release11.success(), release11.diagnostics().toString());
        assertTrue(release11.generatedInjector().contains("import javax.annotation.processing.Generated;"),
                release11.generatedInjector());

        // Java 8 has no javax.annotation.processing.Generated yet, so the pre-9 annotation is used instead.
        assumeTrue(ToolProvider.getSystemJavaCompiler().getSourceVersions().contains(SourceVersion.RELEASE_8),
                "this javac no longer compiles for Java 8");
        Compilation.Result release8 = compileBeanAtRelease("8");
        assertTrue(release8.success(), release8.diagnostics().toString());
        assertTrue(release8.generatedInjector().contains("import javax.annotation.Generated;"),
                release8.generatedInjector());
    }

    /** Compiles a bean with every kind of injection point, and the injector generated for it, at the given release. */
    private Compilation.Result compileBeanAtRelease(String release) throws IOException {
        Path dir = Files.createDirectories(workDir.resolve("release" + release));
        return Compilation.compileWithProcessor(dir, Map.of(
                "app.Dep", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Dep {}
                        """,
                "app.Bean", """
                        package app;
                        @jakarta.ejb.Stateless
                        public class Bean {
                            @jakarta.inject.Inject private Dep dep;
                            @jakarta.inject.Inject jakarta.inject.Provider<Dep> lazy;
                            @jakarta.inject.Inject java.util.logging.Logger logger;
                            @jakarta.inject.Inject public void setDep(Dep dep) {}
                            @jakarta.annotation.PostConstruct void init() {}
                        }
                        """), List.of(), "--release", release, "-Xlint:-options");
    }
}
