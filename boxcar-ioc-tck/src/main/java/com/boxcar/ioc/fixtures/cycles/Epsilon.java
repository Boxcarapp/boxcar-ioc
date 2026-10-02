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

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

/** Two constructor-injected beans that break their cycle with a Provider. */
@Stateless
public class Epsilon {

    private final Provider<Zeta> zeta;

    /**
     * Constructor injection of a lazy reference, which is what makes the cycle with {@link Zeta} satisfiable.
     *
     * @param zeta provider of the bean that in turn constructor-injects this one
     */
    @Inject
    public Epsilon(Provider<Zeta> zeta) {
        this.zeta = zeta;
    }

    /**
     * Returns the {@link Zeta} obtained from the provider.
     *
     * @return the {@link Zeta} obtained from the provider
     */
    public Zeta zeta() {
        return zeta.get();
    }
}
