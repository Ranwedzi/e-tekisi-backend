package com.etekisi.etekisi.payment;

import java.math.BigDecimal;

/**
 * Collects money from a commuter for a wallet top-up. The wallet is only
 * credited after {@link #charge} reports success.
 *
 * <p>Today the only implementation is {@link SandboxPaymentProvider} (dev
 * only). A real gateway (Yoco, PayFast, Paystack, …) implements this
 * interface; with a hosted checkout, the credit would move to the gateway's
 * confirmation webhook instead of this synchronous call.
 */
public interface PaymentProvider {

    PaymentResult charge(Long commuterId, BigDecimal amount, String reference);

    record PaymentResult(boolean success, String providerReference, String failureReason) {
        public static PaymentResult ok(String providerReference) {
            return new PaymentResult(true, providerReference, null);
        }

        public static PaymentResult failed(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }
}
