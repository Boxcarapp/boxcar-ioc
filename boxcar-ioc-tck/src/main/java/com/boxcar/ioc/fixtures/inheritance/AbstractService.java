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
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic, abstract base bean (annotated, but never instantiated itself). Its type variable is
 * substituted per subclass, so {@code repository} resolves to a different bean in each one.
 *
 * @param <T> the entity type, which selects the {@link Repository} bean
 */
@Singleton
public abstract class AbstractService<T> {

    /** Creates the base part of a service bean; subclasses are the actual beans. */
    protected AbstractService() {
    }

    @Inject
    private Repository<T> repository;

    @Inject
    private Clock clock;

    private Notifier notifierFromBase;
    private Notifier notifierFromOverridden;
    /** Names of the lifecycle callbacks that ran, in order. */
    protected final List<String> callbacks = new ArrayList<>();

    /**
     * Method injection in the base class; {@link UserService} overrides it with the annotation repeated.
     *
     * @param notifier the shared notifier bean
     */
    @Inject
    protected void setNotifier(Notifier notifier) {
        this.notifierFromBase = notifier;
    }

    /**
     * Overridden in {@link ProductService} without {@code @Inject}: must not be injected there.
     *
     * @param notifier the shared notifier bean
     */
    @Inject
    protected void setOverriddenNotifier(Notifier notifier) {
        this.notifierFromOverridden = notifier;
    }

    /** Base-class callback; runs before a subclass callback and not at all if overridden without the annotation. */
    @PostConstruct
    protected void baseInit() {
        callbacks.add("base");
    }

    /**
     * Returns the repository resolved for this subclass's type argument.
     *
     * @return the repository resolved for this subclass's type argument
     */
    public Repository<T> repository() {
        return repository;
    }

    /**
     * Returns the clock injected into the private base-class field.
     *
     * @return the clock injected into the private base-class field
     */
    public Clock clock() {
        return clock;
    }

    /**
     * Returns what {@link #setNotifier} received.
     *
     * @return what {@link #setNotifier} received
     */
    public Notifier notifierFromBase() {
        return notifierFromBase;
    }

    /**
     * Returns what {@link #setOverriddenNotifier} received, if it was called at all.
     *
     * @return what {@link #setOverriddenNotifier} received, if it was called at all
     */
    public Notifier notifierFromOverridden() {
        return notifierFromOverridden;
    }

    /**
     * Returns the names of the callbacks that ran, in order.
     *
     * @return the names of the callbacks that ran, in order
     */
    public List<String> callbacks() {
        return List.copyOf(callbacks);
    }
}
