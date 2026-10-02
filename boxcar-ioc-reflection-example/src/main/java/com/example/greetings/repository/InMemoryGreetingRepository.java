package com.example.greetings.repository;

/*-
 * #%L
 * Boxcar IoC :: Reflection Example
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
import java.util.Map;
import java.util.Optional;

/** The only implementation of {@link GreetingRepository}; a JPA one would take its place in production. */
@Stateless
public class InMemoryGreetingRepository implements GreetingRepository {

    private static final Map<String, String> TEMPLATES = Map.of(
            "en", "Hello, %s!",
            "fr", "Bonjour, %s !");

    /** Creates the repository; it needs nothing injected. */
    public InMemoryGreetingRepository() {
    }

    @Override
    public Optional<String> findTemplate(String language) {
        return Optional.ofNullable(TEMPLATES.get(language));
    }
}
