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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.boxcar.Injector;
import com.boxcar.ioc.fixtures.access.HiddenConstructor;
import com.boxcar.ioc.fixtures.access.InternalImpl;
import com.boxcar.ioc.fixtures.access.Visible;
import com.boxcar.ioc.fixtures.inheritance.AbstractService;
import com.boxcar.ioc.fixtures.inheritance.ProductRepository;
import com.boxcar.ioc.fixtures.inheritance.ProductService;
import com.boxcar.ioc.fixtures.inheritance.Repository;
import com.boxcar.ioc.fixtures.inheritance.UserRepository;
import com.boxcar.ioc.fixtures.inheritance.UserService;
import com.boxcar.ioc.fixtures.resolution.FrenchGreeter;
import com.boxcar.ioc.fixtures.resolution.Greeter;
import com.boxcar.ioc.fixtures.resolution.GreetingService;
import com.boxcar.ioc.fixtures.shop.InMemoryOrderRepository;
import com.boxcar.ioc.fixtures.shop.OrderRepository;
import com.boxcar.ioc.fixtures.shop.OrderService;
import com.boxcar.ioc.fixtures.shop.PaymentGateway;
import org.junit.jupiter.api.Test;

/**
 * Behaviour only the generated injector has, because it knows every bean at compile time: it resolves
 * interfaces to their single implementation, matches generic types, reports ambiguity candidates and
 * copes with types it cannot even name.
 */
class GeneratedInjectorTest {

    @Test
    void resolvesInterfaceToItsOnlyImplementationWithoutBinding() {
        Injector injector = new Injector().bind(PaymentGateway.class, amount -> true);

        OrderRepository repository = injector.getInstance(OrderRepository.class);

        assertInstanceOf(InMemoryOrderRepository.class, repository);
        assertSame(repository, injector.getInstance(InMemoryOrderRepository.class));
        assertSame(repository, injector.getInstance(OrderService.class).repository());
    }

    @Test
    void resolvesGenericSupertypesPerSubstitutedTypeArgument() {
        Injector injector = new Injector();

        UserService users = injector.getInstance(UserService.class);
        ProductService products = injector.getInstance(ProductService.class);

        assertInstanceOf(UserRepository.class, users.repository(), "Repository<User> in the same injector as");
        assertInstanceOf(ProductRepository.class, products.repository(), "Repository<Product>");
    }

    @Test
    void reportsAmbiguityCandidates() {
        Injector injector = new Injector();

        Injector.InjectionException injectionPoint = assertThrows(Injector.InjectionException.class,
                () -> injector.getInstance(GreetingService.class));
        assertTrue(injectionPoint.getMessage().contains("EnglishGreeter"), injectionPoint.getMessage());
        assertTrue(injectionPoint.getMessage().contains("FrenchGreeter"), injectionPoint.getMessage());

        Injector.InjectionException lookup = assertThrows(Injector.InjectionException.class,
                () -> injector.getInstance(Repository.class));
        assertTrue(lookup.getMessage().contains("ProductRepository"), lookup.getMessage());
        assertTrue(lookup.getMessage().contains("UserRepository"), lookup.getMessage());
        assertThrows(Injector.InjectionException.class, () -> injector.getInstance(AbstractService.class));

        Injector bound = new Injector();
        bound.bind(Greeter.class, bound.getInstance(FrenchGreeter.class));
        assertEquals("Bonjour Ada", bound.getInstance(GreetingService.class).greet("Ada"));
    }

    @Test
    void handlesPackagePrivateTypesAndSuperclasses() {
        Injector injector = new Injector();

        Visible visible = injector.getInstance(Visible.class);

        assertEquals("s3cr3t", visible.secret(), "package-private interface resolved to its public bean");
        assertEquals("s3cr3t", visible.secretFromProvider());
        assertTrue(visible.setterReceivedSameInstance());
        assertSame(injector.getInstance(HiddenConstructor.class), visible.fromHiddenBase(),
                "private field of package-private superclass");
        assertTrue(visible.hiddenBaseInitialised(), "package-private @PostConstruct in superclass");
        assertNotNull(injector.getInstance(InternalImpl.class));
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
}
