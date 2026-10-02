package com.boxcar.ioc.fixtures.cycles;

/*-
 * #%L
 * Boxcar IoC :: TCK
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

/** Test helper recording the order in which {@code @PostConstruct} callbacks ran. */
public final class CallbackOrder {

    private static final List<String> ORDER = new ArrayList<>();

    private CallbackOrder() {
    }

    /**
     * Appends a callback to the recorded order.
     *
     * @param name the name of the bean whose callback ran
     */
    public static void record(String name) {
        ORDER.add(name);
    }

    /**
     * Returns the order recorded so far and clears it for the next test.
     *
     * @return the bean names in the order their callbacks ran
     */
    public static List<String> reset() {
        List<String> copy = List.copyOf(ORDER);
        ORDER.clear();
        return copy;
    }
}
