package com.boxcar.ioc.fixtures.shop;

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

import jakarta.ejb.Stateful;
import java.util.ArrayList;
import java.util.List;

/** Treated as one instance per Injector, like every other supported scope. */
@Stateful
public class AuditLog {

    private static int instancesCreated;

    private final List<String> entries = new ArrayList<>();

    /** Creates the bean and counts the instantiation. */
    public AuditLog() {
        instancesCreated++;
    }

    /**
     * Returns how many instances have been created in this JVM.
     *
     * @return how many instances have been created in this JVM
     */
    public static int instancesCreated() {
        return instancesCreated;
    }

    /**
     * Appends an entry.
     *
     * @param entry what happened
     */
    public void record(String entry) {
        entries.add(entry);
    }

    /**
     * Returns the entries recorded so far.
     *
     * @return the entries recorded so far
     */
    public List<String> entries() {
        return List.copyOf(entries);
    }
}
