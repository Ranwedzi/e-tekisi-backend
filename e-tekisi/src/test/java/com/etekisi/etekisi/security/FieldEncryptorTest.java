package com.etekisi.etekisi.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptorTest {

    private static String key(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (i + seed);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void roundTripsAndUsesAFreshIvEachTime() {
        FieldEncryptor enc = new FieldEncryptor(key(1));
        String a = enc.encrypt("62123456789");
        String b = enc.encrypt("62123456789");
        assertThat(a).startsWith("enc:v1:").isNotEqualTo(b);
        assertThat(enc.decrypt(a)).isEqualTo("62123456789");
        assertThat(enc.decrypt(b)).isEqualTo("62123456789");
    }

    @Test
    void legacyPlaintextAndNullPassThrough() {
        FieldEncryptor enc = new FieldEncryptor(key(1));
        assertThat(enc.decrypt("0821234567")).isEqualTo("0821234567");
        assertThat(enc.encrypt(null)).isNull();
        assertThat(enc.decrypt(null)).isNull();
    }

    @Test
    void wrongKeyOrTamperingFailsLoudly() {
        String ct = new FieldEncryptor(key(1)).encrypt("secret");
        assertThatThrownBy(() -> new FieldEncryptor(key(2)).decrypt(ct)).isInstanceOf(IllegalStateException.class);
        String tampered = ct.substring(0, ct.length() - 4) + (ct.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThatThrownBy(() -> new FieldEncryptor(key(1)).decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadKeys() {
        assertThatThrownBy(() -> new FieldEncryptor("not base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldEncryptor(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
