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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Emits the source of {@code com.boxcar.Injector}.
 *
 * <p>The generated class is self-contained (it depends on the JDK only) and fully qualifies every
 * type it mentions. It uses no language feature newer than Java 8, because it is compiled at the
 * {@code -source} level of the user's compilation, not at the processor's own. Public members of
 * public classes are accessed directly so that javac type-checks the wiring; everything else goes
 * through cached reflective handles with {@code setAccessible}.
 *
 * <p>Runtime strategy: a bean is registered in the instance map right after construction and before
 * its fields and methods are injected, so a dependency cycle simply finds the partially initialised
 * instance. Cycles that run only through constructors are rejected at compile time. Constructor
 * arguments are resolved before the map is re-checked, because resolving them may already have
 * created the bean through such a mixed cycle. {@code @PostConstruct} callbacks are queued and run
 * once the outermost {@code getInstance} call has wired everything, dependencies first.
 */
final class InjectorGenerator {

    static final String PACKAGE = "com.boxcar";
    static final String CLASS_NAME = "Injector";
    static final String QUALIFIED_NAME = PACKAGE + "." + CLASS_NAME;

    private final Types types;
    private final Elements elements;
    private final SourceVersion sourceVersion;
    private final TypeNames names;
    private final List<BeanModel> beans;
    private final Map<TypeElement, BeanModel> bindings;
    private final Map<TypeElement, List<BeanModel>> ambiguous;

    private final Map<BeanModel, String> factoryNames = new LinkedHashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    /** Reflective handle names by initializer, so that a member inherited by several beans gets one handle. */
    private final Map<String, String> handleNames = new LinkedHashMap<>();
    /** Declarations of the static reflective handles, emitted after the bindings. */
    private final List<String> reflectiveHandles = new ArrayList<>();

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
        for (BeanModel bean : this.beans) {
            factoryNames.put(bean, uniqueName("bean_" + identifier(bean.type)));
        }
    }

    String generate() {
        CodeWriter factories = new CodeWriter().indent();
        for (BeanModel bean : beans) {
            factories.blank();
            writeFactory(factories, bean);
        }

        CodeWriter out = new CodeWriter();
        out.line("package " + PACKAGE + ";");
        out.blank();
        writeClassJavadoc(out);
        out.line("@" + generatedAnnotation() + "(" + CodeWriter.literal(InjectorProcessor.class.getName()) + ")");
        out.line("@SuppressWarnings({\"unchecked\", \"rawtypes\", \"cast\"})");
        out.open("public class " + CLASS_NAME + " {");
        out.blank();
        writeBindings(out);
        out.blank();
        if (!reflectiveHandles.isEmpty()) {
            out.line("// Handles for members that are not public and therefore cannot be accessed directly.");
            reflectiveHandles.forEach(out::line);
            out.blank();
        }
        writeState(out);
        writeConstructor(out);
        writePublicApi(out);
        writeResolution(out);
        out.raw(factories.toString());
        out.blank();
        writeReflectionHelpers(out);
        writeExceptionClass(out);
        out.close();
        return out.toString();
    }

    /**
     * The {@code @Generated} annotation that exists at the source level being compiled:
     * {@code javax.annotation.processing.Generated} from Java 9 on, {@code javax.annotation.Generated} before.
     */
    private String generatedAnnotation() {
        return sourceVersion.compareTo(SourceVersion.RELEASE_8) > 0
                ? "javax.annotation.processing.Generated"
                : "javax.annotation.Generated";
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

    private void writeBindings(CodeWriter out) {
        out.line("/** Requested type to implementation class, for every bean and each of its unambiguous supertypes."
                + " */");
        out.line("private static final java.util.Map<Class<?>, Class<?>> BINDINGS = new java.util.HashMap<>();");
        out.line("/** Supertypes shared by several beans, mapped to their names for error messages. */");
        out.line("private static final java.util.Map<Class<?>, String> AMBIGUOUS = new java.util.HashMap<>();");
        out.blank();
        out.open("static {");
        bindings.forEach((requested, bean) -> out.line("BINDINGS.put(" + classLiteral(requested) + ", "
                + classLiteral(bean.type) + ");"));
        ambiguous.forEach((requested, candidates) -> out.line("AMBIGUOUS.put(" + classLiteral(requested) + ", "
                + CodeWriter.literal(DependencyResolver.describe(candidates)) + ");"));
        out.close();
    }

    private void writeState(CodeWriter out) {
        out.line("/** Bean instances by implementation class, plus everything registered through {@link #bind}. */");
        out.line("private final java.util.Map<Class<?>, Object> instances = new java.util.HashMap<>();");
        out.line("/** Implementation classes registered through {@link #bind(Class, Class)}, by requested type. */");
        out.line("private final java.util.Map<Class<?>, Class<?>> implementations = new java.util.HashMap<>();");
        out.line("/** Types whose instance has been created, injected or returned; their bindings can no longer be"
                + " replaced. */");
        out.line("private final java.util.Set<Class<?>> resolved = new java.util.HashSet<>();");
        out.line("private final java.util.Map<Class<?>, java.util.function.Supplier<?>> factories = new"
                + " java.util.HashMap<>();");
        out.line("/** {@code @PostConstruct} callbacks of beans wired during the current resolution, dependencies"
                + " first. */");
        out.line("private final java.util.ArrayDeque<Runnable> postConstructQueue = new java.util.ArrayDeque<>();");
        out.line("private boolean resolving;");
        out.blank();
    }

    private void writeConstructor(CodeWriter out) {
        out.open("public " + CLASS_NAME + "() {");
        for (BeanModel bean : beans) {
            out.line("factories.put(" + classLiteral(bean.type) + ", this::" + factoryNames.get(bean) + ");");
        }
        out.close();
        out.blank();
    }

    private void writePublicApi(CodeWriter out) {
        out.line("/**");
        out.line(" * Returns the bean of the given type, creating it and everything it depends on if necessary.");
        out.line(" *");
        out.line(" * @param type a bean class, a supertype implemented by exactly one bean, or a type passed to {@link"
                + " #bind}");
        out.line(" * @throws InjectionException if the type is unknown or a dependency cannot be satisfied");
        out.line(" */");
        out.open("public <T> T getInstance(Class<T> type) {");
        out.line("java.util.Objects.requireNonNull(type, \"type\");");
        out.line("return type.cast(enter(() -> resolve(type)));");
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
        out.line(" * @throws IllegalStateException if {@code type} has already been resolved or a resolution is in"
                + " progress");
        out.line(" */");
        out.open("public <T> " + CLASS_NAME + " bind(Class<T> type, T instance) {");
        out.line("java.util.Objects.requireNonNull(type, \"type\");");
        out.line("java.util.Objects.requireNonNull(instance, \"instance\");");
        out.open("synchronized (instances) {");
        out.line("checkBindable(type);");
        out.line("implementations.remove(type);");
        out.line("instances.put(type, instance);");
        out.close();
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
        out.line(" * @throws IllegalStateException if {@code type} has already been resolved or a resolution is in"
                + " progress");
        out.line(" */");
        out.open("public <T> " + CLASS_NAME + " bind(Class<T> type, Class<? extends T> implementation) {");
        out.line("java.util.Objects.requireNonNull(type, \"type\");");
        out.line("java.util.Objects.requireNonNull(implementation, \"implementation\");");
        out.open("if (!factories.containsKey(implementation)) {");
        out.line("throw new IllegalArgumentException(implementation.getName() + \" is not a bean managed by this"
                + " Injector; bind an instance instead\");");
        out.close();
        out.open("if (type == implementation || !type.isAssignableFrom(implementation)) {");
        out.line("throw new IllegalArgumentException(\"Cannot bind \" + type.getName() + \" to \" +"
                + " implementation.getName());");
        out.close();
        out.open("synchronized (instances) {");
        out.line("checkBindable(type);");
        out.open("for (Class<?> next = implementations.get(implementation); next != null; next ="
                + " implementations.get(next)) {");
        out.open("if (next == type) {");
        out.line("throw new IllegalArgumentException(\"Binding \" + type.getName() + \" to \" +"
                + " implementation.getName() + \" would form a cycle\");");
        out.close();
        out.close();
        out.line("instances.remove(type);");
        out.line("implementations.put(type, implementation);");
        out.close();
        out.line("return this;");
        out.close();
        out.blank();
        out.open("private void checkBindable(Class<?> type) {");
        out.open("if (resolving) {");
        out.line("throw new IllegalStateException(\"Cannot bind \" + type.getName() + \" while a resolution is in"
                + " progress\");");
        out.close();
        out.open("if (resolved.contains(type)) {");
        out.line("throw new IllegalStateException(type.getName() + \" has already been resolved by this Injector (an"
                + " instance was\"");
        out.line("        + \" created, injected or returned); bind before the type is first used\");");
        out.close();
        out.close();
        out.blank();
    }

    private void writeResolution(CodeWriter out) {
        out.line("/** Runs a resolution, and if it is the outermost one, fires the queued @PostConstruct callbacks"
                + " afterwards. */");
        out.open("private <T> T enter(java.util.function.Supplier<T> resolution) {");
        out.open("synchronized (instances) {");
        out.open("if (resolving) {");
        out.line("return resolution.get();");
        out.close();
        out.line("resolving = true;");
        out.line("java.util.Set<Class<?>> before = new java.util.HashSet<>(instances.keySet());");
        out.line("java.util.Set<Class<?>> resolvedBefore = new java.util.HashSet<>(resolved);");
        out.open("try {");
        out.line("T result = resolution.get();");
        out.line("Runnable callback;");
        out.open("while ((callback = postConstructQueue.poll()) != null) {");
        out.line("callback.run();");
        out.close();
        out.line("return result;");
        out.close("} catch (RuntimeException | Error e) {");
        out.indent();
        out.line("// Roll back so that a retry after bind() does not see half-wired instances, and so that the");
        out.line("// bindings the discarded instances consumed can be replaced again.");
        out.line("instances.keySet().retainAll(before);");
        out.line("resolved.retainAll(resolvedBefore);");
        out.line("postConstructQueue.clear();");
        out.line("throw e;");
        out.close("} finally {");
        out.indent();
        out.line("resolving = false;");
        out.close();
        out.close();
        out.close();
        out.blank();
        out.open("private Object resolve(Class<?> type) {");
        out.line("Object existing = existing(type);");
        out.open("if (existing != null) {");
        out.line("return existing;");
        out.close();
        out.line("Class<?> implementation = BINDINGS.get(type);");
        out.open("if (implementation == null) {");
        out.line("String candidates = AMBIGUOUS.get(type);");
        out.line("throw new InjectionException(candidates != null");
        out.line("        ? \"Type \" + type.getName() + \" is implemented by several beans (\" + candidates");
        out.line("                + \"); request one of them, or bind an implementation or instance for the type\"");
        out.line("        : \"No bean of type \" + type.getName() + \" is known to this Injector and none was"
                + " bound;\"");
        out.line("                + \" use bind(\" + type.getSimpleName() + \".class, ...) to supply an implementation"
                + " class or an instance\");");
        out.close();
        out.open("if (implementation != type) {");
        out.line("return resolve(implementation);");
        out.close();
        out.line("return factories.get(type).get();");
        out.close();
        out.blank();
        out.line("/** The instance created or bound for {@code type}, following a bound implementation class; null if"
                + " none yet. */");
        out.open("private Object existing(Class<?> type) {");
        out.line("Object existing = instances.get(type);");
        out.open("if (existing == null) {");
        out.line("Class<?> bound = implementations.get(type);");
        out.line("existing = bound != null ? resolve(bound) : null;");
        out.close();
        out.open("if (existing != null) {");
        out.line("// Handed out: from now on the binding of this type cannot be replaced.");
        out.line("resolved.add(type);");
        out.close();
        out.line("return existing;");
        out.close();
        out.blank();
        out.line("/** A dependency resolved at compile time: a binding for the declared type still takes precedence."
                + " */");
        out.open("private <T> T dependency(Class<T> declaredType, java.util.function.Supplier<?> implementation) {");
        out.line("Object bound = existing(declaredType);");
        out.line("return declaredType.cast(bound != null ? bound : implementation.get());");
        out.close();
        out.blank();
        out.line("/** A dependency that could not be resolved at compile time and must have been bound. */");
        out.open("private <T> T required(Class<T> declaredType, String injectionPoint, String reason) {");
        out.line("Object bound = existing(declaredType);");
        out.open("if (bound == null) {");
        out.line("throw new InjectionException(\"Cannot inject \" + injectionPoint + \": \" + reason");
        out.line("        + \". Register an instance with Injector.bind(\" + declaredType.getSimpleName() + \".class,"
                + " ...) first\");");
        out.close();
        out.line("return declaredType.cast(bound);");
        out.close();
    }

    private void writeFactory(CodeWriter out, BeanModel bean) {
        String type = names.render(types.erasure(bean.declaredType));
        String literal = classLiteral(bean.type);

        out.line("/** {@code " + bean.qualifiedName + "} (@" + simpleName(bean.beanAnnotation) + ") */");
        out.open("private " + type + " " + factoryNames.get(bean) + "() {");
        out.line("Object existing = existing(" + literal + ");");
        out.open("if (existing != null) {");
        out.line("return (" + type + ") existing;");
        out.close();

        Constructor constructor = bean.constructor;
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < constructor.parameters().size(); i++) {
            Dependency parameter = constructor.parameters().get(i);
            String local = "arg" + i;
            String localType = names.isAccessible(parameter.declaredType)
                    ? names.render(parameter.declaredType)
                    : "Object";
            out.line(localType + " " + local + " = " + valueExpression(parameter) + ";");
            arguments.add(local);
        }
        if (!arguments.isEmpty()) {
            out.line("// Resolving constructor arguments may already have created this bean through a dependency"
                    + " cycle.");
            out.line("existing = existing(" + literal + ");");
            out.open("if (existing != null) {");
            out.line("return (" + type + ") existing;");
            out.close();
        }
        out.line(type + " instance = " + constructorCall(bean, constructor, arguments) + ";");
        out.line("instances.put(" + literal + ", instance);");
        out.line("resolved.add(" + literal + ");");

        for (MemberInjection member : bean.members) {
            if (member instanceof FieldInjection field) {
                writeFieldInjection(out, field);
            } else if (member instanceof MethodInjection method) {
                writeMethodInjection(out, method);
            } else {
                throw new IllegalStateException("Unknown member type: " + member);
            }
        }
        for (Callback callback : bean.postConstructs) {
            out.line("postConstructQueue.add(() -> " + callbackCall(callback) + ");");
        }
        out.line("return instance;");
        out.close();
    }

    private String constructorCall(BeanModel bean, Constructor constructor, List<String> arguments) {
        boolean direct = constructor.element().getModifiers().contains(Modifier.PUBLIC)
                && constructor.parameters().stream().allMatch(p -> names.isAccessible(p.declaredType));
        if (direct) {
            return "new " + names.render(types.erasure(bean.declaredType))
                    + (bean.type.getTypeParameters().isEmpty() ? "" : "<>")
                    + "(" + String.join(", ", arguments) + ")";
        }
        String handle = declareHandle("CONSTRUCTOR_" + identifier(bean.type), "java.lang.reflect.Constructor<?>",
                "constructor(" + classLiteral(bean.type) + parameterLiterals(constructor.element()) + ")");
        return "(" + names.render(types.erasure(bean.declaredType)) + ") newInstance(" + handle
                + arguments.stream().map(a -> ", " + a).collect(Collectors.joining()) + ")";
    }

    private void writeFieldInjection(CodeWriter out, FieldInjection injection) {
        VariableElement field = injection.field();
        Dependency dependency = injection.dependency();
        boolean direct = field.getModifiers().contains(Modifier.PUBLIC) && names.isAccessible(dependency.declaredType);
        if (direct) {
            out.line("instance." + field.getSimpleName() + " = " + valueExpression(dependency) + ";");
            return;
        }
        String handle = declareHandle("FIELD_" + identifier(injection.owner()) + "_" + field.getSimpleName(),
                "java.lang.reflect.Field",
                "field(" + classLiteral(injection.owner()) + ", "
                        + CodeWriter.literal(field.getSimpleName().toString()) + ")");
        out.line("set(" + handle + ", instance, " + valueExpression(dependency) + ");");
    }

    private void writeMethodInjection(CodeWriter out, MethodInjection injection) {
        ExecutableElement method = injection.method();
        boolean direct = method.getModifiers().contains(Modifier.PUBLIC)
                && injection.parameters().stream().allMatch(p -> names.isAccessible(p.declaredType));
        if (direct) {
            out.line("instance." + method.getSimpleName() + "(" + injection.parameters().stream()
                    .map(this::typedExpression).collect(Collectors.joining(", ")) + ");");
            return;
        }
        String handle = declareHandle("METHOD_" + identifier(injection.owner()) + "_" + method.getSimpleName(),
                "java.lang.reflect.Method",
                "method(" + classLiteral(injection.owner()) + ", "
                        + CodeWriter.literal(method.getSimpleName().toString())
                        + parameterLiterals(method) + ")");
        out.line("invoke(" + handle + ", instance" + injection.parameters().stream()
                .map(p -> ", " + valueExpression(p)).collect(Collectors.joining()) + ");");
    }

    private String callbackCall(Callback callback) {
        ExecutableElement method = callback.method();
        if (method.getModifiers().contains(Modifier.PUBLIC)) {
            return "instance." + method.getSimpleName() + "()";
        }
        String handle = declareHandle("METHOD_" + identifier(callback.owner()) + "_" + method.getSimpleName(),
                "java.lang.reflect.Method",
                "method(" + classLiteral(callback.owner()) + ", "
                        + CodeWriter.literal(method.getSimpleName().toString()) + ")");
        return "invoke(" + handle + ", instance)";
    }

    /** Class literals of the erased declared parameter types, as needed for reflective lookup. */
    private String parameterLiterals(ExecutableElement executable) {
        return executable.getParameters().stream()
                .map(p -> ", " + names.classLiteral(p.asType()))
                .collect(Collectors.joining());
    }

    /** An expression producing the value to inject, cast to the declared type where that is nameable. */
    private String typedExpression(Dependency dependency) {
        String value = valueExpression(dependency);
        if (dependency.provider || !names.isAccessible(dependency.declaredType)) {
            return value;
        }
        return "(" + names.render(dependency.declaredType) + ") " + value;
    }

    private String valueExpression(Dependency dependency) {
        if (!dependency.provider) {
            return resolutionExpression(dependency);
        }
        // A Provider is a lambda; casting it fixes the target type wherever the lambda is passed as Object.
        String providerType = names.isAccessible(dependency.declaredType)
                ? names.render(dependency.declaredType)
                : names.renderErasure(dependency.declaredType) + "<?>";
        return "(" + providerType + ") () -> enter(() -> " + resolutionExpression(dependency) + ")";
    }

    private String resolutionExpression(Dependency dependency) {
        String requested = requestedClassLiteral(dependency);
        if (dependency.resolved == null) {
            return "required(" + requested + ", " + CodeWriter.literal(dependency.description) + ", "
                    + CodeWriter.literal(dependency.unresolvedReason) + ")";
        }
        String factory = factoryNames.get(dependency.resolved);
        if (types.isSameType(types.erasure(dependency.requestedType),
                types.erasure(dependency.resolved.declaredType))) {
            return factory + "()";
        }
        return "dependency(" + requested + ", this::" + factory + ")";
    }

    private String requestedClassLiteral(Dependency dependency) {
        TypeMirror requested = dependency.requestedType;
        if (requested.getKind().isPrimitive()) {
            requested = types.boxedClass((javax.lang.model.type.PrimitiveType) requested).asType();
        }
        return names.classLiteral(requested);
    }

    private String declareHandle(String baseName, String type, String initializer) {
        String existing = handleNames.get(initializer);
        if (existing != null) {
            return existing;
        }
        String name = uniqueName(baseName);
        handleNames.put(initializer, name);
        reflectiveHandles.add("private static final " + type + " " + name + " = " + initializer + ";");
        return name;
    }

    private String classLiteral(TypeElement type) {
        return names.classLiteral(type.asType());
    }

    private String identifier(TypeElement type) {
        return elements.getBinaryName(type).toString().replace('.', '_');
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

    private void writeReflectionHelpers(CodeWriter out) {
        out.open("private static Class<?> loadClass(String binaryName) {");
        out.open("try {");
        out.line("return Class.forName(binaryName, false, " + CLASS_NAME + ".class.getClassLoader());");
        out.close("} catch (ClassNotFoundException e) {");
        out.indent();
        out.line("throw new InjectionException(\"Class \" + binaryName + \" is not on the class path\", e);");
        out.close();
        out.close();
        out.blank();
        out.open("private static java.lang.reflect.Field field(Class<?> owner, String name) {");
        out.open("try {");
        out.line("java.lang.reflect.Field field = owner.getDeclaredField(name);");
        out.line("field.setAccessible(true);");
        out.line("return field;");
        out.close("} catch (NoSuchFieldException | RuntimeException e) {");
        out.indent();
        out.line("throw new InjectionException(\"Cannot access field \" + owner.getName() + \".\" + name, e);");
        out.close();
        out.close();
        out.blank();
        out.open("private static java.lang.reflect.Method method(Class<?> owner, String name, Class<?>..."
                + " parameterTypes) {");
        out.open("try {");
        out.line("java.lang.reflect.Method method = owner.getDeclaredMethod(name, parameterTypes);");
        out.line("method.setAccessible(true);");
        out.line("return method;");
        out.close("} catch (NoSuchMethodException | RuntimeException e) {");
        out.indent();
        out.line("throw new InjectionException(\"Cannot access method \" + owner.getName() + \".\" + name, e);");
        out.close();
        out.close();
        out.blank();
        out.open("private static java.lang.reflect.Constructor<?> constructor(Class<?> owner, Class<?>..."
                + " parameterTypes) {");
        out.open("try {");
        out.line("java.lang.reflect.Constructor<?> constructor = owner.getDeclaredConstructor(parameterTypes);");
        out.line("constructor.setAccessible(true);");
        out.line("return constructor;");
        out.close("} catch (NoSuchMethodException | RuntimeException e) {");
        out.indent();
        out.line("throw new InjectionException(\"Cannot access constructor of \" + owner.getName(), e);");
        out.close();
        out.close();
        out.blank();
        out.open("private static void set(java.lang.reflect.Field field, Object target, Object value) {");
        out.open("try {");
        out.line("field.set(target, value);");
        out.close("} catch (IllegalAccessException | IllegalArgumentException e) {");
        out.indent();
        out.line("throw new InjectionException(\"Cannot inject \" + field.getDeclaringClass().getName() + \".\" +"
                + " field.getName(), e);");
        out.close();
        out.close();
        out.blank();
        out.open("private static Object invoke(java.lang.reflect.Method method, Object target, Object... arguments) {");
        out.open("try {");
        out.line("return method.invoke(target, arguments);");
        out.close("} catch (IllegalAccessException | IllegalArgumentException e) {");
        out.indent();
        out.line("throw new InjectionException(\"Cannot invoke \" + method.getDeclaringClass().getName() + \".\" +"
                + " method.getName(), e);");
        out.close("} catch (java.lang.reflect.InvocationTargetException e) {");
        out.indent();
        out.line("throw unwrap(e);");
        out.close();
        out.close();
        out.blank();
        out.open("private static Object newInstance(java.lang.reflect.Constructor<?> constructor, Object... arguments)"
                + " {");
        out.open("try {");
        out.line("return constructor.newInstance(arguments);");
        out.close("} catch (InstantiationException | IllegalAccessException | IllegalArgumentException e) {");
        out.indent();
        out.line("throw new InjectionException(\"Cannot instantiate \" + constructor.getDeclaringClass().getName(),"
                + " e);");
        out.close("} catch (java.lang.reflect.InvocationTargetException e) {");
        out.indent();
        out.line("throw unwrap(e);");
        out.close();
        out.close();
        out.blank();
        out.line("/** Rethrows what user code threw, so that test assertions see the original exception. */");
        out.open("private static RuntimeException unwrap(java.lang.reflect.InvocationTargetException e) {");
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
