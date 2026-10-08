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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * Turns the class-name markers in generated source into simple names with an import block, the way
 * a person would write the class.
 *
 * <p>{@link TypeNames} renders every class as a marker holding the qualified name of its top-level
 * class, followed by the names of any nested classes. {@link #apply} then imports each top-level
 * class once and replaces its markers with the simple name, except where that would be ambiguous or
 * would shadow something: two referenced classes with the same simple name, a class named like one
 * in {@code java.lang}, or like a class the generated file declares itself, stay fully qualified.
 */
final class Imports {

    /** The generated package; its classes need no import. */
    static final String PACKAGE = InjectorGenerator.PACKAGE;

    /** Marker delimiters: private-use characters that never occur in Java source. */
    private static final char START = (char) 0xE000;
    private static final char END = (char) 0xE001;
    private static final Pattern MARKER = Pattern.compile(START + "([^" + END + "]+)" + END);

    private Imports() {
    }

    /** The marker for a class: its top-level class by qualified name, then the nested path, if any. */
    static String reference(TypeElement type) {
        List<String> nested = new ArrayList<>();
        Element current = type;
        while (current.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
            nested.add(0, current.getSimpleName().toString());
            current = current.getEnclosingElement();
        }
        StringBuilder reference = new StringBuilder().append(START)
                .append(((TypeElement) current).getQualifiedName()).append(END);
        for (String name : nested) {
            reference.append('.').append(name);
        }
        return reference.toString();
    }

    /**
     * Resolves the markers in {@code source} and inserts the import block after the package declaration.
     *
     * @param declared simple names of the classes the generated file declares, which imports must not shadow
     */
    static String apply(String source, Set<String> declared, Elements elements) {
        Map<String, Integer> bySimpleName = new HashMap<>();
        Matcher matcher = MARKER.matcher(source);
        Set<String> referenced = new TreeSet<>();
        while (matcher.find()) {
            referenced.add(matcher.group(1));
        }
        for (String qualified : referenced) {
            String simple = simpleName(qualified);
            bySimpleName.put(simple, bySimpleName.getOrDefault(simple, 0) + 1);
        }
        Map<String, String> rendering = new TreeMap<>();
        Set<String> imports = new TreeSet<>();
        for (String qualified : referenced) {
            String simple = simpleName(qualified);
            String pkg = qualified.substring(0, Math.max(0, qualified.length() - simple.length() - 1));
            boolean implicit = pkg.equals("java.lang") || pkg.equals(PACKAGE);
            boolean shadows = declared.contains(simple)
                    || !pkg.equals("java.lang") && elements.getTypeElement("java.lang." + simple) != null;
            if (bySimpleName.get(simple) > 1 || shadows || pkg.isEmpty()) {
                rendering.put(qualified, qualified);
            } else {
                rendering.put(qualified, simple);
                if (!implicit) {
                    imports.add(qualified);
                }
            }
        }
        StringBuilder result = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(rendering.get(matcher.group(1))));
        }
        matcher.appendTail(result);
        if (imports.isEmpty()) {
            return result.toString();
        }
        StringBuilder block = new StringBuilder();
        for (String qualified : imports) {
            block.append("import ").append(qualified).append(";\n");
        }
        int afterPackage = result.indexOf(";\n") + 2;
        return result.insert(afterPackage, "\n" + block).toString();
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** The text with its markers resolved to qualified names, for comments and messages. */
    static String plain(String text) {
        return text.replace(String.valueOf(START), "").replace(String.valueOf(END), "");
    }
}
