package com.boxcar;

/*-
 * #%L
 * Boxcar IoC :: Reflection
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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The subset of Jakarta (and legacy {@code javax}) annotations understood by the injector, matched by
 * name so that this module has no dependency on any Jakarta API jar.
 */
final class Annotations {

    /** Class-level annotations that mark a class as a bean the injector may instantiate. */
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

    static boolean isAnnotated(AnnotatedElement element, Set<String> annotationNames) {
        return firstAnnotation(element, annotationNames) != null;
    }

    /** The simple name of the first annotation on {@code element} that is in {@code annotationNames}, or null. */
    static String firstAnnotation(AnnotatedElement element, Set<String> annotationNames) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            String name = annotation.annotationType().getName();
            if (annotationNames.contains(name)) {
                return name.substring(name.lastIndexOf('.') + 1);
            }
        }
        return null;
    }
}
