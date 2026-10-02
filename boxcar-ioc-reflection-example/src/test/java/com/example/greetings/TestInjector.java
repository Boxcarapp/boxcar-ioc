package com.example.greetings;

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

import com.boxcar.Injector;
import com.example.greetings.repository.GreetingRepository;
import com.example.greetings.repository.InMemoryGreetingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The project's defaults for everything the container would provide in production. Tests create one per
 * test and override whatever the test is about; a later {@code bind} replaces an earlier one until the type
 * is first resolved.
 */
public class TestInjector extends Injector {

    public static final Instant NOON = Instant.parse("2026-01-01T12:00:00Z");

    public TestInjector() {
        this(true);
    }

    /**
     * {@code withClock = false} leaves the container-provided Clock unbound, to show what a forgotten binding looks
     * like.
     */
    public TestInjector(boolean withClock) {
        if (withClock) {
            bind(Clock.class, Clock.fixed(NOON, ZoneOffset.UTC));
        }
        // The reflection injector never scans, so an interface has to be told its implementation.
        bind(GreetingRepository.class, InMemoryGreetingRepository.class);
    }
}
