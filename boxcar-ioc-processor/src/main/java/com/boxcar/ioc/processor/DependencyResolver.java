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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Decides, at compile time, which bean satisfies each injection point and which beans are reachable
 * through {@code Injector.getInstance(Class)} under which types.
 *
 * <p>An injection point of type {@code T} is satisfied by the single bean assignable to {@code T}.
 * If several beans qualify, a bean whose class is exactly {@code T} wins; otherwise the injection
 * point is ambiguous. Ambiguous and unsatisfiable injection points are neither errors nor warnings
 * at compile time: container-provided dependencies ({@code EntityManager}, {@code Event}, ...) and
 * qualified beans (qualifiers are ignored) legitimately have no bean, and a test supplies them through
 * {@code Injector.bind}. The generated injector reports an injection point that is still unbound when
 * it is first needed, with an {@code InjectionException} that names the point, the reason recorded
 * here, and the {@code bind} call to make.
 */
final class DependencyResolver {

    private final Types types;
    private final TypeNames names;
    private final List<BeanModel> beans;

    DependencyResolver(Types types, TypeNames names, Collection<BeanModel> beans) {
        this.types = types;
        this.names = names;
        this.beans = List.copyOf(beans);
    }

    void resolveAll() {
        for (BeanModel bean : beans) {
            for (Dependency dependency : bean.dependencies()) {
                resolve(dependency);
            }
        }
    }

    private void resolve(Dependency dependency) {
        TypeMirror requested = dependency.requestedType;
        String rendered = simpleNames(names.describe(requested));
        if (TypeNames.isTypeVariableOrWildcard(requested) || requested.getKind().isPrimitive()
                || requested.getKind() != TypeKind.DECLARED && requested.getKind() != TypeKind.ARRAY) {
            unresolved(dependency, "type " + rendered + " cannot be satisfied by a bean");
            return;
        }
        List<BeanModel> candidates = new ArrayList<>();
        for (BeanModel bean : beans) {
            if (types.isAssignable(types.erasure(bean.declaredType), requested)) {
                candidates.add(bean);
            }
        }
        if (candidates.size() == 1) {
            dependency.resolved = candidates.get(0);
            return;
        }
        if (candidates.isEmpty()) {
            unresolved(dependency, "no bean of type " + rendered + " is known");
            return;
        }
        List<BeanModel> exact = new ArrayList<>();
        for (BeanModel bean : candidates) {
            if (types.isSameType(types.erasure(bean.declaredType), types.erasure(requested))) {
                exact.add(bean);
            }
        }
        if (exact.size() == 1) {
            dependency.resolved = exact.get(0);
            return;
        }
        unresolved(dependency, "type " + rendered + " is implemented by several beans: "
                + simpleNames(describe(candidates)) + " (qualifiers are ignored)");
    }

    /** The text with package prefixes removed, for messages read by people: {@code Repository<User>}. */
    static String simpleNames(String qualified) {
        return qualified.replaceAll("[a-z_][a-z0-9_]*\\.", "");
    }

    /** Records why nothing was chosen; the generated injector reports it if the point is still unbound when needed. */
    private static void unresolved(Dependency dependency, String reason) {
        dependency.unresolvedReason = reason;
    }

    /**
     * Computes what {@code getInstance(Class)} can be asked for: for every bean and every one of its
     * (erased) supertypes other than {@code Object}, the bean that type resolves to. The generator turns
     * this into a chain of class comparisons.
     *
     * @return bindings from requested type to implementing bean; supertypes shared by several beans
     *         without an exact match are collected in {@code ambiguous} instead
     */
    Map<TypeElement, BeanModel> bindings(Map<TypeElement, List<BeanModel>> ambiguous) {
        Map<TypeElement, List<BeanModel>> bySupertype = new LinkedHashMap<>();
        for (BeanModel bean : beans) {
            for (TypeElement supertype : supertypes(bean.type)) {
                List<BeanModel> implementations = bySupertype.get(supertype);
                if (implementations == null) {
                    implementations = new ArrayList<>();
                    bySupertype.put(supertype, implementations);
                }
                implementations.add(bean);
            }
        }
        Map<TypeElement, BeanModel> bindings = new LinkedHashMap<>();
        for (Map.Entry<TypeElement, List<BeanModel>> entry : bySupertype.entrySet()) {
            TypeElement supertype = entry.getKey();
            List<BeanModel> implementations = entry.getValue();
            if (implementations.size() == 1) {
                bindings.put(supertype, implementations.get(0));
                continue;
            }
            List<BeanModel> exact = new ArrayList<>();
            for (BeanModel bean : implementations) {
                if (bean.type.equals(supertype)) {
                    exact.add(bean);
                }
            }
            if (exact.size() == 1) {
                bindings.put(supertype, exact.get(0));
            } else {
                ambiguous.put(supertype, implementations);
            }
        }
        return bindings;
    }

    /** The type itself plus all superclasses and interfaces, transitively, excluding java.lang.Object. */
    private Set<TypeElement> supertypes(TypeElement type) {
        Set<TypeElement> result = new LinkedHashSet<>();
        collectSupertypes(type.asType(), result);
        Iterator<TypeElement> iterator = result.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getQualifiedName().contentEquals("java.lang.Object")) {
                iterator.remove();
            }
        }
        return result;
    }

    private void collectSupertypes(TypeMirror type, Set<TypeElement> result) {
        if (type.getKind() != TypeKind.DECLARED) {
            return;
        }
        if (!result.add((TypeElement) ((DeclaredType) type).asElement())) {
            return;
        }
        for (TypeMirror supertype : types.directSupertypes(type)) {
            collectSupertypes(supertype, result);
        }
    }

    static String describe(Collection<BeanModel> beans) {
        List<String> names = new ArrayList<>();
        for (BeanModel bean : beans) {
            names.add(bean.qualifiedName);
        }
        Collections.sort(names);
        return String.join(", ", names);
    }
}
