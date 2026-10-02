package com.boxcar;

/*-
 * #%L
 * Boxcar IoC :: Reflection
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.boxcar.Injector.InjectionException;
import com.boxcar.ioc.fixtures.shop.Config;
import com.boxcar.ioc.fixtures.shop.InMemoryOrderRepository;
import com.boxcar.ioc.fixtures.shop.OrderRepository;
import com.boxcar.ioc.fixtures.shop.OrderService;
import com.boxcar.ioc.fixtures.shop.PaymentGateway;
import com.boxcar.ioc.fixtures.shop.PricingService;
import com.boxcar.ioc.fixtures.shop.TaxCalculator;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Behaviour specific to the reflection implementation: laziness, runtime validation and leniencies. */
class ReflectionInjectorTest {

    @Test
    void introspectsOnlyTheClassesItNeeds() {
        Injector injector = new Injector();

        injector.getInstance(PricingService.class);

        Set<Class<?>> introspected = Introspection.introspectedClasses();
        assertTrue(introspected.containsAll(Set.of(PricingService.class, TaxCalculator.class, Config.class)),
                introspected.toString());
        assertFalse(introspected.contains(NeverRequested.class), "an unrelated bean class is never looked at");
        assertFalse(introspected.contains(NeverRequested.Collaborator.class));

        injector.getInstance(NeverRequested.class);
        assertTrue(Introspection.introspectedClasses()
                .containsAll(Set.of(NeverRequested.class, NeverRequested.Collaborator.class)));
    }

    @Test
    void bindingAnInstanceDoesNotIntrospectItsClass() {
        new Injector().bind(NeverIntrospected.class, new NeverIntrospected());

        assertFalse(Introspection.introspectedClasses().contains(NeverIntrospected.class));
    }

    @Test
    void constructorOnlyCycleIsReportedWithItsPath() {
        InjectionException failure = assertThrows(InjectionException.class,
                () -> new Injector().getInstance(Ping.class));

        assertTrue(failure.getMessage()
                .startsWith("Dependency cycle through constructor injection cannot be satisfied: "),
                failure.getMessage());
        assertTrue(failure.getMessage().contains(Ping.class.getCanonicalName() + " -> " + Pong.class.getCanonicalName()
                + " -> " + Ping.class.getCanonicalName()), failure.getMessage());
    }

    @Test
    void packagePrivateBeanClassesAreAllowed() {
        Hidden hidden = new Injector().getInstance(Hidden.class);

        assertNotNull(hidden.config);
    }

    @Test
    void unresolvedTypeVariableIsReportedWithItsErasure() {
        Injector injector = new Injector();

        InjectionException failure = assertThrows(InjectionException.class, () -> injector.getInstance(Box.class));
        assertTrue(failure.getMessage().contains("Box.contents"), failure.getMessage());
        assertTrue(failure.getMessage().contains("type T cannot be satisfied by a bean"), failure.getMessage());
        assertTrue(failure.getMessage().contains("bind(Object.class"), failure.getMessage());

        injector.bind(Object.class, "contents");
        assertEquals("contents", injector.getInstance(Box.class).contents);
    }

    @Test
    void providerOfUnboundTypeFailsOnlyWhenUsed() {
        Injector injector = new Injector();

        Lazy lazy = injector.getInstance(Lazy.class);
        InjectionException failure = assertThrows(InjectionException.class, () -> lazy.runnable.get());
        assertTrue(failure.getMessage().contains("Lazy.runnable"), failure.getMessage());

        Runnable bound = () -> { };
        injector.bind(Runnable.class, bound);
        assertSame(bound, lazy.runnable.get(), "the provider picks up a binding made after its owner was created");
    }

    @Test
    void invalidBeanDefinitionsAreReported() {
        Injector injector = new Injector();

        assertTrue(assertThrows(InjectionException.class, () -> injector.getInstance(StaticInjection.class))
                .getMessage().contains("is static; static injection is not supported"));
        assertTrue(assertThrows(InjectionException.class, () -> injector.getInstance(TwoConstructors.class))
                .getMessage().contains("has 2 @Inject constructors"));
        assertTrue(assertThrows(InjectionException.class, () -> injector.getInstance(NoConstructor.class))
                .getMessage().contains("neither an @Inject annotated constructor nor a no-argument constructor"));
        assertTrue(assertThrows(InjectionException.class, () -> injector.getInstance(Inner.class))
                .getMessage().contains("non-static inner class"));
    }

    @Test
    void subclassCanBindDefaultsForTestsToOverride() {
        class Defaults extends Injector {
            Defaults() {
                bind(OrderRepository.class, InMemoryOrderRepository.class);
                bind(PaymentGateway.class, amount -> true);
            }
        }

        PaymentGateway declining = amount -> false;

        OrderService orders = new Defaults().bind(PaymentGateway.class, declining).getInstance(OrderService.class);

        assertSame(declining, orders.paymentGateway());
        assertInstanceOf(InMemoryOrderRepository.class, orders.repository(),
                "defaults that are not overridden still apply");
    }

    @Singleton
    public static class NeverRequested {

        @Singleton
        public static class Collaborator {
        }

        @Inject
        Collaborator collaborator;
    }

    @Singleton
    public static class NeverIntrospected {
    }

    @Stateless
    public static class Ping {

        @Inject
        public Ping(Pong pong) {
        }
    }

    @Stateless
    public static class Pong {

        @Inject
        public Pong(Ping ping) {
        }
    }

    @Stateless
    static class Hidden {

        @Inject
        private Config config;
    }

    @Singleton
    public static class Box<T> {

        @Inject
        T contents;
    }

    @Singleton
    public static class Lazy {

        @Inject
        Provider<Runnable> runnable;
    }

    @Singleton
    public static class StaticInjection {

        @Inject
        static Config config;
    }

    @Singleton
    public static class TwoConstructors {

        @Inject
        public TwoConstructors(Config config) {
        }

        @Inject
        public TwoConstructors(TaxCalculator calculator) {
        }
    }

    @Singleton
    public static class NoConstructor {

        public NoConstructor(String name) {
        }
    }

    @Singleton
    public class Inner {
    }
}
