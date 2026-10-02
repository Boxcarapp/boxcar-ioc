package com.boxcar.ioc;

/*-
 * #%L
 * Boxcar IoC :: Processor
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

import com.boxcar.Injector;
import com.boxcar.ioc.tck.InjectorApi;
import com.boxcar.ioc.tck.InjectorTck;

/** Runs the shared TCK against the {@link Injector} generated for the TCK's fixture beans. */
class GeneratedInjectorTckTest extends InjectorTck {

    @Override
    protected InjectorApi injector() {
        return new Adapter(new Injector());
    }

    @Override
    protected Class<? extends RuntimeException> injectionExceptionType() {
        return Injector.InjectionException.class;
    }

    private record Adapter(Injector injector) implements InjectorApi {

        @Override
        public <T> T getInstance(Class<T> type) {
            return injector.getInstance(type);
        }

        @Override
        public <T> InjectorApi bind(Class<T> type, T instance) {
            injector.bind(type, instance);
            return this;
        }

        @Override
        public <T> InjectorApi bind(Class<T> type, Class<? extends T> implementation) {
            injector.bind(type, implementation);
            return this;
        }
    }
}
