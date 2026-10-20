package com.etekisi.etekisi.notify;

/** Sends account emails (verification, password reset). See {@link NotifyConfig} for how one is chosen. */
public interface EmailSender {

    /** Throws {@link DeliveryException} if the message couldn't be handed to the provider. */
    void send(String to, String subject, String text);

    /** False when nothing is configured to deliver email. */
    default boolean available() {
        return true;
    }
}
