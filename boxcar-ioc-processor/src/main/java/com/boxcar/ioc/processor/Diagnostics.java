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

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/** Thin wrapper over {@link Messager} that remembers whether an error has been reported. */
final class Diagnostics {

    private final Messager messager;
    private boolean errors;

    Diagnostics(Messager messager) {
        this.messager = messager;
    }

    void error(Element element, String message) {
        errors = true;
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    void warning(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.WARNING, message, element);
    }

    void warning(String message) {
        messager.printMessage(Diagnostic.Kind.WARNING, message);
    }

    void note(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.NOTE, message, element);
    }

    boolean hasErrors() {
        return errors;
    }
}
