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


import com.boxcar.ioc.processor.BeanModel.Callback;
import com.boxcar.ioc.processor.BeanModel.Constructor;
import com.boxcar.ioc.processor.BeanModel.Dependency;
import com.boxcar.ioc.processor.BeanModel.FieldInjection;
import com.boxcar.ioc.processor.BeanModel.MemberInjection;
import com.boxcar.ioc.processor.BeanModel.MethodInjection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Emits the source of {@code com.boxcar.Injector}.
 *
 * <p>Everything the processor knows at compile time is written down as flat code, the way a person
 * would wire the beans by hand. Every type the injector can hand out has a public getter: a bean's
 * getter constructs it with its dependencies as constructor arguments (each a call to another
 * getter), stores the instance in a {@code Map<Class<?>, Object>}, injects its fields and methods and
 * runs its {@code @PostConstruct} callbacks, all in one method body; the getter of a type that is not
 * a bean class states what the type resolves to. Beans that depend on each other are created by one
 * method for the whole group, all constructed before any is injected. {@code getInstance(Class)} is
 * a chain of {@code if (type == X.class) return getX()} over the types known at compile time.
 *
 * <p>The only runtime state is what {@code bind} needs: the instances and bean classes a test
 * registered, consulted through {@code existing(Class)} at the top of every getter. A getter that can
 * fail half-way rolls back what it added, so that a test can bind what was missing and call again.
 *
 * <p>The class depends on the JDK only and uses no syntax newer than Java 7 (in particular no lambdas
 * or method references), because it is compiled at the {@code -source} level of the user's
 * compilation, not at the processor's own. Public members of public classes are accessed directly so
 * that javac type-checks the wiring; everything else goes through reflection with {@code setAccessible}.
 */
final class InjectorGenerator {

    static final String PACKAGE = "com.boxcar";
    static final String CLASS_NAME = "Injector";
    static final String QUALIFIED_NAME = PACKAGE + "." + CLASS_NAME;

    /** Classes the generated file declares; imports must not shadow them. */
    private static final Set<String> DECLARED = Set.of(CLASS_NAME, "InjectionException");
    /** Fields of the generated class, which no local variable may shadow. */
    private static final Set<String> FIELDS = Set.of("BEANS", "instances", "boundInstances", "implementations");
    /** Methods of the generated class, of Object and of Provider, which no getter may be named after. */
    private static final Set<String> METHODS = Set.of("getInstance", "bind", "existing", "loadClass", "field",
            "method", "constructor", "set", "invoke", "newInstance", "unwrap", "get", "toString", "hashCode", "equals",
            "getClass", "clone", "finalize", "wait", "notify", "notifyAll");
    /**
     * The most members a group of mutually dependent beans may have for a member's getter to name the
     * others in its Javadoc; the getters of a larger group only count them, as the group's method lists
     * them all.
     */
    private static final int NAMED_GROUP_MEMBERS = 3;

    private final Types types;
    private final Elements elements;
    private final SourceVersion sourceVersion;
    private final TypeNames names;
    private final List<BeanModel> beans;
    /** Every supertype of a bean, mapped to the bean it resolves to. */
    private final Map<TypeElement, BeanModel> bindings;
    /** Supertypes shared by several beans without an exact match, with the candidates. */
    private final Map<TypeElement, List<BeanModel>> ambiguous;

    private final Set<String> usedNames = new HashSet<>(METHODS);
    private final Map<BeanModel, String> getterNames = new LinkedHashMap<>();
    /** The group of mutually dependent beans each bean on a cycle belongs to. */
    private final Map<BeanModel, CycleGroup> groups = new LinkedHashMap<>();
    /** Getters for the dependency types that are not bean classes, in order of first use. */
    private final List<Resolver> resolvers = new ArrayList<>();
    /** Reflection helpers the wiring turned out to need. */
    private final Set<String> helpers = new HashSet<>();

    /**
     * Creates a generator for the given beans and their resolved bindings.
     *
     * @param sourceVersion the source level of the compilation the generated class will be compiled at
     */
    InjectorGenerator(Types types, Elements elements, SourceVersion sourceVersion, TypeNames names,
            Collection<BeanModel> beans, Map<TypeElement, BeanModel> bindings,
            Map<TypeElement, List<BeanModel>> ambiguous) {
        this.types = types;
        this.elements = elements;
        this.sourceVersion = sourceVersion;
        this.names = names;
        this.beans = List.copyOf(beans);
        this.bindings = bindings;
        this.ambiguous = ambiguous;
        nameGetters();
        Map<BeanModel, List<BeanModel>> edges = CycleDetector.edges(this.beans, false);
        Map<BeanModel, List<BeanModel>> constructorEdges = CycleDetector.edges(this.beans, true);
        List<List<BeanModel>> components = CycleDetector.components(edges);
        for (int i = 0; i < components.size(); i++) {
            String name = components.size() == 1 ? "createCycle" : "createCycle" + (i + 1);
            CycleGroup group = new CycleGroup(uniqueName(name), components.get(i), edges, constructorEdges);
            for (BeanModel member : components.get(i)) {
                groups.put(member, group);
            }
        }
        for (BeanModel bean : this.beans) {
            for (Dependency dependency : bean.dependencies()) {
                if (!resolvesToBeanClass(dependency)) {
                    resolver(dependency);
                }
            }
        }
    }

    /**
     * Names each bean's getter {@code getX} after its simple name. Beans sharing a simple name are told
     * apart by their package's last segment ({@code getBillingConfig}, {@code getShippingConfig}), or
     * failing that by their qualified name.
     */
    private void nameGetters() {
        Map<String, Integer> bySimpleName = new HashMap<>();
        Map<String, Integer> byPackageAndSimpleName = new HashMap<>();
        for (BeanModel bean : beans) {
            count(bySimpleName, simpleGetterName(bean));
            count(byPackageAndSimpleName, packageGetterName(bean));
        }
        for (BeanModel bean : beans) {
            String name;
            if (bySimpleName.get(simpleGetterName(bean)) == 1) {
                name = simpleGetterName(bean);
            } else if (byPackageAndSimpleName.get(packageGetterName(bean)) == 1) {
                name = packageGetterName(bean);
            } else {
                name = "get" + elements.getBinaryName(bean.type).toString().replace('.', '_').replace('$', '_');
            }
            getterNames.put(bean, uniqueName(name));
        }
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static String simpleGetterName(BeanModel bean) {
        return "get" + bean.type.getSimpleName();
    }

    private String packageGetterName(BeanModel bean) {
        String packageName = elements.getPackageOf(bean.type).getQualifiedName().toString();
        String segment = packageName.substring(packageName.lastIndexOf('.') + 1);
        return "get" + capitalize(segment) + bean.type.getSimpleName();
    }

    /** Whether the dependency's requested type is exactly the class of the bean it resolves to. */
    private boolean resolvesToBeanClass(Dependency dependency) {
        return dependency.resolved != null && types.isSameType(types.erasure(dependency.requestedType),
                types.erasure(dependency.resolved.declaredType));
    }

    /** The resolver for the dependency's requested type, created on first use. */
    private Resolver resolver(Dependency dependency) {
        TypeMirror requested = boxed(dependency.requestedType);
        for (Resolver resolver : resolvers) {
            if (types.isSameType(resolver.type, requested)) {
                resolver.injectionPoints.add(shortDescription(dependency));
                return resolver;
            }
        }
        Resolver resolver = new Resolver(requested, dependency);
        resolvers.add(resolver);
        return resolver;
    }

    /** The injection point's description with the owning class by simple name, e.g. {@code OrderService.gateway}. */
    private static String shortDescription(Dependency dependency) {
        String description = dependency.description;
        int member = description.indexOf('(');
        if (member < 0) {
            member = description.lastIndexOf('.');
        }
        int owner = description.lastIndexOf('.', member - 1);
        return owner < 0 ? description : description.substring(owner + 1);
    }

    private TypeMirror boxed(TypeMirror type) {
        return type.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) type).asType() : type;
    }

    String generate() {
        // The wiring first: writing it collects the reflection helpers it needs.
        CodeWriter wiring = new CodeWriter().indent();
        Set<CycleGroup> written = new HashSet<>();
        for (BeanModel bean : beans) {
            CycleGroup group = groups.get(bean);
            if (group != null && written.add(group)) {
                wiring.blank();
                writeGroup(wiring, group);
            }
            wiring.blank();
            writeGetter(wiring, bean);
        }
        for (Resolver resolver : resolvers) {
            wiring.blank();
            writeResolver(wiring, resolver);
        }

        CodeWriter out = new CodeWriter();
        out.line("package " + PACKAGE + ";");
        out.blank();
        writeClassJavadoc(out);
        out.line("@" + generatedAnnotation() + "(" + CodeWriter.literal(InjectorProcessor.class.getName()) + ")");
        out.line("@SuppressWarnings({\"unchecked\", \"rawtypes\"})");
        out.open("public class " + CLASS_NAME + " {");
        out.blank();
        writeState(out);
        writePublicApi(out);
        writeExisting(out);
        out.raw(wiring.toString());
        out.blank();
        writeReflectionHelpers(out);
        writeExceptionClass(out);
        out.close();
        return Imports.apply(out.toString(), DECLARED, elements);
    }

    /**
     * The {@code @Generated} annotation that exists at the source level being compiled:
     * {@code javax.annotation.processing.Generated} from Java 9 on, {@code javax.annotation.Generated} before.
     */
    private String generatedAnnotation() {
        String qualifiedName = sourceVersion.compareTo(SourceVersion.RELEASE_8) > 0
                ? "javax.annotation.processing.Generated"
                : "javax.annotation.Generated";
        TypeElement annotation = elements.getTypeElement(qualifiedName);
        return annotation != null ? Imports.reference(annotation) : qualifiedName;
    }

    private void writeClassJavadoc(CodeWriter out) {
        out.line("/**");
        out.line(" * Container-free dependency injector generated by Boxcar IoC for unit tests.");
        out.line(" *");
        out.line(" * <p>Each {@code Injector} holds one instance per bean class, created lazily on first request"
                + " together");
        out.line(" * with its transitive dependencies. Create a new {@code Injector} per test for isolation, and use");
        out.line(" * {@link #bind(Class, Object)} to substitute mocks or to supply dependencies that no bean provides,"
                + " or");
        out.line(" * {@link #bind(Class, Class)} to choose which bean class implements a type. Binding a type again");
        out.line(" * replaces its earlier binding until the type is first resolved, so a test base class can extend");
        out.line(" * {@code Injector}, bind defaults in its constructor and leave individual tests to override them.");
        out.line(" *");
        out.line(" * <p>Managed beans:");
        out.line(" * <ul>");
        for (BeanModel bean : beans) {
            out.line(" *   <li>{@code " + bean.qualifiedName + "} (@" + simpleName(bean.beanAnnotation) + ")</li>");
        }
        out.line(" * </ul>");
        out.line(" */");
    }

    private void writeState(CodeWriter out) {
        out.line("/** The bean classes, which {@link #bind(Class, Class)} accepts as implementations. */");
        String declaration = "private static final " + jdk("java.util.List") + "<Class<?>> BEANS = "
                + jdk("java.util.Arrays") + ".asList(";
        if (beans.isEmpty()) {
            out.line(declaration + ");");
        } else {
            out.line(declaration);
            out.indent().indent();
            for (int i = 0; i < beans.size(); i++) {
                out.line(classLiteral(beans.get(i).type) + (i < beans.size() - 1 ? "," : ");"));
            }
            out.outdent().outdent();
        }
        out.blank();
        out.line("/**");
        out.line(" * Every type resolved so far, mapped to its instance: the beans created, and the bound instances"
                + " and");
        out.line(" * implementations once they have been used. A type in this map can no longer be bound.");
        out.line(" */");
        out.line("private final " + jdk("java.util.Map") + "<Class<?>, Object> instances = new "
                + jdk("java.util.HashMap") + "<>();");
        out.line("/** Instances registered through {@link #bind(Class, Object)}, by type. */");
        out.line("private final " + jdk("java.util.Map") + "<Class<?>, Object> boundInstances = new "
                + jdk("java.util.HashMap") + "<>();");
        out.line("/** Bean classes registered through {@link #bind(Class, Class)}, by the type they implement. */");
        out.line("private final " + jdk("java.util.Map") + "<Class<?>, Class<?>> implementations = new "
                + jdk("java.util.HashMap") + "<>();");
        out.blank();
    }

    private void writePublicApi(CodeWriter out) {
        out.line("/**");
        out.line(" * Returns the bean of the given type, creating it and everything it depends on if necessary.");
        out.line(" *");
        out.line(" * <p>This is the entry point shared with the reflection-based {@code Injector}. Every type known at"
                + " compile");
        out.line(" * time also has a getter of its own ({@link #" + firstGetterName() + "()} and so on), which does the"
                + " same");
        out.line(" * without the dispatch and with the type checked by the compiler; use it when the project does not"
                + " need to");
        out.line(" * stay source-compatible with the reflection-based injector.");
        out.line(" *");
        out.line(" * @param type a bean class, a supertype implemented by exactly one bean, or a type passed to {@link"
                + " #bind}");
        out.line(" * @throws InjectionException if the type is unknown or a dependency cannot be satisfied");
        out.line(" */");
        out.open("public <T> T getInstance(Class<T> type) {");
        for (Map.Entry<TypeElement, BeanModel> binding : bindings.entrySet()) {
            out.open("if (type == " + classLiteral(binding.getKey()) + ") {");
            out.line("return type.cast(" + supertypeExpression(binding.getKey(), binding.getValue()) + ");");
            out.close();
        }
        out.line("// Not a type known at compile time: only a binding can provide it.");
        out.line("T bound = existing(" + jdk("java.util.Objects") + ".requireNonNull(type, \"type\"));");
        out.open("if (bound != null) {");
        out.line("return bound;");
        out.close();
        for (Map.Entry<TypeElement, List<BeanModel>> entry : ambiguous.entrySet()) {
            out.open("if (type == " + classLiteral(entry.getKey()) + ") {");
            out.statement("throw new InjectionException(", "Type " + entry.getKey().getSimpleName()
                    + " is implemented by several beans ("
                    + DependencyResolver.simpleNames(DependencyResolver.describe(entry.getValue()))
                    + "); request one of them, or bind an implementation or instance for the type", ");");
            out.close();
        }
        out.line("throw new InjectionException(\"No bean of type \" + type.getName() + \" is known to this Injector"
                + " and none was bound;\"");
        out.line("        + \" use bind(\" + type.getSimpleName() + \".class, ...) to supply an implementation class"
                + " or an instance\");");
        out.close();
        out.blank();
        out.line("/**");
        out.line(" * Registers an existing object, typically a mock, to be returned and injected wherever {@code type}"
                + " is");
        out.line(" * requested. Bindings take precedence over bean classes. Binding a type again replaces its earlier"
                + " binding");
        out.line(" * until the type is first resolved: once an instance has been created, injected or returned, the"
                + " binding is fixed.");
        out.line(" *");
        out.line(" * @return this injector, for chaining");
        out.line(" * @throws IllegalStateException if {@code type} has already been resolved");
        out.line(" */");
        out.open("public <T> " + CLASS_NAME + " bind(Class<T> type, T instance) {");
        out.line(jdk("java.util.Objects") + ".requireNonNull(type, \"type\");");
        out.line(jdk("java.util.Objects") + ".requireNonNull(instance, \"instance\");");
        writeBindableCheck(out);
        out.line("implementations.remove(type);");
        out.line("boundInstances.put(type, instance);");
        out.line("return this;");
        out.close();
        out.blank();
        out.line("/**");
        out.line(" * Registers the bean class to instantiate wherever {@code type} is requested, for example to pick"
                + " one of");
        out.line(" * several implementations of an interface. Replaces an earlier binding of {@code type} until the"
                + " type is");
        out.line(" * first resolved.");
        out.line(" *");
        out.line(" * @return this injector, for chaining");
        out.line(" * @throws IllegalArgumentException if {@code implementation} is not a bean managed by this"
                + " injector");
        out.line(" * @throws IllegalStateException if {@code type} has already been resolved");
        out.line(" */");
        out.open("public <T> " + CLASS_NAME + " bind(Class<T> type, Class<? extends T> implementation) {");
        out.line(jdk("java.util.Objects") + ".requireNonNull(type, \"type\");");
        out.line(jdk("java.util.Objects") + ".requireNonNull(implementation, \"implementation\");");
        out.open("if (!BEANS.contains(implementation)) {");
        out.line("throw new IllegalArgumentException(implementation.getName() + \" is not a bean managed by this"
                + " Injector; bind an instance instead\");");
        out.close();
        out.open("if (type == implementation || !type.isAssignableFrom(implementation)) {");
        out.line("throw new IllegalArgumentException(\"Cannot bind \" + type.getName() + \" to \" +"
                + " implementation.getName());");
        out.close();
        writeBindableCheck(out);
        out.open("for (Class<?> next = implementations.get(implementation); next != null; next ="
                + " implementations.get(next)) {");
        out.open("if (next == type) {");
        out.line("throw new IllegalArgumentException(\"Binding \" + type.getName() + \" to \" +"
                + " implementation.getName() + \" would form a cycle\");");
        out.close();
        out.close();
        out.line("boundInstances.remove(type);");
        out.line("implementations.put(type, implementation);");
        out.line("return this;");
        out.close();
        out.blank();
    }

    /** The getter named in the {@code getInstance} javadoc as an example; the first bean's, or a placeholder. */
    private String firstGetterName() {
        return beans.isEmpty() ? "getInstance" : getterNames.get(beans.get(0));
    }

    private void writeBindableCheck(CodeWriter out) {
        out.open("if (instances.containsKey(type)) {");
        out.line("throw new IllegalStateException(type.getName() + \" has already been resolved by this Injector (an"
                + " instance was\"");
        out.line("        + \" created, injected or returned); bind before the type is first used\");");
        out.close();
    }

    /** {@code existing}: the one runtime lookup, consulted at the top of every getter. */
    private void writeExisting(CodeWriter out) {
        out.line("/** The instance created or bound for {@code type} so far; null if there is none yet. */");
        out.open("private <T> T existing(Class<T> type) {");
        out.line("Object instance = instances.get(type);");
        out.open("if (instance == null) {");
        out.line("instance = boundInstances.get(type);");
        out.open("if (instance == null && implementations.containsKey(type)) {");
        out.line("instance = getInstance(implementations.get(type));");
        out.close();
        out.open("if (instance != null) {");
        out.line("// In use: from now on the binding of this type cannot be replaced.");
        out.line("instances.put(type, instance);");
        out.close();
        out.close();
        out.line("return type.cast(instance);");
        out.close();
    }

    /** How {@code getInstance} resolves a supertype: its own getter if it is injected somewhere, else the bean's. */
    private String supertypeExpression(TypeElement supertype, BeanModel bean) {
        for (Resolver resolver : resolvers) {
            if (types.isSameType(resolver.type, types.erasure(supertype.asType()))) {
                return resolver.name + "()";
            }
        }
        return getterNames.get(bean) + "()";
    }

    /**
     * The getter of a bean: the whole construction in one method body. If anything after the instance has
     * been stored can fail (a dependency, an injection, a callback), the getter rolls back what it added, so
     * that the caller can bind what was missing and call again.
     */
    private void writeGetter(CodeWriter out, BeanModel bean) {
        String type = typeName(bean);
        String literal = classLiteral(bean.type);
        CycleGroup group = groups.get(bean);
        Scope scope = new Scope();
        String local = scope.declare(localName(bean.type.getSimpleName().toString()));

        if (group != null) {
            out.javadoc("Created together with " + group.describeOthers(bean) + ", see {@link #" + group.name + "}.");
        }
        out.open("public " + type + " " + getterNames.get(bean) + "() {");
        out.line(type + " " + local + " = existing(" + literal + ");");
        if (group != null) {
            out.open("if (" + local + " == null) {");
            out.line(group.name + "();");
            out.line(local + " = (" + type + ") instances.get(" + literal + ");");
            out.close();
            out.line("return " + local + ";");
            out.close();
            return;
        }
        scope.locals.put(bean, local);
        out.open("if (" + local + " != null) {");
        out.line("return " + local + ";");
        out.close();
        boolean rollback = !bean.dependencies().isEmpty() || !bean.postConstructs.isEmpty();
        if (rollback) {
            writeSnapshot(out);
            out.open("try {");
        }
        writeConstruction(out, bean, local, scope);
        out.line("instances.put(" + literal + ", " + local + ");");
        writeInjection(out, bean, local, scope);
        writeCallbacks(out, bean, local);
        out.line("return " + local + ";");
        if (rollback) {
            writeRollback(out);
        }
        out.close();
    }

    private void writeSnapshot(CodeWriter out) {
        out.line(jdk("java.util.Set") + "<Class<?>> before = new " + jdk("java.util.HashSet")
                + "<>(instances.keySet());");
    }

    /** Closes the {@code try} opened after {@link #writeSnapshot}: discards everything added since. */
    private void writeRollback(CodeWriter out) {
        out.close("} catch (RuntimeException | Error e) {");
        out.indent();
        out.line("instances.keySet().retainAll(before);");
        out.line("throw e;");
        out.close();
    }

    /**
     * The method creating a group of mutually dependent beans: every instance is constructed (in an order
     * that satisfies the constructor dependencies among them) before any is injected, so the cycle is closed
     * once all of them exist, and the callbacks run after all injection. A member whose class is bound is
     * used as bound and left alone. The group is one unit of work and rolls back as one.
     */
    private void writeGroup(CodeWriter out, CycleGroup group) {
        Scope scope = new Scope();
        Map<BeanModel, String> created = new HashMap<>();
        for (Map.Entry<BeanModel, String> local : localNames(group.members).entrySet()) {
            scope.locals.put(local.getKey(), scope.declare(local.getValue()));
        }
        out.javadoc("Creates " + group.describe() + ", which depend on each other: all of them are constructed"
                + " before any is injected, and all are injected before their @PostConstruct callbacks run. A member"
                + " whose class is bound is used as bound.");
        out.open("private void " + group.name + "() {");
        writeSnapshot(out);
        out.open("try {");
        for (BeanModel member : group.members) {
            String type = typeName(member);
            String local = scope.locals.get(member);
            out.line(type + " " + local + " = existing(" + classLiteral(member.type) + ");");
            if (member.members.isEmpty() && member.postConstructs.isEmpty()) {
                out.open("if (" + local + " == null) {");
            } else {
                String flag = scope.declare("new" + capitalize(local));
                created.put(member, flag);
                out.line("boolean " + flag + " = " + local + " == null;");
                out.open("if (" + flag + ") {");
            }
            writeConstruction(out, member, local, scope);
            out.line("instances.put(" + classLiteral(member.type) + ", " + local + ");");
            out.close();
        }
        for (BeanModel member : group.members) {
            if (!member.members.isEmpty()) {
                out.open("if (" + created.get(member) + ") {");
                writeInjection(out, member, scope.locals.get(member), scope);
                out.close();
            }
        }
        for (BeanModel member : group.callbackOrder) {
            if (!member.postConstructs.isEmpty()) {
                out.open("if (" + created.get(member) + ") {");
                writeCallbacks(out, member, scope.locals.get(member));
                out.close();
            }
        }
        writeRollback(out);
        out.close();
    }

    /** Assigns a new instance of the bean to {@code target}, with its dependencies as constructor arguments. */
    private void writeConstruction(CodeWriter out, BeanModel bean, String target, Scope scope) {
        Constructor constructor = bean.constructor;
        List<String> arguments = new ArrayList<>();
        for (Dependency parameter : constructor.parameters()) {
            arguments.add(argument(parameter, scope));
        }
        boolean direct = constructor.element().getModifiers().contains(Modifier.PUBLIC)
                && allAccessible(constructor.parameters());
        if (direct) {
            out.lines(target + " = new " + typeName(bean) + (bean.type.getTypeParameters().isEmpty() ? "" : "<>")
                    + "(" + String.join(", ", arguments) + ");");
            return;
        }
        helpers.add("constructor");
        StringBuilder call = new StringBuilder(target).append(" = newInstance(constructor(")
                .append(classLiteral(bean.type)).append(parameterLiterals(constructor.element())).append(")");
        for (String argument : arguments) {
            call.append(", ").append(argument);
        }
        out.lines(call.append(");").toString());
    }

    /** Whether every dependency's declared type can be named from the generated class. */
    private boolean allAccessible(List<Dependency> dependencies) {
        for (Dependency dependency : dependencies) {
            if (!names.isAccessible(dependency.declaredType)) {
                return false;
            }
        }
        return true;
    }

    private void writeInjection(CodeWriter out, BeanModel bean, String target, Scope scope) {
        for (MemberInjection member : bean.members) {
            if (member instanceof FieldInjection field) {
                writeFieldInjection(out, field, target, scope);
            } else if (member instanceof MethodInjection method) {
                writeMethodInjection(out, method, target, scope);
            } else {
                throw new IllegalStateException("Unknown member type: " + member);
            }
        }
    }

    private void writeFieldInjection(CodeWriter out, FieldInjection injection, String target, Scope scope) {
        VariableElement field = injection.field();
        String value = argument(injection.dependency(), scope);
        if (field.getModifiers().contains(Modifier.PUBLIC) && names.isAccessible(injection.dependency().declaredType)) {
            out.lines(target + "." + field.getSimpleName() + " = " + value + ";");
            return;
        }
        helpers.add("field");
        out.lines("set(field(" + classLiteral(injection.owner()) + ", "
                + CodeWriter.literal(field.getSimpleName().toString()) + "), " + target + ", " + value + ");");
    }

    private void writeMethodInjection(CodeWriter out, MethodInjection injection, String target, Scope scope) {
        ExecutableElement method = injection.method();
        List<String> arguments = new ArrayList<>();
        for (Dependency parameter : injection.parameters()) {
            arguments.add(argument(parameter, scope));
        }
        if (method.getModifiers().contains(Modifier.PUBLIC) && allAccessible(injection.parameters())) {
            out.lines(target + "." + method.getSimpleName() + "(" + String.join(", ", arguments) + ");");
            return;
        }
        helpers.add("method");
        StringBuilder call = new StringBuilder("invoke(method(").append(classLiteral(injection.owner()))
                .append(", ").append(CodeWriter.literal(method.getSimpleName().toString()))
                .append(parameterLiterals(method)).append("), ").append(target);
        for (String argument : arguments) {
            call.append(", ").append(argument);
        }
        out.lines(call.append(");").toString());
    }

    private void writeCallbacks(CodeWriter out, BeanModel bean, String target) {
        for (Callback callback : bean.postConstructs) {
            ExecutableElement method = callback.method();
            if (method.getModifiers().contains(Modifier.PUBLIC)) {
                out.line(target + "." + method.getSimpleName() + "();");
                continue;
            }
            helpers.add("method");
            out.line("invoke(method(" + classLiteral(callback.owner()) + ", "
                    + CodeWriter.literal(method.getSimpleName().toString()) + "), " + target + ");");
        }
    }

    /** Class literals of the erased declared parameter types, as needed for reflective lookup. */
    private String parameterLiterals(ExecutableElement executable) {
        StringBuilder literals = new StringBuilder();
        for (VariableElement parameter : executable.getParameters()) {
            literals.append(", ").append(classLiteral(parameter.asType()));
        }
        return literals.toString();
    }

    /**
     * The expression supplying the value for an injection point. A {@code Provider} is an anonymous class
     * whose {@code get()} calls the getter, spread over several lines; {@link CodeWriter#lines} lays it out.
     */
    private String argument(Dependency dependency, Scope scope) {
        if (!dependency.provider) {
            return resolutionExpression(dependency, scope);
        }
        String provided = names.isAccessible(dependency.requestedType)
                ? names.render(boxed(dependency.requestedType))
                : "Object";
        // Resolved through the injector on each call, never from a local: a binding made later must be seen.
        return "new " + names.renderErasure(dependency.declaredType) + "<" + provided + ">() {\n"
                + "    @Override\n"
                + "    public " + provided + " get() {\n"
                + "        return " + resolutionExpression(dependency, Scope.NONE) + ";\n"
                + "    }\n"
                + "}";
    }

    /**
     * The expression resolving a dependency right now: the bean held in a local variable of the current
     * method, or a call to the getter of the bean or of the type that is not a bean class.
     */
    private String resolutionExpression(Dependency dependency, Scope scope) {
        if (!resolvesToBeanClass(dependency)) {
            return resolver(dependency).name + "()";
        }
        String local = scope.locals.get(dependency.resolved);
        return local != null ? local : getterNames.get(dependency.resolved) + "()";
    }

    /**
     * The getter of a type that is not a bean class states what the type resolves to: the bean implementing
     * it, or nothing, in which case a test has to bind the type.
     */
    private void writeResolver(CodeWriter out, Resolver resolver) {
        boolean accessible = names.isAccessible(resolver.type);
        String type = accessible ? names.render(resolver.type) : "Object";
        String literal = classLiteral(resolver.type);
        String local = new Scope().declare(localName(types.asElement(types.erasure(resolver.type)) != null
                ? types.asElement(types.erasure(resolver.type)).getSimpleName().toString()
                : "value"));
        String displayName = DependencyResolver.simpleNames(names.describe(resolver.type));
        if (resolver.bean != null) {
            out.javadoc("{@code " + displayName + "} is implemented by {@link " + typeName(resolver.bean) + "}"
                    + (accessible ? "" : "; the type itself is not visible from here") + ".");
            out.open("public " + type + " " + resolver.name + "() {");
            out.line(type + " " + local + " = existing(" + literal + ");");
            out.line("return " + local + " != null ? " + local + " : " + getterNames.get(resolver.bean) + "();");
            out.close();
            return;
        }
        out.javadoc("{@code " + displayName + "} has to be bound by the test: " + resolver.reason + ".");
        out.open("public " + type + " " + resolver.name + "() {");
        out.line(type + " " + local + " = existing(" + literal + ");");
        out.open("if (" + local + " == null) {");
        out.statement("throw new InjectionException(", "No " + displayName + " was bound, but "
                + String.join(", ", resolver.injectionPoints) + " needs one: " + resolver.reason
                + ". Register an instance with Injector.bind("
                + simpleName(names.describe(types.erasure(resolver.type))) + ".class, ...) first", ");");
        out.close();
        out.line("return " + local + ";");
        out.close();
    }

    private void writeReflectionHelpers(CodeWriter out) {
        if (helpers.contains("loadClass")) {
            out.open("private static Class<?> loadClass(String binaryName) {");
            out.open("try {");
            out.line("return Class.forName(binaryName, false, " + CLASS_NAME + ".class.getClassLoader());");
            out.close("} catch (ClassNotFoundException e) {");
            out.indent();
            out.line("throw new InjectionException(\"Class \" + binaryName + \" is not on the class path\", e);");
            out.close();
            out.close();
            out.blank();
        }
        if (helpers.contains("field")) {
            String fieldType = jdk("java.lang.reflect.Field");
            out.open("private static " + fieldType + " field(Class<?> owner, String name) {");
            out.open("try {");
            out.line(fieldType + " field = owner.getDeclaredField(name);");
            out.line("field.setAccessible(true);");
            out.line("return field;");
            out.close("} catch (NoSuchFieldException | RuntimeException e) {");
            out.indent();
            out.line("throw new InjectionException(\"Cannot access field \" + owner.getName() + \".\" + name, e);");
            out.close();
            out.close();
            out.blank();
            out.open("private static void set(" + fieldType + " field, Object target, Object value) {");
            out.open("try {");
            out.line("field.set(target, value);");
            out.close("} catch (IllegalAccessException | IllegalArgumentException e) {");
            out.indent();
            out.line("throw new InjectionException(\"Cannot inject \" + field.getDeclaringClass().getName() + \".\" +"
                    + " field.getName(), e);");
            out.close();
            out.close();
            out.blank();
        }
        if (helpers.contains("method")) {
            String methodType = jdk("java.lang.reflect.Method");
            out.open("private static " + methodType + " method(Class<?> owner, String name, Class<?>..."
                    + " parameterTypes) {");
            out.open("try {");
            out.line(methodType + " method = owner.getDeclaredMethod(name, parameterTypes);");
            out.line("method.setAccessible(true);");
            out.line("return method;");
            out.close("} catch (NoSuchMethodException | RuntimeException e) {");
            out.indent();
            out.line("throw new InjectionException(\"Cannot access method \" + owner.getName() + \".\" + name, e);");
            out.close();
            out.close();
            out.blank();
            out.open("private static Object invoke(" + methodType + " method, Object target, Object... arguments) {");
            out.open("try {");
            out.line("return method.invoke(target, arguments);");
            out.close("} catch (IllegalAccessException | IllegalArgumentException e) {");
            out.indent();
            out.line("throw new InjectionException(\"Cannot invoke \" + method.getDeclaringClass().getName() + \".\" +"
                    + " method.getName(), e);");
            out.close("} catch (" + jdk("java.lang.reflect.InvocationTargetException") + " e) {");
            out.indent();
            out.line("throw unwrap(e);");
            out.close();
            out.close();
            out.blank();
        }
        if (helpers.contains("constructor")) {
            String constructorType = jdk("java.lang.reflect.Constructor");
            out.open("private static <T> " + constructorType + "<T> constructor(Class<T> owner, Class<?>..."
                    + " parameterTypes) {");
            out.open("try {");
            out.line(constructorType + "<T> constructor = owner.getDeclaredConstructor(parameterTypes);");
            out.line("constructor.setAccessible(true);");
            out.line("return constructor;");
            out.close("} catch (NoSuchMethodException | RuntimeException e) {");
            out.indent();
            out.line("throw new InjectionException(\"Cannot access constructor of \" + owner.getName(), e);");
            out.close();
            out.close();
            out.blank();
            out.open("private static <T> T newInstance(" + constructorType + "<T> constructor, Object... arguments) {");
            out.open("try {");
            out.line("return constructor.newInstance(arguments);");
            out.close("} catch (InstantiationException | IllegalAccessException | IllegalArgumentException e) {");
            out.indent();
            out.line("throw new InjectionException(\"Cannot instantiate \" + constructor.getDeclaringClass().getName(),"
                    + " e);");
            out.close("} catch (" + jdk("java.lang.reflect.InvocationTargetException") + " e) {");
            out.indent();
            out.line("throw unwrap(e);");
            out.close();
            out.close();
            out.blank();
        }
        if (helpers.contains("method") || helpers.contains("constructor")) {
            out.line("/** Rethrows what user code threw, so that test assertions see the original exception. */");
            out.open("private static RuntimeException unwrap(" + jdk("java.lang.reflect.InvocationTargetException")
                    + " e) {");
            out.line("Throwable cause = e.getCause();");
            out.open("if (cause instanceof RuntimeException) {");
            out.line("return (RuntimeException) cause;");
            out.close();
            out.open("if (cause instanceof Error) {");
            out.line("throw (Error) cause;");
            out.close();
            out.line("return new InjectionException(cause.getMessage(), cause);");
            out.close();
            out.blank();
        }
    }

    /** The erasure of the bean's class as source, e.g. {@code OrderService}. */
    private String typeName(BeanModel bean) {
        return names.render(types.erasure(bean.declaredType));
    }

    /** A class literal, or a {@code loadClass} call for a class the generated code cannot name. */
    private String classLiteral(TypeMirror type) {
        String literal = names.classLiteral(type);
        if (literal.startsWith("loadClass(")) {
            helpers.add("loadClass");
        }
        return literal;
    }

    private String classLiteral(TypeElement type) {
        return classLiteral(type.asType());
    }

    /** An import marker for a JDK class the infrastructure uses. */
    private String jdk(String qualifiedName) {
        return Imports.reference(elements.getTypeElement(qualifiedName));
    }

    private String uniqueName(String base) {
        String name = base;
        for (int i = 2; !usedNames.add(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * The local variable name for an instance of the class: its last camel-case word ({@code service} for
     * {@code OrderService}), unless that is a keyword or the name of a method of the generated class.
     */
    private static String localName(String simpleName) {
        int start = simpleName.length() - 1;
        while (start > 0 && !(Character.isUpperCase(simpleName.charAt(start))
                && !Character.isUpperCase(simpleName.charAt(start - 1)))) {
            start--;
        }
        String word = decapitalize(simpleName.substring(start));
        return SourceVersion.isName(word) && !METHODS.contains(word) ? word : decapitalize(simpleName);
    }

    /** Local names for the members of a group; members whose last word would clash use their full names. */
    private static Map<BeanModel, String> localNames(List<BeanModel> members) {
        Map<String, Integer> counts = new HashMap<>();
        for (BeanModel member : members) {
            String name = localName(member.type.getSimpleName().toString());
            counts.put(name, counts.getOrDefault(name, 0) + 1);
        }
        Map<BeanModel, String> locals = new LinkedHashMap<>();
        for (BeanModel member : members) {
            String simple = member.type.getSimpleName().toString();
            String name = localName(simple);
            locals.put(member, counts.get(name) == 1 ? name : decapitalize(simple));
        }
        return locals;
    }

    /** The local variables of one generated method. */
    private static final class Scope {

        /** The scope of code that must resolve through the injector, holding no bean in a local. */
        static final Scope NONE = new Scope();

        private final Set<String> used = new HashSet<>(FIELDS);
        /** Beans held in a local variable of the method: the bean being created, or the members of a group. */
        final Map<BeanModel, String> locals = new HashMap<>();

        String declare(String base) {
            String name = SourceVersion.isName(base) ? base : base + "_";
            for (int i = 2; !used.add(name); i++) {
                name = base + i;
            }
            return name;
        }
    }

    /** A dependency type that is not a bean class, and what it resolves to. */
    private final class Resolver {

        final TypeMirror type;
        /** The bean implementing the type, or null if a test has to bind it. */
        final BeanModel bean;
        /** Why nothing was resolved, if {@link #bean} is null. */
        final String reason;
        final Set<String> injectionPoints = new LinkedHashSet<>();
        final String name;

        Resolver(TypeMirror type, Dependency first) {
            this.type = type;
            this.bean = first.resolved;
            this.reason = first.unresolvedReason;
            this.injectionPoints.add(shortDescription(first));
            this.name = uniqueName("get" + capitalize(methodName(type)));
        }

        /** {@code repository} for {@code Repository}, {@code repositoryOfUser} for {@code Repository<User>}. */
        private String methodName(TypeMirror type) {
            String rendered = names.describe(type);
            StringBuilder name = new StringBuilder();
            for (String part : rendered.split("[<>,\\s]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                String simple = part.substring(part.lastIndexOf('.') + 1).replace("?", "Any").replace("[]", "Array");
                if (name.length() == 0) {
                    name.append(decapitalize(simple));
                } else {
                    name.append("Of").append(capitalize(simple));
                }
            }
            String result = name.toString().replaceAll("[^A-Za-z0-9_$]", "");
            return SourceVersion.isName(result) ? result : result + "Type";
        }
    }

    /**
     * Beans that depend on each other and are therefore created together, see {@link #writeGroup}. The
     * method is {@code createCycle}, numbered from 1 in bean order when there are several groups.
     */
    private final class CycleGroup {

        final String name;
        /** The members in creation order: a bean after every bean it takes as a constructor argument. */
        final List<BeanModel> members;
        /** The members in the order their callbacks run: dependencies first, as far as a cycle allows. */
        final List<BeanModel> callbackOrder;

        CycleGroup(String name, List<BeanModel> component, Map<BeanModel, List<BeanModel>> edges,
                Map<BeanModel, List<BeanModel>> constructorEdges) {
            this.name = name;
            this.members = creationOrder(component, constructorEdges);
            this.callbackOrder = new ArrayList<>();
            visitDependenciesFirst(members.get(0), edges, new HashSet<>(), callbackOrder);
        }

        /** The members as {@code {@link A}, {@link B} and {@link C}}. */
        String describe() {
            return describe(members);
        }

        private String describe(List<BeanModel> beans) {
            StringBuilder description = new StringBuilder();
            for (int i = 0; i < beans.size(); i++) {
                if (i > 0) {
                    description.append(i == beans.size() - 1 ? " and " : ", ");
                }
                description.append("{@link ").append(typeName(beans.get(i))).append('}');
            }
            return description.toString();
        }

        /**
         * The members other than {@code bean}, as {@link #describe()}; in a group with more than
         * {@link #NAMED_GROUP_MEMBERS}, just their number, as the group's method lists them all.
         */
        String describeOthers(BeanModel bean) {
            if (members.size() > NAMED_GROUP_MEMBERS) {
                return (members.size() - 1) + " other beans";
            }
            List<BeanModel> others = new ArrayList<>(members);
            others.remove(bean);
            return describe(others);
        }

        /** Topological order by the constructor edges within the component, ties broken by bean order. */
        private List<BeanModel> creationOrder(List<BeanModel> component,
                Map<BeanModel, List<BeanModel>> constructorEdges) {
            List<BeanModel> order = new ArrayList<>();
            Set<BeanModel> remaining = new LinkedHashSet<>(component);
            while (!remaining.isEmpty()) {
                BeanModel next = null;
                for (BeanModel candidate : remaining) {
                    boolean ready = true;
                    for (BeanModel argument : constructorEdges.get(candidate)) {
                        if (argument != candidate && remaining.contains(argument)) {
                            ready = false;
                        }
                    }
                    if (ready) {
                        next = candidate;
                        break;
                    }
                }
                if (next == null) {
                    // A constructor-only cycle; reported as an error elsewhere, so any order will do.
                    next = remaining.iterator().next();
                }
                remaining.remove(next);
                order.add(next);
            }
            return order;
        }

        /** Depth-first post-order over the edges within the component, starting from {@code bean}. */
        private void visitDependenciesFirst(BeanModel bean, Map<BeanModel, List<BeanModel>> edges,
                Set<BeanModel> visited, List<BeanModel> order) {
            if (!visited.add(bean)) {
                return;
            }
            for (BeanModel dependency : edges.get(bean)) {
                if (members.contains(dependency)) {
                    visitDependenciesFirst(dependency, edges, visited, order);
                }
            }
            order.add(bean);
        }
    }

    private void writeExceptionClass(CodeWriter out) {
        out.line("/** Thrown when a bean or one of its dependencies cannot be resolved, created or injected. */");
        out.open("public static final class InjectionException extends RuntimeException {");
        out.blank();
        out.line("private static final long serialVersionUID = 1L;");
        out.blank();
        out.open("InjectionException(String message) {");
        out.line("super(message);");
        out.close();
        out.blank();
        out.open("InjectionException(String message, Throwable cause) {");
        out.line("super(message, cause);");
        out.close();
        out.close();
    }
}
