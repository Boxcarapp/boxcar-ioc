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

import com.boxcar.ioc.processor.BeanModel.Dependency;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.JavaFileObject;

/**
 * Generates {@code com.boxcar.Injector}, a dependency injector for the beans of a compilation.
 *
 * <p>Bean classes are the classes annotated with one of the supported bean-defining annotations
 * (see {@link Annotations#BEAN_DEFINING}). They are discovered among the sources being compiled
 * and, optionally, among already compiled classes below the package prefixes listed in the
 * {@value #PACKAGES_OPTION} option, which is how a test compilation can build an injector for the
 * production beans on its class path:
 *
 * <pre>{@code javac -processorpath boxcar-ioc.jar -Aboxcar.packages=com.acme ...}</pre>
 *
 * <p>Prefixes are recursive: {@code com.acme} covers {@code com.acme.service.impl}. Finding the classes
 * requires listing class files, which the annotation processing API does not offer, so the processor
 * looks in the directories and jars of its own class loader (the whole compile class path when the
 * processor is on it, as it is by default with Maven) and in the entries of the {@value #CLASSPATH_OPTION}
 * option. The latter is needed when the processor runs from a separate processor path:
 *
 * <pre>{@code -Aboxcar.packages=com.acme -Aboxcar.classpath=target/classes:lib/acme-model.jar}</pre>
 *
 * <p>The injector is generated in the first round in which beans are found and all their dependency
 * types resolve. Beans produced by other annotation processors in later rounds cannot be included
 * and are reported with a warning.
 */
@SupportedOptions({InjectorProcessor.PACKAGES_OPTION, InjectorProcessor.CLASSPATH_OPTION})
public final class InjectorProcessor extends AbstractProcessor {

    /** Comma separated package prefixes whose annotated classes on the class path are beans too. */
    public static final String PACKAGES_OPTION = "boxcar.packages";

    /**
     * Directories and jars to search for the classes below {@link #PACKAGES_OPTION}, separated by the
     * platform path separator (or commas). Only needed when the processor is not on the compile class path.
     */
    public static final String CLASSPATH_OPTION = "boxcar.classpath";

    private TypeNames names;
    private Diagnostics diagnostics;
    private BeanScanner scanner;

    /** Discovered bean classes by qualified name; sorted so that generated output is deterministic. */
    private final Map<String, TypeElement> beanClasses = new TreeMap<>();
    private final Map<String, String> beanAnnotations = new LinkedHashMap<>();
    private boolean packagesScanned;
    private boolean generated;

    /** Creates the processor; javac does this through {@code META-INF/services} or {@code -processor}. */
    public InjectorProcessor() {
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        names = new TypeNames(env.getTypeUtils(), env.getElementUtils());
        diagnostics = new Diagnostics(env.getMessager());
        scanner = new BeanScanner(env.getTypeUtils(), env.getElementUtils(), diagnostics);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /** Every round is of interest: with the packages option there may be no annotations in the sources at all. */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (generated) {
            for (TypeElement late : discover(roundEnv.getRootElements())) {
                diagnostics.warning(late, "Bean " + late.getQualifiedName() + " appeared after "
                        + InjectorGenerator.QUALIFIED_NAME + " had been generated (it was produced by another"
                        + " annotation processor) and cannot be managed by it."
                        + " Compile it separately and list its package in -A" + PACKAGES_OPTION + " instead");
            }
            return false;
        }
        if (!packagesScanned) {
            packagesScanned = true;
            scanConfiguredPackages();
        }
        discover(roundEnv.getRootElements());
        if (beanClasses.isEmpty() || roundEnv.processingOver()) {
            // Nothing to do, or too late: a file created in the final round is not fully processed by javac.
            // The latter only happens when dependency types never resolved, which javac reports on its own.
            return false;
        }
        if (beanClasses.values().stream().anyMatch(scanner::hasUnresolvedTypes)) {
            // Some injection point mentions a type that does not exist yet; another processor may generate
            // it, so wait for the next round.
            return false;
        }
        generate();
        generated = true;
        return false;
    }

    private void scanConfiguredPackages() {
        String option = processingEnv.getOptions().get(PACKAGES_OPTION);
        if (option == null || option.isBlank()) {
            return;
        }
        ClassPathScanner scanner = new ClassPathScanner(getClass().getClassLoader(), configuredClassPath(),
                message -> diagnostics.warning(message + " (-A" + CLASSPATH_OPTION + ")"));
        for (String entry : option.split(",")) {
            String prefix = entry.strip();
            if (prefix.isEmpty()) {
                continue;
            }
            if (scanner.hasPackage(prefix)) {
                for (String binaryName : scanner.classesUnder(prefix)) {
                    TypeElement type = processingEnv.getElementUtils().getTypeElement(binaryName.replace('$', '.'));
                    if (type != null && type.getNestingKind() == NestingKind.TOP_LEVEL) {
                        discover(List.of(type));
                    }
                }
                continue;
            }
            // Not on any scannable root: fall back to the compiler's view of the package itself, which
            // covers the package's own classes but not its subpackages.
            PackageElement pkg = processingEnv.getElementUtils().getPackageElement(prefix);
            if (pkg == null) {
                diagnostics.warning("Package " + prefix + " listed in -A" + PACKAGES_OPTION
                        + " was not found on the class path");
                continue;
            }
            if (!pkg.getEnclosedElements().isEmpty()) {
                diagnostics.warning("Package " + prefix + " listed in -A" + PACKAGES_OPTION + " is not on a class path"
                        + " root the processor can scan, so only its own classes were considered, not its subpackages."
                        + " Put the processor on the compile class path or list the root in -A" + CLASSPATH_OPTION);
            }
            discover(pkg.getEnclosedElements());
        }
    }

    /** The entries of the {@value #CLASSPATH_OPTION} option, split on the platform path separator or commas. */
    private List<Path> configuredClassPath() {
        String option = processingEnv.getOptions().get(CLASSPATH_OPTION);
        if (option == null || option.isBlank()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String entry : option.split("[" + Pattern.quote(File.pathSeparator) + ",]")) {
            if (!entry.isBlank()) {
                paths.add(Paths.get(entry.strip()));
            }
        }
        return paths;
    }

    /** Records the bean classes among {@code elements} and their nested classes; returns the newly found ones. */
    private List<TypeElement> discover(Iterable<? extends Element> elements) {
        List<TypeElement> found = new ArrayList<>();
        for (TypeElement type : ElementFilter.typesIn(toList(elements))) {
            String annotation = Annotations.firstAnnotation(type, Annotations.BEAN_DEFINING);
            String qualifiedName = type.getQualifiedName().toString();
            if (annotation != null && beanClasses.putIfAbsent(qualifiedName, type) == null) {
                beanAnnotations.put(qualifiedName, annotation);
                found.add(type);
            }
            found.addAll(discover(type.getEnclosedElements()));
        }
        return found;
    }

    private static List<Element> toList(Iterable<? extends Element> elements) {
        List<Element> list = new ArrayList<>();
        elements.forEach(list::add);
        return list;
    }

    private void generate() {
        List<BeanModel> beans = new ArrayList<>();
        beanClasses.forEach((qualifiedName, type) -> {
            BeanModel bean = scanner.scan(type, beanAnnotations.get(qualifiedName));
            if (bean != null) {
                beans.add(bean);
            }
        });

        DependencyResolver resolver = new DependencyResolver(processingEnv.getTypeUtils(), names, beans);
        resolver.resolveAll();
        reportCycles(beans);
        Map<TypeElement, List<BeanModel>> ambiguous = new LinkedHashMap<>();
        Map<TypeElement, BeanModel> bindings = resolver.bindings(ambiguous);

        InjectorGenerator generator = new InjectorGenerator(processingEnv.getTypeUtils(),
                processingEnv.getElementUtils(), processingEnv.getSourceVersion(), names, beans, bindings, ambiguous);
        String source = generator.generate();

        Filer filer = processingEnv.getFiler();
        try {
            Element[] originating = beans.stream().map(bean -> (Element) bean.type).toArray(Element[]::new);
            JavaFileObject file = filer.createSourceFile(InjectorGenerator.QUALIFIED_NAME, originating);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (FilerException e) {
            diagnostics.error(null, InjectorGenerator.QUALIFIED_NAME + " could not be created (does the compilation"
                    + " already contain a class of that name?): " + e.getMessage());
        } catch (IOException e) {
            diagnostics.error(null, "Failed to write " + InjectorGenerator.QUALIFIED_NAME + ": " + e);
        }
    }

    /**
     * Cycles that pass only through constructor parameters cannot be created at all and are errors;
     * every other cycle is closed after construction through field or method injection.
     */
    private void reportCycles(List<BeanModel> beans) {
        Map<BeanModel, List<BeanModel>> constructorEdges = new LinkedHashMap<>();
        Map<BeanModel, List<BeanModel>> allEdges = new LinkedHashMap<>();
        for (BeanModel bean : beans) {
            constructorEdges.put(bean, targets(bean.constructor.parameters()));
            allEdges.put(bean, targets(bean.dependencies()));
        }
        List<List<BeanModel>> constructorCycles = CycleDetector.findCycles(constructorEdges);
        for (List<BeanModel> cycle : constructorCycles) {
            diagnostics.error(cycle.get(0).type, "Dependency cycle through constructor injection cannot be satisfied: "
                    + CycleDetector.describe(cycle)
                    + ". Inject one of the dependencies into a field or method, or as a Provider");
        }
        for (List<BeanModel> cycle : CycleDetector.findCycles(allEdges)) {
            if (!constructorCycles.contains(cycle)) {
                diagnostics.note(cycle.get(0).type, "Dependency cycle " + CycleDetector.describe(cycle)
                        + " is resolved lazily: the instances are created first and the cycle is closed by field/method"
                        + " injection");
            }
        }
    }

    /** Beans that the given dependencies are resolved to; Provider dependencies are lazy and create no edge. */
    private static List<BeanModel> targets(List<Dependency> dependencies) {
        return dependencies.stream()
                .filter(dependency -> dependency.resolved != null && !dependency.provider)
                .map(dependency -> dependency.resolved)
                .toList();
    }
}
