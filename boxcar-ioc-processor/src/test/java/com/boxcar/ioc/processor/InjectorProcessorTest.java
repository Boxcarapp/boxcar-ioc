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
        assertTrue(result.hasMessage(Kind.NOTE, "resolved lazily"), result.messages(Kind.NOTE).toString());
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
        assertTrue(injector.contains("required(java.util.logging.Logger.class, \"app.Bean.logger\","
                + " \"no bean of type java.util.logging.Logger is known\")"), injector);
        assertTrue(injector.contains("(jakarta.inject.Provider<java.lang.Runnable>) () -> enter(() -> required("
                + "java.lang.Runnable.class"), injector);
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
        assertTrue(injector.contains("BINDINGS.put(lib.Repo.class, lib.JpaRepo.class);"), injector);
        assertTrue(injector.contains("lib.nested.Deep"), injector);
        assertTrue(injector.contains("lib.nested.deeper.Deepest"), injector);
        assertTrue(injector.contains("lib.Outer.Nested"),
                "nested static bean classes are found through their outer class");
        assertEquals(1, countOccurrences(injector, "private lib.Outer.Nested bean_"), "and only once");

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
        assertTrue(injector.contains("instance.publicField = bean_app_Dep();"), injector);
        assertTrue(injector.contains("set(FIELD_app_Bean_privateField, instance, bean_app_Dep());"), injector);
        assertTrue(injector.contains("instance.publicSetter((app.Dep) bean_app_Dep());"), injector);
        assertTrue(injector.contains("invoke(METHOD_app_Bean_packageSetter, instance, bean_app_Dep());"), injector);
        assertTrue(injector.contains("postConstructQueue.add(() -> instance.init());"), injector);
        assertFalse(injector.contains("import "), "generated code is fully qualified");
    }

    @Test
    void generatedCodeCompilesAtOlderSourceLevels() throws IOException {
        // The generated class is compiled at the user's -source level, not the processor's, so it must not use
        // anything newer than Java 8. Java 11 is the oldest level a Jakarta EE 10 project can be compiled at.
        Compilation.Result release11 = compileBeanAtRelease("11");
        assertTrue(release11.success(), release11.diagnostics().toString());
        assertTrue(release11.generatedInjector().contains("@javax.annotation.processing.Generated("),
                release11.generatedInjector());

        // Java 8 has no javax.annotation.processing.Generated yet, so the pre-9 annotation is used instead.
        assumeTrue(ToolProvider.getSystemJavaCompiler().getSourceVersions().contains(SourceVersion.RELEASE_8),
                "this javac no longer compiles for Java 8");
        Compilation.Result release8 = compileBeanAtRelease("8");
        assertTrue(release8.success(), release8.diagnostics().toString());
        assertTrue(release8.generatedInjector().contains("@javax.annotation.Generated("), release8.generatedInjector());
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
