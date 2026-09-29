package com.etekisi.etekisi.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pretend gateway for local development: every charge succeeds and no real
 * money moves. Only exists when {@code tekisi.payments.sandbox-enabled=true};
 * the prod profile sets it to false, so top-ups are refused there until a
 * real provider is wired in.
 */
@Component
@ConditionalOnProperty(name = "tekisi.payments.sandbox-enabled", havingValue = "true")
public class SandboxPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxPaymentProvider.class);

    @Override
    public PaymentResult charge(Long commuterId, BigDecimal amount, String reference) {
        String ref = "SANDBOX-" + UUID.randomUUID();
        log.info("payment.sandbox.charge commuterId={} amount={} reference={} providerRef={}",
                commuterId, amount, reference, ref);
        return PaymentResult.ok(ref);
    }
}
