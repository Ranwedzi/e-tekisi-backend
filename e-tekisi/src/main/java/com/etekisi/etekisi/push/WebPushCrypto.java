package com.etekisi.etekisi.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;

/**
 * Message encryption for Web Push (RFC 8291, "aes128gcm" content coding,
 * RFC 8188) with only the JDK's crypto. Verified against the RFC's own test
 * vectors (WebPushCryptoTest).
 */
public final class WebPushCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RECORD_SIZE = 4096;
    static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {}

    /** Encrypts {@code plaintext} for one subscription (its p256dh key and auth secret). */
    public static byte[] encrypt(byte[] uaPublic, byte[] authSecret, byte[] plaintext) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(uaPublic, authSecret, plaintext, newKeyPair(), salt);
    }

    /** With a given sender key pair and salt: for the RFC test vectors. */
    static byte[] encrypt(byte[] uaPublic, byte[] authSecret, byte[] plaintext, KeyPair asKeys, byte[] salt) {
        try {
            byte[] asPublic = rawPublic((ECPublicKey) asKeys.getPublic());
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(asKeys.getPrivate());
            ka.doPhase(publicKey(uaPublic), true);
            byte[] ecdhSecret = ka.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[]{1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = aes.doFinal(concat(plaintext, new byte[]{2})); // 0x02: last (only) record

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push encryption failed", e);
        }
    }

    // ------------------------------------------------------------------ keys --

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 65-byte uncompressed point (0x04 || X || Y), as browsers and VAPID use. */
    public static byte[] rawPublic(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(fixed32(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed32(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    /** 32-byte private scalar. */
    public static byte[] rawPrivate(ECPrivateKey key) {
        return fixed32(key.getS());
    }

    public static ECPublicKey publicKey(byte[] raw) {
        if (raw.length != 65 || raw[0] != 4) throw new IllegalArgumentException("Not an uncompressed P-256 point");
        try {
            ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid P-256 public key", e);
        }
    }

    public static ECPrivateKey privateKey(byte[] raw) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid P-256 private key", e);
        }
    }

    public static byte[] b64(String base64url) {
        return Base64.getUrlDecoder().decode(base64url.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // --------------------------------------------------------------- helpers --

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] fixed32(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length == 32) return b;
        byte[] out = new byte[32];
        if (b.length > 32) System.arraycopy(b, b.length - 32, out, 0, 32); // drop the sign byte
        else System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
