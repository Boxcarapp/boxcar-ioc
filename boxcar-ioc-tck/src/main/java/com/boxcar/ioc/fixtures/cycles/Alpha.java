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

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/** Alpha and Beta inject each other through fields: resolved lazily after both instances exist. */
@Stateless
public class Alpha {

    /** Creates the bean; the injector supplies its dependencies afterwards. */
    public Alpha() {
    }

    @Inject
    private Beta beta;

    private boolean betaWiredAtInit;
    private boolean betaHadAlphaAtInit;

    @PostConstruct
    void init() {
        betaWiredAtInit = beta != null;
        betaHadAlphaAtInit = beta != null && beta.alpha() == this;
        CallbackOrder.record("alpha");
    }

    /**
     * Returns the field-injected {@link Beta}.
     *
     * @return the field-injected {@link Beta}
     */
    public Beta beta() {
        return beta;
    }

    /**
     * Returns whether {@code beta} was already injected when the callback ran.
     *
     * @return whether {@code beta} was already injected when the callback ran
     */
    public boolean betaWiredAtInit() {
        return betaWiredAtInit;
    }

    /**
     * Returns whether {@code beta} already referred back to this instance when the callback ran.
     *
     * @return whether {@code beta} already referred back to this instance when the callback ran
     */
    public boolean betaHadAlphaAtInit() {
        return betaHadAlphaAtInit;
    }
}
