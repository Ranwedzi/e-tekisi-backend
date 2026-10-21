package com.etekisi.etekisi.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static com.etekisi.etekisi.push.WebPushCrypto.b64;
import static org.assertj.core.api.Assertions.assertThat;

/** Web Push encryption against RFC 8291, Section 5 / Appendix A. */
class WebPushCryptoTest {

    @Test
    void producesTheRfc8291ExampleMessage() {
        byte[] uaPublic = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = b64("BTBZMqHH6r4Tts7J_aSIgg");
        KeyPair as = new KeyPair(
                WebPushCrypto.publicKey(b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
                WebPushCrypto.privateKey(b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")));
        byte[] salt = b64("DGv6ra1nlYgDCS1FRnbzlw");
        byte[] plaintext = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);
        assertThat(b64(plaintext)).isEqualTo("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24");

        byte[] body = WebPushCrypto.encrypt(uaPublic, auth, plaintext, as, salt);

        assertThat(b64(Arrays.copyOf(body, 86)))
                .isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        assertThat(b64(Arrays.copyOfRange(body, 86, body.length)))
                .isEqualTo("8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ");
    }

    @Test
    void keysRoundTrip() {
        KeyPair k = WebPushCrypto.newKeyPair();
        byte[] pub = WebPushCrypto.rawPublic((java.security.interfaces.ECPublicKey) k.getPublic());
        byte[] priv = WebPushCrypto.rawPrivate((java.security.interfaces.ECPrivateKey) k.getPrivate());
        assertThat(pub).hasSize(65);
        assertThat(priv).hasSize(32);
        assertThat(WebPushCrypto.rawPublic(WebPushCrypto.publicKey(pub))).isEqualTo(pub);
        assertThat(WebPushCrypto.rawPrivate(WebPushCrypto.privateKey(priv))).isEqualTo(priv);
    }
}
