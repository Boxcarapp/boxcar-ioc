package com.boxcar.ioc.fixtures.access;

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

/** Public bean whose superclass, injected interface type and provider argument are all package-private. */
@Stateless
public class Visible extends HiddenBase {

    /** Creates the bean; the injector supplies its dependencies afterwards. */
    public Visible() {
    }

    @Inject
    private Internal internal;

    @Inject
    Provider<Internal> internalProvider;

    private Internal fromSetter;

    /**
     * Setter injection of a package-private type.
     *
     * @param internal the bean implementing the package-private interface
     */
    @Inject
    protected void setInternal(Internal internal) {
        this.fromSetter = internal;
    }

    /**
     * Returns the secret of the field-injected {@code Internal}.
     *
     * @return the secret of the field-injected {@code Internal}
     */
    public String secret() {
        return internal.secret();
    }

    /**
     * Returns the secret of the {@code Internal} obtained through the injected provider.
     *
     * @return the secret of the {@code Internal} obtained through the injected provider
     */
    public String secretFromProvider() {
        return internalProvider.get().secret();
    }

    /**
     * Returns whether the setter received the same instance as the field.
     *
     * @return whether the setter received the same instance as the field
     */
    public boolean setterReceivedSameInstance() {
        return fromSetter == internal;
    }

    /**
     * Returns whether the package-private superclass callback ran.
     *
     * @return whether the package-private superclass callback ran
     */
    public boolean hiddenBaseInitialised() {
        return hiddenBaseInitialised;
    }
}
