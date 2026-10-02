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

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Everything the generator needs to know about one concrete bean class: how to construct it, which
 * members to inject (in specification order) and which lifecycle callbacks to invoke afterwards.
 */
final class BeanModel {

    final TypeElement type;
    final DeclaredType declaredType;
    final String qualifiedName;
    /** The bean-defining annotation that made this class a bean, for diagnostics. */
    final String beanAnnotation;

    Constructor constructor;
    /** Fields and methods to inject, superclass members first, fields before methods within a class. */
    final List<MemberInjection> members = new ArrayList<>();
    /** {@code @PostConstruct} methods, superclass callbacks first. */
    final List<Callback> postConstructs = new ArrayList<>();

    BeanModel(TypeElement type, String beanAnnotation) {
        this.type = type;
        this.declaredType = (DeclaredType) type.asType();
        this.qualifiedName = type.getQualifiedName().toString();
        this.beanAnnotation = beanAnnotation;
    }

    /** All dependencies of this bean, constructor parameters first, in injection order. */
    List<Dependency> dependencies() {
        List<Dependency> all = new ArrayList<>(constructor.parameters());
        for (MemberInjection member : members) {
            all.addAll(member.dependencies());
        }
        return all;
    }

    @Override
    public String toString() {
        return qualifiedName;
    }

    /** The constructor used to instantiate the bean; {@code parameters} is empty for a no-arg constructor. */
    record Constructor(ExecutableElement element, List<Dependency> parameters) {
    }

    sealed interface MemberInjection permits FieldInjection, MethodInjection {
        /** The class that declares the member, which may be a superclass of the bean. */
        TypeElement owner();

        List<Dependency> dependencies();
    }

    record FieldInjection(VariableElement field, TypeElement owner, Dependency dependency) implements MemberInjection {
        @Override
        public List<Dependency> dependencies() {
            return List.of(dependency);
        }
    }

    record MethodInjection(ExecutableElement method, TypeElement owner, List<Dependency> parameters)
            implements MemberInjection {
        @Override
        public List<Dependency> dependencies() {
            return parameters;
        }
    }

    record Callback(ExecutableElement method, TypeElement owner) {
    }

    /**
     * One thing a bean needs injected: a field value, or a constructor or method argument.
     *
     * <p>{@code declaredType} is the type as seen from the bean (type variables of generic
     * superclasses are already substituted). For {@code Provider<X>} injection points
     * {@code requestedType} is {@code X}, otherwise it equals {@code declaredType}.
     */
    static final class Dependency {
        final TypeMirror declaredType;
        final TypeMirror requestedType;
        final boolean provider;
        /** Human readable injection point, e.g. {@code com.acme.OrderService.repository}. */
        final String description;
        final Element source;

        /** Set by the resolver: the bean that satisfies this dependency, or null if none/ambiguous. */
        BeanModel resolved;
        /** Set by the resolver when {@link #resolved} is null: why nothing was chosen at compile time. */
        String unresolvedReason;

        Dependency(TypeMirror declaredType, TypeMirror requestedType, boolean provider, String description,
                Element source) {
            this.declaredType = declaredType;
            this.requestedType = requestedType;
            this.provider = provider;
            this.description = description;
            this.source = source;
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
