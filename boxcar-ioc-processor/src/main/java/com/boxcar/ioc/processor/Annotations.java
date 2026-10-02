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

import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * The subset of Jakarta (and legacy {@code javax}) annotations understood by the processor.
 *
 * <p>Annotations are matched by fully qualified name so that the processor has no compile-time
 * dependency on any Jakarta API jar; the user's code already has the ones it uses.
 */
final class Annotations {

    /** Class-level annotations that mark a class as a bean managed by the generated injector. */
    static final Set<String> BEAN_DEFINING = both(
            "inject.Singleton",
            "ejb.Singleton",
            "ejb.Stateless",
            "ejb.Stateful",
            "enterprise.context.ApplicationScoped");

    /** Member-level annotations that mark a field, method or constructor as an injection point. */
    static final Set<String> INJECT = both("inject.Inject", "ejb.EJB");

    /** Annotations allowed on constructors; {@code @EJB} is field/method only. */
    static final Set<String> CONSTRUCTOR_INJECT = both("inject.Inject");

    static final Set<String> POST_CONSTRUCT = both("annotation.PostConstruct");

    static final Set<String> PROVIDER = both("inject.Provider");

    private Annotations() {
    }

    private static Set<String> both(String... suffixes) {
        Set<String> names = new LinkedHashSet<>();
        for (String suffix : suffixes) {
            names.add("jakarta." + suffix);
            names.add("javax." + suffix);
        }
        return Set.copyOf(names);
    }

    static boolean isAnnotated(Element element, Set<String> annotationNames) {
        return firstAnnotation(element, annotationNames) != null;
    }

    /** Returns the qualified name of the first annotation on {@code element} that is in {@code annotationNames}. */
    static String firstAnnotation(Element element, Set<String> annotationNames) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String name = qualifiedName(mirror);
            if (annotationNames.contains(name)) {
                return name;
            }
        }
        return null;
    }

    /** Whether {@code type} is a (raw or parameterized) use of {@code jakarta.inject.Provider} or its javax twin. */
    static boolean isProvider(TypeMirror type) {
        if (!(type instanceof DeclaredType declared)) {
            return false;
        }
        return PROVIDER.contains(((TypeElement) declared.asElement()).getQualifiedName().toString());
    }

    private static String qualifiedName(AnnotationMirror mirror) {
        return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
    }
}
