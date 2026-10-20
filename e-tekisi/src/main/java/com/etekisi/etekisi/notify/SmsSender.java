package com.etekisi.etekisi.notify;

/** Sends SMS codes (phone verification, password reset). See {@link NotifyConfig} for how one is chosen. */
public interface SmsSender {

    /** {@code to} is E.164, e.g. +27821234567. Throws {@link DeliveryException} on failure. */
    void send(String to, String text);

    /** False when no SMS provider is configured: phone verification is then not offered. */
    default boolean available() {
        return true;
    }
}
