package com.boxcar.ioc.fixtures.inheritance;

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

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/** Collects messages so that tests can see which service called it. */
@Singleton
public class Notifier {

    /** Creates the bean. */
    public Notifier() {
    }

    private final List<String> sent = new ArrayList<>();

    /**
     * Records a message instead of sending it.
     *
     * @param message the message a service wanted to send
     */
    public void send(String message) {
        sent.add(message);
    }

    /**
     * Returns the messages recorded so far.
     *
     * @return the messages recorded so far
     */
    public List<String> sent() {
        return List.copyOf(sent);
    }
}
