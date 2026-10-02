package com.example.greetings;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.boxcar.Injector;
import com.example.greetings.repository.GreetingRepository;
import com.example.greetings.repository.InMemoryGreetingRepository;
import com.example.greetings.service.GreetingLog;
import com.example.greetings.service.GreetingService;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Identical in both example modules: the tests do not depend on which Injector implementation is on the class path. */
public class GreetingServiceTest {

    private TestInjector injector;

    @BeforeEach
    public void beforeEach() {
        injector = new TestInjector();
    }

    @AfterEach
    public void afterEach() {
        injector = null;
    }

    @Test
    public void greetsInTheRequestedLanguage() {
        GreetingService service = injector.getInstance(GreetingService.class);

        assertEquals("Bonjour, Ada !", service.greet("Ada", "fr"));
    }

    @Test
    public void fallsBackToTheTemplateSetUpInPostConstruct() {
        GreetingService service = injector.getInstance(GreetingService.class);

        assertEquals("Hi, Ada!", service.greet("Ada", "xx"));
    }

    @Test
    public void logsThroughTheSharedSingletonUsingTheBoundClock() {
        GreetingService service = injector.getInstance(GreetingService.class);
        service.greet("Ada", "en");

        GreetingLog log = injector.getInstance(GreetingLog.class);
        assertEquals(List.of("12:00 Hello, Ada!"), log.entries());
    }

    @Test
    public void interfaceResolvesToTheOnlyImplementation() {
        GreetingRepository repository = injector.getInstance(GreetingRepository.class);

        assertSame(injector.getInstance(InMemoryGreetingRepository.class), repository);
    }

    @Test
    public void testCanOverrideDefaultBinding() {
        Clock later = Clock.fixed(TestInjector.NOON.plusSeconds(90 * 60), ZoneOffset.UTC);
        GreetingService service = injector.bind(Clock.class, later).getInstance(GreetingService.class);

        service.greet("Ada", "en");

        assertEquals(List.of("13:30 Hello, Ada!"), injector.getInstance(GreetingLog.class).entries());
    }

    @Test
    public void testCanReplaceBeanWithFake() {
        GreetingRepository shouting = language -> Optional.of("HELLO %S");
        GreetingService service = injector.bind(GreetingRepository.class, shouting).getInstance(GreetingService.class);

        assertEquals("HELLO ADA", service.greet("Ada", "en"));
    }

    @Test
    public void forgottenBindingIsReportedWithTheBindCallToMake() {
        TestInjector withoutClock = new TestInjector(false);

        Injector.InjectionException failure = assertThrows(Injector.InjectionException.class,
                () -> withoutClock.getInstance(GreetingService.class));

        assertTrue(failure.getMessage().contains("GreetingService.clock"), failure.getMessage());
        assertTrue(failure.getMessage().contains("bind(Clock.class"), failure.getMessage());
    }
}
