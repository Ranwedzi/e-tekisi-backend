package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.push.PushSubscriptionRepository;
import com.etekisi.etekisi.push.WebPushCrypto;
import com.etekisi.etekisi.repository.DriverLocationRepository;
import com.etekisi.etekisi.service.NotificationService;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web Push end to end against a fake push service: what reaches it must carry
 * a valid VAPID signature and decrypt, with the browser's keys, to the
 * notification; a device the push service says is gone is forgotten.
 */
@SpringBootTest
class PushNotificationTest {

    record Received(String path, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    /** A browser: its own key pair and auth secret, as pushManager.subscribe() would make. */
    record Browser(KeyPair keys, byte[] auth, String endpoint) {
        String json() {
            return """
                    {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"}}"""
                    .formatted(endpoint, WebPushCrypto.b64(WebPushCrypto.rawPublic((ECPublicKey) keys.getPublic())),
                            WebPushCrypto.b64(auth));
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private PushSubscriptionRepository subscriptions;
    @Autowired private DriverLocationRepository locations;
    @Autowired private NotificationService notifications;

    private HttpServer server;
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void startPushService() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/push/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            received.add(new Received(ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body));
            ex.sendResponseHeaders(ex.getRequestURI().getPath().endsWith("/gone") ? 410 : 201, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopPushService() {
        server.stop(0);
    }

    private Browser browser(String name) {
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        return new Browser(WebPushCrypto.newKeyPair(), auth,
                "http://localhost:" + server.getAddress().getPort() + "/push/" + name + "-" + System.nanoTime());
    }

    private Received next() throws InterruptedException {
        Received r = received.poll(10, TimeUnit.SECONDS);
        assertThat(r).as("a push reached the push service").isNotNull();
        return r;
    }

    @Test
    void anOfferReachesTheCommutersPhoneEncryptedAndSigned() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String publicKey = mvc.perform(get("/api/push/key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"publicKey\":\"([^\"]+)\".*", "$1");

        User c = fixtures.commuterWithBalance("100");
        Driver d = fixtures.driver();
        Browser phone = browser("commuter");
        mvc.perform(post("/api/me/push").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(phone.json()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/push").with(as(Role.COMMUTER, c.getId())))
                .andExpect(jsonPath("$.devices").value(1));

        Long bookingId = fixtures.pendingBooking(c.getId()).getId();
        mvc.perform(put("/api/bookings/" + bookingId + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isOk());

        Received r = next();
        assertThat(phone.endpoint()).endsWith(r.path());
        assertThat(r.header("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(r.header("TTL")).isEqualTo("3600");

        // VAPID: a JWT for this push service, signed with the key browsers subscribed with.
        String authz = r.header("Authorization");
        assertThat(authz).startsWith("vapid t=").endsWith(", k=" + publicKey);
        SignedJWT jwt = SignedJWT.parse(authz.substring("vapid t=".length(), authz.indexOf(',')));
        assertThat(jwt.verify(new ECDSAVerifier(WebPushCrypto.publicKey(WebPushCrypto.b64(publicKey))))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("http://localhost:" + server.getAddress().getPort());
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isInTheFuture();

        String payload = decrypt(phone, r.body());
        assertThat(payload).contains("\"title\":\"e-Tekisi\"").contains("offers to take you to").contains("\"url\":\"/\"");

        // Signing out of this browser stops its pushes.
        mvc.perform(post("/api/me/push/unsubscribe").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + phone.endpoint() + "\"}"))
                .andExpect(status().isOk());
        assertThat(subscriptions.countByOwnerRoleAndOwnerId(Role.COMMUTER, c.getId())).isZero();
    }

    @Test
    void nearbyOnlineDriversHearAboutANewRequest() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver near = fixtures.driver();
        Driver far = fixtures.driver();
        position(near, -26.205, 28.04);   // about 0.5 km from the pick-up
        position(far, -33.92, 18.42);     // Cape Town
        Browser nearPhone = browser("near");
        Browser farPhone = browser("far");
        for (var e : Map.of(near, nearPhone, far, farPhone).entrySet()) {
            mvc.perform(post("/api/me/push").with(as(Role.DRIVER, e.getKey().getId()))
                            .contentType(MediaType.APPLICATION_JSON).content(e.getValue().json()))
                    .andExpect(status().isOk());
        }

        User c = fixtures.commuterWithBalance("100");
        mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pickupLocation":"Bree Taxi Rank","destination":"Soweto","pickupLat":-26.2005,"pickupLng":28.0368}"""))
                .andExpect(status().isOk());

        Received r = next();
        assertThat(nearPhone.endpoint()).endsWith(r.path());
        assertThat(r.header("TTL")).isEqualTo("300");
        assertThat(decrypt(nearPhone, r.body())).contains("New trip request").contains("Bree Taxi Rank");
        assertThat(received.poll(1, TimeUnit.SECONDS)).as("the far driver isn't disturbed").isNull();
    }

    @Test
    void aDeviceThePushServiceSaysIsGoneIsForgotten() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuter();
        Browser stale = new Browser(WebPushCrypto.newKeyPair(), new byte[16],
                "http://localhost:" + server.getAddress().getPort() + "/push/gone");
        mvc.perform(post("/api/me/push").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(stale.json()))
                .andExpect(status().isOk());
        notifications.send(c.getId(), Role.COMMUTER, "Hello");

        next();
        for (int i = 0; i < 50 && subscriptions.countByOwnerRoleAndOwnerId(Role.COMMUTER, c.getId()) > 0; i++) {
            Thread.sleep(100); // the 410 is handled just after the push service answers
        }
        assertThat(subscriptions.countByOwnerRoleAndOwnerId(Role.COMMUTER, c.getId())).isZero();
    }

    @Test
    void onlyRealPushServicesAreAccepted() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuter();
        Browser b = browser("x");
        for (String endpoint : List.of("https://internal.example.com/admin", "http://fcm.googleapis.com/fcm/send/abc",
                "file:///etc/passwd", "not a url")) {
            String body = b.json().replace(b.endpoint(), endpoint);
            mvc.perform(post("/api/me/push").with(as(Role.COMMUTER, c.getId()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION"));
        }
        // Keys that aren't a P-256 point.
        mvc.perform(post("/api/me/push").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/abc\",\"keys\":{\"p256dh\":\"AAAA\",\"auth\":\"AAAA\"}}"))
                .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION"));
        // A real one, and signed-out callers can't register.
        mvc.perform(post("/api/me/push").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(b.json().replace(b.endpoint(), "https://fcm.googleapis.com/fcm/send/abc-" + System.nanoTime())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/me/push").contentType(MediaType.APPLICATION_JSON).content(b.json()))
                .andExpect(status().isUnauthorized());
    }

    private void position(Driver d, double lat, double lng) {
        DriverLocation l = new DriverLocation();
        l.setDriverId(d.getId());
        l.setLat(lat);
        l.setLng(lng);
        l.setLastUpdated(LocalDateTime.now());
        locations.save(l);
    }

    /** What the browser does with a push: RFC 8291 decryption with its private key and auth secret. */
    private static String decrypt(Browser b, byte[] body) throws Exception {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idlen = body[20] & 0xff;
        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idlen);
        byte[] ciphertext = Arrays.copyOfRange(body, 21 + idlen, body.length);
        byte[] uaPublic = WebPushCrypto.rawPublic((ECPublicKey) b.keys().getPublic());

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init((ECPrivateKey) b.keys().getPrivate());
        ka.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] ecdh = ka.generateSecret();

        byte[] prkKey = hmac(b.auth(), ecdh);
        byte[] ikm = hmac(prkKey, concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1})), 12);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = aes.doFinal(ciphertext);
        int end = padded.length - 1;
        while (end > 0 && padded[end] == 0) end--;
        assertThat(padded[end]).as("last-record delimiter").isEqualTo((byte) 2);
        return new String(padded, 0, end, StandardCharsets.UTF_8);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
