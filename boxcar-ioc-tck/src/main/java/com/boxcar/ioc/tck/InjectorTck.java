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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.boxcar.ioc.fixtures.access.HiddenConstructor;
import com.boxcar.ioc.fixtures.access.PackageConstructor;
import com.boxcar.ioc.fixtures.cycles.Alpha;
import com.boxcar.ioc.fixtures.cycles.Beta;
import com.boxcar.ioc.fixtures.cycles.CallbackOrder;
import com.boxcar.ioc.fixtures.cycles.Delta;
import com.boxcar.ioc.fixtures.cycles.Epsilon;
import com.boxcar.ioc.fixtures.cycles.Gamma;
import com.boxcar.ioc.fixtures.cycles.Selfish;
import com.boxcar.ioc.fixtures.cycles.Zeta;
import com.boxcar.ioc.fixtures.inheritance.Clock;
import com.boxcar.ioc.fixtures.inheritance.ProductRepository;
import com.boxcar.ioc.fixtures.inheritance.ProductService;
import com.boxcar.ioc.fixtures.inheritance.Repository;
import com.boxcar.ioc.fixtures.inheritance.UserRepository;
import com.boxcar.ioc.fixtures.inheritance.UserService;
import com.boxcar.ioc.fixtures.lifecycle.Dependent;
import com.boxcar.ioc.fixtures.lifecycle.Faulty;
import com.boxcar.ioc.fixtures.resolution.BaseHandler;
import com.boxcar.ioc.fixtures.resolution.EnglishGreeter;
import com.boxcar.ioc.fixtures.resolution.FrenchGreeter;
import com.boxcar.ioc.fixtures.resolution.Greeter;
import com.boxcar.ioc.fixtures.resolution.GreetingService;
import com.boxcar.ioc.fixtures.resolution.HandlerClient;
import com.boxcar.ioc.fixtures.resolution.LegacyBean;
import com.boxcar.ioc.fixtures.resolution.Outer;
import com.boxcar.ioc.fixtures.resolution.PlainGreeter;
import com.boxcar.ioc.fixtures.resolution.SpecialHandler;
import com.boxcar.ioc.fixtures.shop.AuditLog;
import com.boxcar.ioc.fixtures.shop.Config;
import com.boxcar.ioc.fixtures.shop.InMemoryOrderRepository;
import com.boxcar.ioc.fixtures.shop.OrderRepository;
import com.boxcar.ioc.fixtures.shop.OrderService;
import com.boxcar.ioc.fixtures.shop.PaymentGateway;
import com.boxcar.ioc.fixtures.shop.PricingService;
import com.boxcar.ioc.fixtures.shop.TaxCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@code com.boxcar.Injector} implementation must have. Subclass it in an
 * implementation module and provide the two factory methods.
 *
 * <p>The tests bind interfaces to implementation classes explicitly wherever a bean depends on an
 * interface: the reflection implementation cannot discover implementations without scanning, and
 * the generated implementation accepts the binding as a (redundant) override. Bindings may be made
 * again until the type is first resolved; the later binding replaces the earlier one.
 */
public abstract class InjectorTck {

    /** Creates the suite; JUnit instantiates the concrete subclass. */
    protected InjectorTck() {
    }

    /**
     * Creates a fresh, empty injector.
     *
     * @return the implementation's {@code Injector} behind an {@link InjectorApi} adapter
     */
    protected abstract InjectorApi injector();

    /**
     * Names the implementation's exception type for resolutions that cannot be satisfied.
     *
     * @return the class of the exception {@code getInstance} throws for an unsatisfiable dependency
     */
    protected abstract Class<? extends RuntimeException> injectionExceptionType();

    private InjectorApi shopInjector() {
        return injector().bind(OrderRepository.class, InMemoryOrderRepository.class);
    }

    @Nested
    class Creation {

        @Test
        void createsBeanWithTransitiveDependencies() {
            PricingService pricing = injector().getInstance(PricingService.class);

            assertNotNull(pricing.taxCalculator, "public field injected");
            assertNotNull(pricing.taxCalculator.config(), "constructor injection");
            assertEquals(0, new BigDecimal("12.00").compareTo(pricing.gross(new BigDecimal("10.00"))));
        }

        @Test
        void sharesOneInstancePerInjectorRegardlessOfScopeAnnotation() {
            InjectorApi injector = injector();

            TaxCalculator calculator = injector.getInstance(TaxCalculator.class);
            PricingService pricing = injector.getInstance(PricingService.class);
            Config config = injector.getInstance(Config.class);

            assertSame(calculator, pricing.taxCalculator);
            assertSame(config, calculator.config());
            assertSame(calculator, injector.getInstance(TaxCalculator.class));
            assertSame(injector.getInstance(AuditLog.class), injector.getInstance(AuditLog.class), "@Stateful too");
        }

        @Test
        void separateInjectorsHaveSeparateInstances() {
            TaxCalculator first = injector().getInstance(TaxCalculator.class);
            TaxCalculator second = injector().getInstance(TaxCalculator.class);

            assertNotSame(first, second);
            assertNotSame(first.config(), second.config());
        }

        @Test
        void injectsPrivateFieldsEjbReferencesPackagePrivateMethodsAndProviders() {
            InjectorApi injector = shopInjector().bind(PaymentGateway.class, amount -> true);

            OrderService orders = injector.getInstance(OrderService.class);

            assertSame(injector.getInstance(InMemoryOrderRepository.class), orders.repository(),
                    "private interface field");
            assertSame(injector.getInstance(PricingService.class), orders.pricing(), "@EJB field");
            assertTrue(orders.placeOrder("book", new BigDecimal("10.00")));
            assertEquals(List.of("ORD-book"), orders.repository().findAll(), "package-private @Inject method ran");
            assertEquals(List.of("placed book"), injector.getInstance(AuditLog.class).entries());
            assertSame(orders.auditLogProvider().get(), orders.auditLogProvider().get(),
                    "provider returns the shared instance");
        }

        @Test
        void providerIsLazy() {
            int created = AuditLog.instancesCreated();
            InjectorApi injector = shopInjector().bind(PaymentGateway.class, amount -> true);

            OrderService orders = injector.getInstance(OrderService.class);
            assertEquals(created, AuditLog.instancesCreated(), "Provider target not created until get()");

            orders.auditLogProvider().get();
            assertEquals(created + 1, AuditLog.instancesCreated());
        }

        @Test
        void instantiatesThroughNonPublicConstructors() {
            InjectorApi injector = injector();

            PackageConstructor bean = injector.getInstance(PackageConstructor.class);

            assertTrue(bean.dependency().constructed(), "private no-arg constructor");
            assertSame(injector.getInstance(HiddenConstructor.class), bean.dependency(),
                    "package-private @Inject constructor");
        }

        @Test
        void supportsJavaxAnnotations() {
            InjectorApi injector = injector();

            assertSame(injector.getInstance(BaseHandler.class), injector.getInstance(LegacyBean.class).handler());
        }

        @Test
        void supportsNestedStaticBeans() {
            InjectorApi injector = injector();

            Outer.Middle.DeeplyNested deeplyNested = injector.getInstance(Outer.Middle.DeeplyNested.class);

            assertSame(injector.getInstance(Outer.Nested.class), deeplyNested.nested);
            assertSame(injector.getInstance(BaseHandler.class), deeplyNested.nested.handler());
        }
    }

    @Nested
    class Inheritance {

        @Test
        void injectsPrivateSuperclassMembers() {
            InjectorApi injector = injector().bind(Repository.class, UserRepository.class);

            UserService users = injector.getInstance(UserService.class);

            assertInstanceOf(UserRepository.class, users.repository(), "private field of generic superclass");
            assertSame(injector.getInstance(Clock.class), users.clock(), "private superclass field");
            assertSame(users.clock(), users.ownClock(), "own private field");
        }

        @Test
        void injectsOverridingMethodOnce() {
            UserService users = injector().bind(Repository.class, UserRepository.class).getInstance(UserService.class);

            assertEquals(1, users.notifierInjections());
            assertNotNull(users.notifierFromBase());
        }

        @Test
        void skipsInjectMethodOverriddenWithoutAnnotation() {
            ProductService products = injector().bind(Repository.class, ProductRepository.class)
                    .getInstance(ProductService.class);

            assertFalse(products.overriddenSetterCalled());
            assertNull(products.notifierFromOverridden());
            assertNotNull(products.notifierFromBase(), "non-overridden base setter still injected");
        }

        @Test
        void runsPostConstructSuperclassFirstAndHonoursOverriding() {
            UserService users = injector().bind(Repository.class, UserRepository.class).getInstance(UserService.class);
            ProductService products = injector().bind(Repository.class, ProductRepository.class)
                    .getInstance(ProductService.class);

            assertEquals(List.of("base", "user"), users.callbacks());
            assertEquals(List.of(), products.callbacks(),
                    "baseInit overridden without @PostConstruct is not a callback");
        }

        @Test
        void concreteBeanWinsOverItsOwnSubclassBean() {
            InjectorApi injector = injector();

            HandlerClient client = injector.getInstance(HandlerClient.class);

            assertSame(BaseHandler.class, client.handler().getClass());
            assertSame(client.handler(), injector.getInstance(BaseHandler.class));
            assertInstanceOf(SpecialHandler.class, injector.getInstance(SpecialHandler.class));
        }
    }

    @Nested
    class Cycles {

        @BeforeEach
        public void beforeEach() {
            CallbackOrder.reset();
        }

        @Test
        void fieldCycleIsClosedAfterConstruction() {
            InjectorApi injector = injector();

            Alpha alpha = injector.getInstance(Alpha.class);
            Beta beta = injector.getInstance(Beta.class);

            assertSame(beta, alpha.beta());
            assertSame(alpha, beta.alpha());
        }

        @Test
        void postConstructRunsAfterWholeCycleIsWired() {
            Alpha alpha = injector().getInstance(Alpha.class);

            assertTrue(alpha.betaWiredAtInit());
            assertTrue(alpha.betaHadAlphaAtInit());
            assertTrue(alpha.beta().alphaHadBetaAtInit());
            assertEquals(List.of("beta", "alpha"), CallbackOrder.reset(), "dependencies first");
        }

        @Test
        void mixedConstructorAndFieldCycleIsSatisfiedFromEitherSide() {
            InjectorApi fromGamma = injector();
            Gamma gamma = fromGamma.getInstance(Gamma.class);
            assertSame(gamma, gamma.delta().gamma());
            assertSame(gamma.delta(), fromGamma.getInstance(Delta.class));

            InjectorApi fromDelta = injector();
            Delta delta = fromDelta.getInstance(Delta.class);
            assertSame(delta, delta.gamma().delta());
            assertSame(delta.gamma(), fromDelta.getInstance(Gamma.class));
        }

        @Test
        void providerBreaksConstructorCycle() {
            InjectorApi injector = injector();

            Epsilon epsilon = injector.getInstance(Epsilon.class);
            Zeta zeta = injector.getInstance(Zeta.class);

            assertSame(zeta, epsilon.zeta());
            assertSame(epsilon, zeta.epsilon());
        }

        @Test
        void beanCanInjectItself() {
            Selfish selfish = injector().getInstance(Selfish.class);

            assertSame(selfish, selfish.self());
        }
    }

    @Nested
    class Bindings {

        @Test
        void boundImplementationClassIsSharedWithDirectLookup() {
            InjectorApi injector = shopInjector();

            OrderRepository repository = injector.getInstance(OrderRepository.class);

            assertInstanceOf(InMemoryOrderRepository.class, repository);
            assertSame(repository, injector.getInstance(InMemoryOrderRepository.class));
        }

        @Test
        void boundImplementationClassSelectsAmongImplementations() {
            InjectorApi injector = injector().bind(Greeter.class, FrenchGreeter.class);

            assertEquals("Bonjour Ada", injector.getInstance(GreetingService.class).greet("Ada"));
            assertSame(injector.getInstance(FrenchGreeter.class), injector.getInstance(Greeter.class));
            assertInstanceOf(EnglishGreeter.class, injector.getInstance(EnglishGreeter.class),
                    "the other bean is still available");
        }

        @Test
        void boundInstanceOverridesBeanForInterfaceType() {
            List<String> saved = new ArrayList<>();
            OrderRepository fake = new OrderRepository() {
                @Override
                public void save(String order) {
                    saved.add(order);
                }

                @Override
                public List<String> findAll() {
                    return saved;
                }
            };
            InjectorApi injector = injector()
                    .bind(OrderRepository.class, fake)
                    .bind(PaymentGateway.class, amount -> true);

            OrderService orders = injector.getInstance(OrderService.class);
            orders.placeOrder("pen", BigDecimal.ONE);

            assertSame(fake, orders.repository());
            assertSame(fake, injector.getInstance(OrderRepository.class));
            assertEquals(List.of("ORD-pen"), saved);
            assertNotSame(fake, injector.getInstance(InMemoryOrderRepository.class),
                    "the implementation class itself is still available as a separate bean");
        }

        @Test
        void boundInstanceOverridesBeanForImplementationType() {
            TaxCalculator custom = new TaxCalculator(new Config() {
                @Override
                public BigDecimal taxRate() {
                    return BigDecimal.ZERO;
                }
            });
            InjectorApi injector = injector().bind(TaxCalculator.class, custom);

            PricingService pricing = injector.getInstance(PricingService.class);

            assertSame(custom, pricing.taxCalculator);
            assertEquals(0, new BigDecimal("10.00").compareTo(pricing.gross(new BigDecimal("10.00"))));
        }

        @Test
        void boundInstanceOfNonBeanTypeIsRetrievable() {
            InjectorApi injector = injector();
            PaymentGateway gateway = amount -> true;

            injector.bind(PaymentGateway.class, gateway);

            assertSame(gateway, injector.getInstance(PaymentGateway.class));
        }

        @Test
        void implementationClassMustBeBean() {
            InjectorApi injector = injector();

            assertThrows(IllegalArgumentException.class, () -> injector.bind(Greeter.class, PlainGreeter.class));
            assertThrows(IllegalArgumentException.class, () -> injector.bind(Greeter.class, Greeter.class));
        }

        @Test
        void bindingAfterCreationIsRejectedOnlyForCreatedTypes() {
            InjectorApi injector = injector();
            injector.getInstance(Config.class);

            assertThrows(IllegalStateException.class, () -> injector.bind(Config.class, new Config()));

            TaxCalculator custom = new TaxCalculator(new Config());
            injector.bind(TaxCalculator.class, custom);
            assertSame(custom, injector.getInstance(PricingService.class).taxCalculator);
        }

        @Test
        void bindingTypeAgainReplacesItsBindingUntilResolved() {
            InjectorApi injector = shopInjector();
            OrderRepository fakeRepository = new InMemoryOrderRepository();
            PaymentGateway accepting = amount -> true;
            PaymentGateway declining = amount -> false;

            injector.bind(OrderRepository.class, fakeRepository);          // instance replaces implementation class
            injector.bind(PaymentGateway.class, accepting);
            injector.bind(PaymentGateway.class, declining);                // instance replaces instance
            injector.bind(Greeter.class, new PlainGreeter());
            injector.bind(Greeter.class, FrenchGreeter.class);             // implementation class replaces instance
            injector.bind(Greeter.class, EnglishGreeter.class);            // implementation class replaces the same

            OrderService orders = injector.getInstance(OrderService.class);
            assertSame(fakeRepository, orders.repository());
            assertSame(declining, orders.paymentGateway());
            assertSame(fakeRepository, injector.getInstance(OrderRepository.class));
            assertEquals("Hello Ada", injector.getInstance(GreetingService.class).greet("Ada"));
        }

        @Test
        void bindingResolvedTypeIsRejected() {
            InjectorApi injector = shopInjector().bind(PaymentGateway.class, amount -> true);
            final OrderService orders = injector.getInstance(OrderService.class);

            assertThrows(IllegalStateException.class, () -> injector.bind(PaymentGateway.class, amount -> false),
                    "the bound instance has been injected");
            assertThrows(IllegalStateException.class,
                    () -> injector.bind(OrderRepository.class, new InMemoryOrderRepository()),
                    "the bound implementation class has been instantiated and injected");
            assertThrows(IllegalStateException.class,
                    () -> injector.bind(OrderRepository.class, InMemoryOrderRepository.class));

            // Only reached through a Provider so far, hence not resolved yet.
            AuditLog auditLog = new AuditLog();
            injector.bind(AuditLog.class, auditLog);
            assertSame(auditLog, orders.auditLogProvider().get());

            PaymentGateway gateway = amount -> true;
            InjectorApi other = injector().bind(PaymentGateway.class, gateway);
            assertSame(gateway, other.getInstance(PaymentGateway.class));
            assertThrows(IllegalStateException.class, () -> other.bind(PaymentGateway.class, amount -> false),
                    "the bound instance has been returned by getInstance");
        }

        @Test
        void bindingsConsumedByFailedResolutionCanBeReplaced() {
            InjectorApi injector = shopInjector();

            // OrderService receives the repository first and then fails on the unbound PaymentGateway.
            assertThrows(injectionExceptionType(), () -> injector.getInstance(OrderService.class));

            // The half-wired OrderService was discarded, so nothing holds the repository it was given.
            OrderRepository fakeRepository = new InMemoryOrderRepository();
            injector.bind(OrderRepository.class, fakeRepository);
            injector.bind(PaymentGateway.class, amount -> true);
            assertSame(fakeRepository, injector.getInstance(OrderService.class).repository());
        }
    }

    @Nested
    class Failures {

        @Test
        void unresolvedDependencyFailsWithHelpfulMessageAndRollsBack() {
            InjectorApi injector = shopInjector();

            RuntimeException failure = assertThrows(injectionExceptionType(),
                    () -> injector.getInstance(OrderService.class));

            assertTrue(failure.getMessage().contains("OrderService.paymentGateway"), failure.getMessage());
            assertTrue(failure.getMessage().contains("Injector.bind(PaymentGateway.class"), failure.getMessage());

            // Nothing half-wired survived, so binding the missing piece and retrying works.
            injector.bind(PaymentGateway.class, amount -> false);
            OrderService orders = injector.getInstance(OrderService.class);
            assertFalse(orders.placeOrder("gadget", BigDecimal.TEN));
            assertEquals(List.of("declined gadget"), injector.getInstance(AuditLog.class).entries());
        }

        @Test
        void unboundInterfaceInjectionPointIsReported() {
            RuntimeException failure = assertThrows(injectionExceptionType(),
                    () -> injector().getInstance(GreetingService.class));

            assertTrue(failure.getMessage().contains("GreetingService.greeter"), failure.getMessage());
            assertTrue(failure.getMessage().contains("bind(Greeter.class"), failure.getMessage());
        }

        @Test
        void unknownTypesAreRejected() {
            InjectorApi injector = injector();

            RuntimeException unboundInterface = assertThrows(injectionExceptionType(),
                    () -> injector.getInstance(PaymentGateway.class));
            assertTrue(unboundInterface.getMessage().contains("PaymentGateway"), unboundInterface.getMessage());

            RuntimeException notBean = assertThrows(injectionExceptionType(),
                    () -> injector.getInstance(PlainGreeter.class));
            assertTrue(notBean.getMessage().contains("PlainGreeter"), notBean.getMessage());

            RuntimeException ambiguousOrUnbound = assertThrows(injectionExceptionType(),
                    () -> injector.getInstance(Repository.class));
            assertTrue(ambiguousOrUnbound.getMessage().contains("Repository"), ambiguousOrUnbound.getMessage());
        }

        @Test
        void exceptionsFromBeanCodePropagateUnwrappedAndRollBack() {
            InjectorApi injector = injector();

            IllegalStateException boom = assertThrows(IllegalStateException.class,
                    () -> injector.getInstance(Dependent.class));
            assertEquals("boom", boom.getMessage());

            // The failed resolution left nothing behind, so a replacement can still be bound.
            Faulty replacement = new Faulty();
            injector.bind(Faulty.class, replacement);
            assertSame(replacement, injector.getInstance(Dependent.class).faulty());
        }
    }
}
