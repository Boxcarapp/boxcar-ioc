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

import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleTypeVisitor14;
import javax.lang.model.util.Types;

/**
 * Renders {@link TypeMirror}s as Java source and decides whether a type can be named from the
 * generated {@code com.boxcar} package at all.
 *
 * <p>Everything is rendered fully qualified: the generated class imports nothing, so no user type
 * can ever clash with a JDK type the injector itself uses. {@link TypeMirror#toString()} is
 * deliberately avoided because javac includes type-use annotations in it.
 */
final class TypeNames {

    private final Types types;
    private final Elements elements;

    TypeNames(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    /** Fully qualified source representation of {@code type}, including type arguments. */
    String render(TypeMirror type) {
        return type.accept(new SimpleTypeVisitor14<String, Void>() {
            @Override
            public String visitDeclared(DeclaredType t, Void unused) {
                String name = ((TypeElement) t.asElement()).getQualifiedName().toString();
                List<? extends TypeMirror> args = t.getTypeArguments();
                if (args.isEmpty()) {
                    return name;
                }
                return name + args.stream().map(TypeNames.this::render)
                        .collect(Collectors.joining(", ", "<", ">"));
            }

            @Override
            public String visitArray(ArrayType t, Void unused) {
                return render(t.getComponentType()) + "[]";
            }

            @Override
            public String visitWildcard(WildcardType t, Void unused) {
                if (t.getExtendsBound() != null) {
                    return "? extends " + render(t.getExtendsBound());
                }
                if (t.getSuperBound() != null) {
                    return "? super " + render(t.getSuperBound());
                }
                return "?";
            }

            @Override
            public String visitTypeVariable(TypeVariable t, Void unused) {
                return t.asElement().getSimpleName().toString();
            }

            @Override
            protected String defaultAction(TypeMirror t, Void unused) {
                // Primitives and void: their kind name in lower case is the Java keyword.
                return t.getKind().name().toLowerCase();
            }
        }, null);
    }

    /** Source rendering of the erasure of {@code type}, e.g. {@code java.util.List} for {@code List<Foo>}. */
    String renderErasure(TypeMirror type) {
        return render(types.erasure(type));
    }

    /**
     * Whether the type can be written in source inside {@code com.boxcar}: every class it mentions
     * is public all the way up its nesting chain and it contains no type variables.
     */
    boolean isAccessible(TypeMirror type) {
        return type.accept(new SimpleTypeVisitor14<Boolean, Void>() {
            @Override
            public Boolean visitDeclared(DeclaredType t, Void unused) {
                return isPublicType((TypeElement) t.asElement())
                        && t.getTypeArguments().stream().allMatch(TypeNames.this::isAccessible);
            }

            @Override
            public Boolean visitArray(ArrayType t, Void unused) {
                return isAccessible(t.getComponentType());
            }

            @Override
            public Boolean visitWildcard(WildcardType t, Void unused) {
                TypeMirror bound = t.getExtendsBound() != null ? t.getExtendsBound() : t.getSuperBound();
                return bound == null || isAccessible(bound);
            }

            @Override
            public Boolean visitTypeVariable(TypeVariable t, Void unused) {
                return false;
            }

            @Override
            protected Boolean defaultAction(TypeMirror t, Void unused) {
                return t.getKind().isPrimitive();
            }
        }, null);
    }

    /** Whether the class and all its enclosing classes are public, so that it can be referenced from any package. */
    static boolean isPublicType(TypeElement type) {
        for (Element current = type; current != null && current.getKind() != ElementKind.PACKAGE;
                current = current.getEnclosingElement()) {
            if (!(current instanceof TypeElement typeElement)
                    || typeElement.getNestingKind() == NestingKind.LOCAL
                    || typeElement.getNestingKind() == NestingKind.ANONYMOUS
                    || !current.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A source expression evaluating to the {@link Class} object of the erasure of {@code type}.
     * Uses a class literal when the type is nameable, and otherwise loads it reflectively by binary
     * name through the generated {@code loadClass} helper.
     */
    String classLiteral(TypeMirror type) {
        TypeMirror erasure = types.erasure(type);
        if (isAccessible(erasure)) {
            return render(erasure) + ".class";
        }
        return "loadClass(\"" + binaryName(erasure) + "\")";
    }

    /** JVM binary name of the erasure of {@code type}, suitable for {@link Class#forName(String)}. */
    String binaryName(TypeMirror type) {
        TypeMirror erasure = types.erasure(type);
        return switch (erasure.getKind()) {
          case DECLARED -> elements.getBinaryName((TypeElement) ((DeclaredType) erasure).asElement()).toString();
          case ARRAY -> "[" + descriptor(((ArrayType) erasure).getComponentType());
          default -> render(erasure);
        };
    }

    private String descriptor(TypeMirror type) {
        TypeMirror erasure = types.erasure(type);
        return switch (erasure.getKind()) {
          case DECLARED -> "L" + binaryName(erasure) + ";";
          case ARRAY -> "[" + descriptor(((ArrayType) erasure).getComponentType());
          case BOOLEAN -> "Z";
          case BYTE -> "B";
          case SHORT -> "S";
          case INT -> "I";
          case LONG -> "J";
          case CHAR -> "C";
          case FLOAT -> "F";
          case DOUBLE -> "D";
          default -> throw new IllegalArgumentException("No descriptor for " + erasure.getKind());
        };
    }

    static boolean isTypeVariableOrWildcard(TypeMirror type) {
        return type.getKind() == TypeKind.TYPEVAR || type.getKind() == TypeKind.WILDCARD;
    }
}
