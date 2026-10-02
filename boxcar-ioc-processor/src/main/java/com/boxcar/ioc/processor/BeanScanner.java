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
import com.boxcar.ioc.processor.BeanModel.MethodInjection;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Builds a {@link BeanModel} for a bean class by walking its inheritance hierarchy and collecting
 * every {@code @Inject}/{@code @EJB} constructor, field and method plus {@code @PostConstruct}
 * callbacks, following the ordering and overriding rules of the Jakarta Inject specification.
 */
final class BeanScanner {

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;

    BeanScanner(Types types, Elements elements, Diagnostics diagnostics) {
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
    }

    /**
     * Whether any injection point in the class hierarchy of {@code bean} mentions a type that could
     * not be resolved, typically because another annotation processor has yet to generate it.
     */
    boolean hasUnresolvedTypes(TypeElement bean) {
        for (TypeElement owner : hierarchy(bean)) {
            for (Element member : owner.getEnclosedElements()) {
                if (!Annotations.isAnnotated(member, Annotations.INJECT)) {
                    continue;
                }
                if (member instanceof VariableElement field && containsErrorType(field.asType())) {
                    return true;
                }
                if (member instanceof ExecutableElement executable
                        && executable.getParameters().stream().anyMatch(p -> containsErrorType(p.asType()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Scans {@code type}, reporting problems through the messager.
     *
     * @return the model, or null if the class cannot be a bean (abstract classes are skipped silently
     *         because they are legitimately annotated as bases for concrete beans; anything else that is
     *         unusable is reported as an error)
     */
    BeanModel scan(TypeElement type, String beanAnnotation) {
        String annotation = "@" + simpleName(beanAnnotation);
        if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) {
            diagnostics.error(type, annotation + " can only be applied to classes; "
                    + type.getQualifiedName() + " is " + type.getKind().name().toLowerCase().replace('_', ' '));
            return null;
        }
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            return null;
        }
        if (type.getNestingKind() == NestingKind.LOCAL || type.getNestingKind() == NestingKind.ANONYMOUS) {
            diagnostics.error(type, "Local and anonymous classes cannot be beans");
            return null;
        }
        if (type.getNestingKind() == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC)) {
            diagnostics.error(type, "Bean class " + type.getQualifiedName()
                    + " is a non-static inner class; the injector cannot instantiate it without an enclosing instance");
            return null;
        }
        if (!TypeNames.isPublicType(type)) {
            diagnostics.error(type, "Bean class " + type.getQualifiedName()
                    + " must be public (including all enclosing classes) so that the generated com.boxcar.Injector"
                    + " can reference it");
            return null;
        }
        if (elements.getPackageOf(type).isUnnamed()) {
            diagnostics.error(type, "Bean class " + type.getQualifiedName()
                    + " is in the default package and cannot be referenced by the generated com.boxcar.Injector");
            return null;
        }

        BeanModel bean = new BeanModel(type, beanAnnotation);
        bean.constructor = findConstructor(bean);
        if (bean.constructor == null) {
            return null;
        }

        List<TypeElement> hierarchy = hierarchy(type);
        for (int i = 0; i < hierarchy.size(); i++) {
            TypeElement owner = hierarchy.get(i);
            List<TypeElement> subclasses = hierarchy.subList(i + 1, hierarchy.size());
            scanFields(bean, owner);
            scanMethods(bean, owner, subclasses);
            scanPostConstruct(bean, owner, subclasses);
        }
        return bean;
    }

    private Constructor findConstructor(BeanModel bean) {
        List<ExecutableElement> constructors = ElementFilter.constructorsIn(bean.type.getEnclosedElements());
        List<ExecutableElement> injectable = constructors.stream()
                .filter(c -> Annotations.isAnnotated(c, Annotations.CONSTRUCTOR_INJECT))
                .toList();
        if (injectable.size() > 1) {
            diagnostics.error(bean.type, "Bean class " + bean.qualifiedName
                    + " has " + injectable.size() + " @Inject constructors; at most one is allowed");
            return null;
        }
        if (injectable.size() == 1) {
            ExecutableElement constructor = injectable.get(0);
            List<Dependency> parameters = new ArrayList<>();
            List<? extends VariableElement> params = constructor.getParameters();
            for (int i = 0; i < params.size(); i++) {
                String description = bean.qualifiedName + "(...) parameter '" + params.get(i).getSimpleName() + "'";
                parameters.add(dependency(params.get(i).asType(), description, params.get(i)));
            }
            return new Constructor(constructor, parameters);
        }
        for (ExecutableElement constructor : constructors) {
            if (constructor.getParameters().isEmpty()) {
                return new Constructor(constructor, List.of());
            }
        }
        diagnostics.error(bean.type, "Bean class " + bean.qualifiedName
                + " has neither an @Inject annotated constructor nor a no-argument constructor");
        return null;
    }

    /** The bean class and its superclasses, topmost superclass first, excluding java.lang.Object. */
    private List<TypeElement> hierarchy(TypeElement type) {
        List<TypeElement> chain = new ArrayList<>();
        for (TypeElement current = type; current != null; current = superclass(current)) {
            if (current.getQualifiedName().contentEquals("java.lang.Object")) {
                break;
            }
            chain.add(0, current);
        }
        return chain;
    }

    private static TypeElement superclass(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.DECLARED) {
            return null;
        }
        return (TypeElement) ((DeclaredType) superclass).asElement();
    }

    private void scanFields(BeanModel bean, TypeElement owner) {
        for (VariableElement field : ElementFilter.fieldsIn(owner.getEnclosedElements())) {
            String annotation = Annotations.firstAnnotation(field, Annotations.INJECT);
            if (annotation == null) {
                continue;
            }
            String description = owner.getQualifiedName() + "." + field.getSimpleName();
            if (field.getModifiers().contains(Modifier.STATIC)) {
                diagnostics.error(field, "@" + simpleName(annotation) + " field " + description
                        + " is static; static injection is not supported");
                continue;
            }
            if (field.getModifiers().contains(Modifier.FINAL)) {
                diagnostics.error(field, "@" + simpleName(annotation) + " field " + description
                        + " is final and cannot be injected");
                continue;
            }
            TypeMirror fieldType = types.asMemberOf(bean.declaredType, field);
            bean.members.add(new FieldInjection(field, owner, dependency(fieldType, description, field)));
        }
    }

    private void scanMethods(BeanModel bean, TypeElement owner, List<TypeElement> subclasses) {
        for (ExecutableElement method : ElementFilter.methodsIn(owner.getEnclosedElements())) {
            String annotation = Annotations.firstAnnotation(method, Annotations.INJECT);
            if (annotation == null) {
                continue;
            }
            String description = owner.getQualifiedName() + "." + method.getSimpleName() + "(...)";
            if (method.getModifiers().contains(Modifier.STATIC)) {
                diagnostics.error(method, "@" + simpleName(annotation) + " method " + description
                        + " is static; static injection is not supported");
                continue;
            }
            if (method.getModifiers().contains(Modifier.ABSTRACT)) {
                diagnostics.error(method, "@" + simpleName(annotation) + " method " + description + " is abstract");
                continue;
            }
            if (!method.getTypeParameters().isEmpty()) {
                diagnostics.error(method, "@" + simpleName(annotation) + " method " + description
                        + " declares type parameters, which injectable methods may not do");
                continue;
            }
            if (annotation.endsWith(".EJB") && method.getParameters().size() != 1) {
                diagnostics.error(method,
                        "@EJB method " + description + " must be a setter with exactly one parameter");
                continue;
            }
            if (isOverriddenIn(method, owner, subclasses)) {
                // Injected at most once per instance: the overriding method decides (and is skipped if it
                // is not itself annotated).
                continue;
            }
            ExecutableType methodType = (ExecutableType) types.asMemberOf(bean.declaredType, method);
            List<Dependency> parameters = new ArrayList<>();
            List<? extends VariableElement> params = method.getParameters();
            for (int i = 0; i < params.size(); i++) {
                String paramDescription = description + " parameter '" + params.get(i).getSimpleName() + "'";
                parameters.add(dependency(methodType.getParameterTypes().get(i), paramDescription, params.get(i)));
            }
            bean.members.add(new MethodInjection(method, owner, parameters));
        }
    }

    private void scanPostConstruct(BeanModel bean, TypeElement owner, List<TypeElement> subclasses) {
        List<ExecutableElement> callbacks = ElementFilter.methodsIn(owner.getEnclosedElements()).stream()
                .filter(m -> Annotations.isAnnotated(m, Annotations.POST_CONSTRUCT))
                .toList();
        if (callbacks.size() > 1) {
            diagnostics.error(owner, "Class " + owner.getQualifiedName() + " declares " + callbacks.size()
                    + " @PostConstruct methods (" + callbacks.stream().map(m -> m.getSimpleName().toString())
                    .collect(Collectors.joining(", ")) + "); at most one is allowed per class");
            return;
        }
        for (ExecutableElement callback : callbacks) {
            String description = owner.getQualifiedName() + "." + callback.getSimpleName() + "()";
            if (callback.getModifiers().contains(Modifier.STATIC)) {
                diagnostics.error(callback, "@PostConstruct method " + description + " must not be static");
                continue;
            }
            if (!callback.getParameters().isEmpty()) {
                diagnostics.error(callback, "@PostConstruct method " + description + " must not take parameters");
                continue;
            }
            if (callback.getModifiers().contains(Modifier.ABSTRACT) || isOverriddenIn(callback, owner, subclasses)) {
                continue;
            }
            bean.postConstructs.add(new Callback(callback, owner));
        }
    }

    private boolean isOverriddenIn(ExecutableElement method, TypeElement owner, List<TypeElement> subclasses) {
        for (TypeElement subclass : subclasses) {
            for (ExecutableElement candidate : ElementFilter.methodsIn(subclass.getEnclosedElements())) {
                if (elements.overrides(candidate, method, subclass)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Dependency dependency(TypeMirror declaredType, String description, Element source) {
        if (Annotations.isProvider(declaredType)) {
            List<? extends TypeMirror> arguments = ((DeclaredType) declaredType).getTypeArguments();
            if (arguments.size() == 1 && !TypeNames.isTypeVariableOrWildcard(arguments.get(0))) {
                return new Dependency(declaredType, arguments.get(0), true, description, source);
            }
        }
        return new Dependency(declaredType, declaredType, false, description, source);
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    /** Whether any part of {@code type} failed to resolve, in which case processing must be deferred. */
    private boolean containsErrorType(TypeMirror type) {
        return switch (type.getKind()) {
          case ERROR -> true;
          case DECLARED -> ((DeclaredType) type).getTypeArguments().stream().anyMatch(this::containsErrorType);
          case ARRAY -> containsErrorType(((ArrayType) type).getComponentType());
          case WILDCARD -> {
              WildcardType wildcard = (WildcardType) type;
              TypeMirror bound = wildcard.getExtendsBound() != null
                      ? wildcard.getExtendsBound()
                      : wildcard.getSuperBound();
              yield bound != null && containsErrorType(bound);
          }
          default -> false;
        };
    }
}
