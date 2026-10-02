package com.boxcar.ioc.fixtures.shop;

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

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.math.BigDecimal;

/**
 * Private field injection of an interface, an {@code @EJB} reference, a container-provided
 * dependency that has to be bound by the test, and a lazy {@link Provider}.
 */
@Stateless
public class OrderService {

    /** Creates the bean; the injector supplies its dependencies afterwards. */
    public OrderService() {
    }

    @Inject
    private OrderRepository repository;

    @EJB
    private PricingService pricing;

    @Inject
    private PaymentGateway paymentGateway;

    @Inject
    private Provider<AuditLog> auditLog;

    private String orderPrefix;

    @Inject
    void configure(Config config) {
        // Package-private method injection; the parameter is another bean.
        this.orderPrefix = config == null ? "?" : "ORD-";
    }

    /**
     * Charges the gross price and saves the order.
     *
     * @param item what is being ordered
     * @param net the net price
     * @return whether the payment was accepted
     */
    public boolean placeOrder(String item, BigDecimal net) {
        BigDecimal gross = pricing.gross(net);
        if (!paymentGateway.charge(gross)) {
            auditLog.get().record("declined " + item);
            return false;
        }
        repository.save(orderPrefix + item);
        auditLog.get().record("placed " + item);
        return true;
    }

    /**
     * Returns the injected repository.
     *
     * @return the injected repository
     */
    public OrderRepository repository() {
        return repository;
    }

    /**
     * Returns the {@code @EJB} reference.
     *
     * @return the {@code @EJB} reference
     */
    public PricingService pricing() {
        return pricing;
    }

    /**
     * Returns the bound payment gateway.
     *
     * @return the bound payment gateway
     */
    public PaymentGateway paymentGateway() {
        return paymentGateway;
    }

    /**
     * Returns the injected provider itself, not its value.
     *
     * @return the injected provider itself, not its value
     */
    public Provider<AuditLog> auditLogProvider() {
        return auditLog;
    }
}
