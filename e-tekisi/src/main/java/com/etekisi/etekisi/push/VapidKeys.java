package com.etekisi.etekisi.push;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The application server's VAPID identity (RFC 8292): a P-256 key pair whose
 * public half browsers subscribe with, and which signs a short JWT on every
 * push. Set VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY (scripts/vapid-keys.sh makes
 * a pair) and keep them: new keys invalidate every existing subscription.
 * Without them, dev makes a temporary pair (logged) and prod turns push off.
 */
@Component
public class VapidKeys {

    private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);

    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String publicKeyB64;
    private final String subject;
    private final boolean enabled;
    private final Map<String, CachedJwt> jwtByAudience = new ConcurrentHashMap<>();

    private record CachedJwt(String token, Instant renewAfter) {}

    public VapidKeys(@Value("${tekisi.push.vapid-public-key:}") String pub,
                     @Value("${tekisi.push.vapid-private-key:}") String priv,
                     @Value("${tekisi.push.subject:mailto:ops@example.com}") String subject,
                     @Value("${tekisi.push.generate-if-missing:false}") boolean generate) {
        this.subject = subject.isBlank() ? "mailto:ops@example.com" : subject;
        if (!pub.isBlank() && !priv.isBlank()) {
            this.publicKey = WebPushCrypto.publicKey(WebPushCrypto.b64(pub));
            this.privateKey = WebPushCrypto.privateKey(WebPushCrypto.b64(priv));
            this.enabled = true;
        } else if (generate) {
            KeyPair k = WebPushCrypto.newKeyPair();
            this.publicKey = (ECPublicKey) k.getPublic();
            this.privateKey = (ECPrivateKey) k.getPrivate();
            this.enabled = true;
            log.warn("push.vapid.generated: temporary keys, subscriptions stop working on restart. To keep them set "
                    + "VAPID_PUBLIC_KEY={} VAPID_PRIVATE_KEY={}",
                    WebPushCrypto.b64(WebPushCrypto.rawPublic(publicKey)), WebPushCrypto.b64(WebPushCrypto.rawPrivate(privateKey)));
        } else {
            this.publicKey = null;
            this.privateKey = null;
            this.enabled = false;
            log.warn("push.disabled: set VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY (scripts/vapid-keys.sh) to send notifications");
        }
        this.publicKeyB64 = enabled ? WebPushCrypto.b64(WebPushCrypto.rawPublic(publicKey)) : null;
    }

    public boolean enabled() {
        return enabled;
    }

    /** What browsers pass as applicationServerKey. */
    public String publicKey() {
        return publicKeyB64;
    }

    /** {@code Authorization} header value for a push to {@code endpoint}. JWTs are reused for ~11 hours per push service. */
    public String authorization(String endpoint) {
        URI u = URI.create(endpoint);
        String audience = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        CachedJwt c = jwtByAudience.compute(audience, (k, old) ->
                old != null && old.renewAfter().isAfter(Instant.now()) ? old : sign(audience));
        return "vapid t=" + c.token() + ", k=" + publicKeyB64;
    }

    private CachedJwt sign(String audience) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(audience)
                .expirationTime(Date.from(now.plus(Duration.ofHours(12))))
                .subject(subject)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new ECDSASigner(privateKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("VAPID signing failed", e);
        }
        return new CachedJwt(jwt.serialize(), now.plus(Duration.ofHours(11)));
    }

    ECPublicKey ecPublicKey() {
        return publicKey;
    }
}
