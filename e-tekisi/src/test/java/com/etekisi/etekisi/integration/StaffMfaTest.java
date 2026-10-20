package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.StaffUser;
import com.etekisi.etekisi.repository.StaffUserRepository;
import com.etekisi.etekisi.security.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Two-step sign-in for operators and admins. */
class StaffMfaTest {

    private static final String PASSWORD = "operator-pass-123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void totpMatchesTheRfc6238ReferenceValue() {
        // RFC 6238 appendix B: SHA-1 secret "12345678901234567890", T = 59 s -> 94287082 (last 6 digits).
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertThat(Totp.codeAt(secret, 1)).isEqualTo("287082");
        assertThat(Totp.verify(secret, "287082", null, Instant.ofEpochSecond(59))).isEqualTo(1);
        assertThat(Totp.verify(secret, "287082", 1L, Instant.ofEpochSecond(59))).isEqualTo(-1); // no replay
    }

    abstract static class Base {
        @Autowired WebApplicationContext context;
        @Autowired StaffUserRepository staff;
        @Autowired BCryptPasswordEncoder encoder;
        @Autowired JwtEncoder jwtEncoder;

        StaffUser newOperator() {
            StaffUser s = new StaffUser();
            s.setFullName("Ops Tester");
            s.setEmail("ops" + System.nanoTime() + "@test.dev");
            s.setPassword(encoder.encode(PASSWORD));
            s.setRole(Role.OPERATOR);
            return staff.save(s);
        }

        JsonNode login(MockMvc mvc, StaffUser s) throws Exception {
            String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + s.getEmail() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"OPERATOR\"}"))
                    .andReturn().getResponse().getContentAsString();
            return JSON.readTree(body);
        }

        static JsonNode postJson(MockMvc mvc, String url, String json) throws Exception {
            return JSON.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getContentAsString());
        }

        static String now(String secret, int offsetSteps) {
            return Totp.codeAt(secret, Totp.currentStep(Instant.now()) + offsetSteps);
        }

        String tokenIssuedEarlier(long id) {
            Instant then = Instant.now().minus(Duration.ofMinutes(1));
            JwtClaimsSet claims = JwtClaimsSet.builder().issuer("tekisi-test").issuedAt(then)
                    .expiresAt(then.plus(Duration.ofHours(1))).subject(String.valueOf(id)).claim("role", "OPERATOR").build();
            return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        }
    }

    @Nested
    @SpringBootTest
    class Optional extends Base {

        @Test
        void enrollThenSignInWithACodeOrARecoveryCode() throws Exception {
            MockMvc mvc = TestAuth.mvc(context);
            StaffUser s = newOperator();
            assertThat(login(mvc, s).get("status").asText()).isEqualTo("SUCCESS");
            String oldSession = tokenIssuedEarlier(s.getId());

            String setup = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/mfa/setup")
                            .with(as(Role.OPERATOR, s.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.otpauthUrl").value(org.hamcrest.Matchers.startsWith("otpauth://totp/e-Tekisi:")))
                    .andReturn().getResponse().getContentAsString();
            String secret = JSON.readTree(setup).get("secret").asText();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/mfa/enable")
                            .with(as(Role.OPERATOR, s.getId())).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                    .andExpect(status().isUnauthorized());
            String enabled = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/mfa/enable")
                            .with(as(Role.OPERATOR, s.getId())).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + now(secret, -1) + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recoveryCodes.length()").value(8))
                    .andReturn().getResponse().getContentAsString();
            String recovery = JSON.readTree(enabled).get("recoveryCodes").get(0).asText();

            // Turning MFA on signs out existing sessions.
            mvc.perform(get("/api/me/mfa").header("Authorization", "Bearer " + oldSession)).andExpect(status().isUnauthorized());
            // The secret is stored encrypted.
            assertThat(staff.findById(s.getId()).orElseThrow().getMfaSecret()).startsWith("enc:v1:").doesNotContain(secret);

            // Password alone no longer gives a token.
            JsonNode first = login(mvc, s);
            assertThat(first.get("status").asText()).isEqualTo("MFA_REQUIRED");
            assertThat(first.has("token")).isFalse();
            String challenge = first.get("mfaToken").asText();
            // The code used to enable can't be replayed; the next one works.
            JsonNode replay = postJson(mvc, "/api/auth/mfa/verify", "{\"mfaToken\":\"" + challenge + "\",\"code\":\"" + now(secret, -1) + "\"}");
            assertThat(replay.get("code").asText()).isEqualTo("INVALID_MFA_CODE");
            JsonNode ok = postJson(mvc, "/api/auth/mfa/verify", "{\"mfaToken\":\"" + challenge + "\",\"code\":\"" + now(secret, 0) + "\"}");
            assertThat(ok.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(ok.get("token").asText()).isNotBlank();
            // A challenge works once.
            assertThat(postJson(mvc, "/api/auth/mfa/verify", "{\"mfaToken\":\"" + challenge + "\",\"code\":\"" + now(secret, 1) + "\"}")
                    .get("code").asText()).isEqualTo("MFA_CHALLENGE_EXPIRED");

            // Lost phone: a recovery code works, once.
            String c2 = login(mvc, s).get("mfaToken").asText();
            assertThat(postJson(mvc, "/api/auth/mfa/verify", "{\"mfaToken\":\"" + c2 + "\",\"code\":\"" + recovery + "\"}")
                    .get("status").asText()).isEqualTo("SUCCESS");
            String c3 = login(mvc, s).get("mfaToken").asText();
            assertThat(postJson(mvc, "/api/auth/mfa/verify", "{\"mfaToken\":\"" + c3 + "\",\"code\":\"" + recovery + "\"}")
                    .get("code").asText()).isEqualTo("INVALID_MFA_CODE");
            mvc.perform(get("/api/me/mfa").with(as(Role.OPERATOR, s.getId())))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.recoveryCodesLeft").value(7));
        }

        @Test
        void wrongCodesLockTheAccount() throws Exception {
            MockMvc mvc = TestAuth.mvc(context);
            StaffUser s = newOperator();
            String secret = JSON.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/me/mfa/setup").with(as(Role.OPERATOR, s.getId()))).andReturn().getResponse().getContentAsString())
                    .get("secret").asText();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/mfa/enable")
                    .with(as(Role.OPERATOR, s.getId())).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + now(secret, 0) + "\"}")).andExpect(status().isOk());

            String wrong = now(secret, 1).equals("123456") ? "654321" : "123456";
            for (int round = 0; round < 2; round++) {
                JsonNode l = login(mvc, s);
                if (!l.has("mfaToken")) break;
                for (int i = 0; i < 3; i++) {
                    postJson(mvc, "/api/auth/mfa/verify", "{\"mfaToken\":\"" + l.get("mfaToken").asText() + "\",\"code\":\"" + wrong + "\"}");
                }
            }
            // Five wrong codes in total (across sign-ins) lock the account, even with the right password.
            assertThat(login(mvc, s).get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        }
    }

    @Nested
    @SpringBootTest(properties = "tekisi.security.staff-mfa-required=true")
    class Required extends Base {

        @Test
        void staffWithoutMfaMustSetItUpToSignIn() throws Exception {
            MockMvc mvc = TestAuth.mvc(context);
            StaffUser s = newOperator();
            JsonNode l = login(mvc, s);
            assertThat(l.get("status").asText()).isEqualTo("MFA_SETUP_REQUIRED");
            assertThat(l.has("token")).isFalse();
            String challenge = l.get("mfaToken").asText();
            // The setup challenge can't be used as a login challenge.
            assertThat(postJson(mvc, "/api/auth/mfa/verify", "{\"mfaToken\":\"" + challenge + "\",\"code\":\"123456\"}")
                    .get("code").asText()).isEqualTo("MFA_CHALLENGE_EXPIRED");

            l = login(mvc, s);
            challenge = l.get("mfaToken").asText();
            String secret = postJson(mvc, "/api/auth/mfa/setup", "{\"mfaToken\":\"" + challenge + "\"}").get("secret").asText();
            JsonNode done = postJson(mvc, "/api/auth/mfa/enable", "{\"mfaToken\":\"" + challenge + "\",\"code\":\"" + now(secret, 0) + "\"}");
            assertThat(done.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(done.get("token").asText()).isNotBlank();
            assertThat(done.get("recoveryCodes").size()).isEqualTo(8);

            assertThat(login(mvc, s).get("status").asText()).isEqualTo("MFA_REQUIRED");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/mfa/disable")
                            .with(as(Role.OPERATOR, s.getId())).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + now(secret, 1) + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("MFA_REQUIRED_BY_POLICY"));
        }
    }
}
