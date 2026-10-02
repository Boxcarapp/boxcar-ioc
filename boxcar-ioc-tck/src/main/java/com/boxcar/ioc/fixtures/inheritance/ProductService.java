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

import jakarta.ejb.Stateless;

/** Overrides the inherited injection and lifecycle methods without re-annotating them. */
@Stateless
public class ProductService extends AbstractService<Product> {

    /** Creates the bean; the injector supplies its dependencies afterwards. */
    public ProductService() {
    }

    private boolean overriddenSetterCalled;

    /** Overrides an {@code @Inject} method without the annotation: the injector must skip it. */
    @Override
    protected void setOverriddenNotifier(Notifier notifier) {
        overriddenSetterCalled = true;
    }

    /** Overrides the {@code @PostConstruct} method without the annotation: not a callback any more. */
    @Override
    protected void baseInit() {
        callbacks.add("product-not-a-callback");
    }

    /**
     * Returns whether the un-annotated override was (wrongly) called by the injector.
     *
     * @return whether the un-annotated override was (wrongly) called by the injector
     */
    public boolean overriddenSetterCalled() {
        return overriddenSetterCalled;
    }
}
