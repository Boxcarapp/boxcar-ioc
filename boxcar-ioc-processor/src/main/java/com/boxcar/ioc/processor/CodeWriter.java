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

/** Minimal indentation-aware source writer. */
final class CodeWriter {

    private static final String INDENT = "    ";

    private final StringBuilder out = new StringBuilder();
    private int depth;

    CodeWriter line(String text) {
        if (!text.isEmpty()) {
            out.append(INDENT.repeat(depth));
        }
        out.append(text).append('\n');
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
