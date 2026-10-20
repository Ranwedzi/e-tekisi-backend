package com.etekisi.etekisi.notify;

/** An email or SMS couldn't be handed to the provider. */
public class DeliveryException extends RuntimeException {
    public DeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeliveryException(String message) {
        super(message);
    }
}
