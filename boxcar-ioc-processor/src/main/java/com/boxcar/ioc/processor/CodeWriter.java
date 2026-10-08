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

/** Minimal indentation-aware source writer. */
final class CodeWriter {

    private static final String INDENT = "    ";
    /** The line width that wrapped text is kept within. */
    private static final int WIDTH = 110;

    private final StringBuilder out = new StringBuilder();
    private int depth;

    CodeWriter line(String text) {
        if (!text.isEmpty()) {
            out.append(INDENT.repeat(depth));
        }
        out.append(text).append('\n');
        return this;
    }

    /** Writes a statement that may span several lines, such as one containing an anonymous class. */
    CodeWriter lines(String text) {
        for (String line : text.split("\n", -1)) {
            line(line);
        }
        return this;
    }

    CodeWriter blank() {
        out.append('\n');
        return this;
    }

    /** Writes {@code text} and indents the following lines; pair with {@link #close}. */
    CodeWriter open(String text) {
        line(text);
        depth++;
        return this;
    }

    CodeWriter close(String text) {
        depth--;
        return line(text);
    }

    CodeWriter close() {
        return close("}");
    }

    /** Appends already formatted text verbatim. */
    CodeWriter raw(String text) {
        out.append(text);
        return this;
    }

    /**
     * Writes a Javadoc comment: on one line if it fits, otherwise wrapped at word boundaries the way a
     * person would. An inline tag such as {@code {@link X}} is kept whole. Class-name markers are measured
     * as the simple names {@link Imports#apply} will turn them into.
     */
    CodeWriter javadoc(String text) {
        int available = WIDTH - INDENT.length() * depth;
        if ("/** ".length() + Imports.renderedLength(text) + " */".length() <= available) {
            return line("/** " + text + " */");
        }
        line("/**");
        for (String part : wrap(text, available - " * ".length())) {
            line(" * " + part);
        }
        return line(" */");
    }

    /** Breaks the text into lines of at most {@code width} characters, at spaces outside inline tags. */
    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int length = 0;
        for (String word : words(text)) {
            int wordLength = Imports.renderedLength(word);
            if (length > 0 && length + 1 + wordLength > width) {
                lines.add(line.toString());
                line.setLength(0);
                length = 0;
            }
            if (length > 0) {
                line.append(' ');
                length++;
            }
            line.append(word);
            length += wordLength;
        }
        if (length > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** The space-separated words of the text, an inline tag like {@code {@link X}} counting as one word. */
    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int braces = 0;
        for (char c : text.toCharArray()) {
            if (c == ' ' && braces == 0) {
                if (word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                }
                continue;
            }
            if (c == '{') {
                braces++;
            } else if (c == '}' && braces > 0) {
                braces--;
            }
            word.append(c);
        }
        if (word.length() > 0) {
            words.add(word.toString());
        }
        return words;
    }

    /**
     * Writes a statement ending in a string literal, wrapped over continuation lines at word boundaries
     * the way a person would break a long message: {@code prefix + "text..."} then {@code + " ...text" + suffix}.
     */
    CodeWriter statement(String prefix, String text, String suffix) {
        int available = WIDTH - INDENT.length() * depth;
        List<String> parts = new ArrayList<>();
        int start = 0;
        int width = available - prefix.length() - 2;
        while (text.length() - start > width) {
            int end = text.lastIndexOf(' ', start + width);
            if (end <= start) {
                end = Math.min(text.length(), start + width);
            }
            parts.add(text.substring(start, end));
            start = end;
            width = available - INDENT.length() * 2 - 4;
        }
        parts.add(text.substring(start));
        for (int i = 0; i < parts.size(); i++) {
            String literal = literal(parts.get(i));
            String tail = i == parts.size() - 1 ? suffix : "";
            if (i == 0) {
                line(prefix + literal + tail);
            } else {
                line(INDENT + INDENT + "+ " + literal + tail);
            }
        }
        return this;
    }

    CodeWriter indent() {
        depth++;
        return this;
    }

    CodeWriter outdent() {
        depth--;
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    static String literal(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
              case '"' -> sb.append("\\\"");
              case '\\' -> sb.append("\\\\");
              case '\n' -> sb.append("\\n");
              case '\r' -> sb.append("\\r");
              case '\t' -> sb.append("\\t");
              default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
