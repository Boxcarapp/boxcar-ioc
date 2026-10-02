package com.example.greetings.service;

/*-
 * #%L
 * Boxcar IoC :: Processor Example
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

import com.example.greetings.repository.GreetingRepository;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.LocalTime;

/**
 * A typical session bean: an injected repository interface, an {@code @EJB} reference to another bean,
 * a container-provided dependency ({@link Clock}, which a test has to bind) and a {@code @PostConstruct}.
 */
@Stateless
public class GreetingService {

    @Inject
    private GreetingRepository repository;

    @EJB
    private GreetingLog log;

    @Inject
    private Clock clock;

    private String fallback;

    /** Creates the service; the container (or the test injector) wires its fields afterwards. */
    public GreetingService() {
    }

    /** Sets up the fallback template once all fields are injected. */
    @PostConstruct
    public void init() {
        fallback = "Hi, %s!";
    }

    /**
     * Greets someone in the requested language and logs the greeting.
     *
     * @param name who to greet
     * @param language the language code to look up a template for
     * @return the greeting, built from the fallback template if the language is unknown
     */
    public String greet(String name, String language) {
        String template = repository.findTemplate(language).orElse(fallback);
        String greeting = template.formatted(name);
        log.record(LocalTime.now(clock) + " " + greeting);
        return greeting;
    }
}
