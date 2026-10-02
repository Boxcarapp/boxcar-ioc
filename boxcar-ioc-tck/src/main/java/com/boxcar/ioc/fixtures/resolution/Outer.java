package com.boxcar.ioc.fixtures.resolution;

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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Not a bean itself, but hosts nested static beans. */
public class Outer {

    private Outer() {
    }

    /** A bean nested one level down; found through its outer class, which is not a bean. */
    @Singleton
    public static class Nested {

        @Inject
        private BaseHandler handler;

        /** Creates the bean; the injector supplies its dependencies afterwards. */
        public Nested() {
        }

        /**
         * Returns the injected handler.
         *
         * @return the injected handler
         */
        public BaseHandler handler() {
            return handler;
        }
    }

    /** Another non-bean level between {@link Outer} and a bean. */
    public static class Middle {

        private Middle() {
        }

        /** A bean nested two levels down, injecting its sibling {@link Nested}. */
        @Singleton
        public static class DeeplyNested {

            /** Public field injection of another nested bean. */
            @Inject
            public Nested nested;

            /** Creates the bean; the injector supplies its dependencies afterwards. */
            public DeeplyNested() {
            }
        }
    }
}
