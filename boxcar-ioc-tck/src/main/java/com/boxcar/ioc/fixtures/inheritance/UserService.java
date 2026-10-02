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

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/** Inherits everything from the base and adds its own field and callback. */
@Stateless
public class UserService extends AbstractService<User> {

    /** Creates the bean; the injector supplies its dependencies afterwards. */
    public UserService() {
    }

    @Inject
    private Clock ownClock;

    private int notifierInjections;

    /** Overrides an {@code @Inject} method and is itself annotated: injected exactly once. */
    @Inject
    @Override
    protected void setNotifier(Notifier notifier) {
        notifierInjections++;
        super.setNotifier(notifier);
    }

    @PostConstruct
    void ownInit() {
        callbacks.add("user");
    }

    /**
     * Returns the clock injected into this class's own field.
     *
     * @return the clock injected into this class's own field
     */
    public Clock ownClock() {
        return ownClock;
    }

    /**
     * Returns how many times {@link #setNotifier} was called; must be exactly once.
     *
     * @return how many times {@link #setNotifier} was called; must be exactly once
     */
    public int notifierInjections() {
        return notifierInjections;
    }
}
