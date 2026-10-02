package com.boxcar.ioc.tck;

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

/**
 * The public API every {@code com.boxcar.Injector} implementation provides. The TCK is written
 * against this interface; each implementation module adapts its {@code Injector} to it (the
 * implementations cannot share a compile-time type because both are named
 * {@code com.boxcar.Injector} and are meant to be interchangeable on the class path).
 */
public interface InjectorApi {

    /**
     * Returns the bean of the given type, creating it and everything it depends on if necessary.
     *
     * @param <T> the requested type
     * @param type a bean class, or a type passed to one of the {@code bind} methods
     * @return the instance for {@code type}
     */
    <T> T getInstance(Class<T> type);

    /**
     * Registers an existing object to be returned and injected wherever {@code type} is requested.
     *
     * @param <T> the bound type
     * @param type the type to bind
     * @param instance the object to use for it
     * @return this injector, for chaining
     */
    <T> InjectorApi bind(Class<T> type, T instance);

    /**
     * Registers the bean class to instantiate wherever {@code type} is requested.
     *
     * @param <T> the bound type
     * @param type the type to bind
     * @param implementation the bean class to instantiate for it
     * @return this injector, for chaining
     */
    <T> InjectorApi bind(Class<T> type, Class<? extends T> implementation);
}
