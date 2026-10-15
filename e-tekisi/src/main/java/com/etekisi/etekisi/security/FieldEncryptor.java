package com.etekisi.etekisi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts sensitive text columns (bank account numbers, phone numbers for
 * payouts) with AES-256-GCM before they reach the database.
 *
 * <p>Stored format: {@code enc:v1:<base64(12-byte IV || ciphertext+tag)>}.
 * Values without the prefix are legacy plaintext and are returned as-is, so
 * rows written before encryption still read; {@code LegacyPayoutEncryption}
 * rewrites them at startup.
 *
 * <p>The key comes from {@code tekisi.security.data-key} (env
 * {@code DATA_ENCRYPTION_KEY}): base64 of exactly 32 random bytes. Losing it
 * makes the encrypted columns unreadable, so back it up with the database
 * credentials. Generate one with {@code openssl rand -base64 32}.
 */
@Component
public class FieldEncryptor {

    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(@Value("${tekisi.security.data-key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("tekisi.security.data-key must be base64 (openssl rand -base64 32)");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("tekisi.security.data-key must decode to exactly 32 bytes, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Wrong key or tampered value. Never fall back to returning ciphertext as data.
            throw new IllegalStateException("Could not decrypt a stored value (wrong DATA_ENCRYPTION_KEY?)", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
