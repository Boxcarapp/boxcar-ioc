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

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.math.BigDecimal;

/** Public field injection (direct assignment) and a private {@code @PostConstruct} callback. */
@Singleton
public class PricingService {

    /** Creates the bean; the injector supplies its dependencies afterwards. */
    public PricingService() {
    }

    /** Public field injection, which the generated injector assigns directly. */
    @Inject
    public TaxCalculator taxCalculator;

    private boolean initialised;
    private boolean taxCalculatorPresentAtInit;

    @PostConstruct
    private void init() {
        initialised = true;
        taxCalculatorPresentAtInit = taxCalculator != null;
    }

    /**
     * Returns whether the private callback ran.
     *
     * @return whether the private callback ran
     */
    public boolean isInitialised() {
        return initialised;
    }

    /**
     * Returns whether the field was already injected when the callback ran.
     *
     * @return whether the field was already injected when the callback ran
     */
    public boolean wasTaxCalculatorPresentAtInit() {
        return taxCalculatorPresentAtInit;
    }

    /**
     * Adds tax to a net price.
     *
     * @param net the net price
     * @return the gross price
     */
    public BigDecimal gross(BigDecimal net) {
        return net.add(taxCalculator.tax(net));
    }
}
