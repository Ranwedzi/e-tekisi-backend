package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consent, password rules, email/SMS verification, password reset and change. */
@SpringBootTest
class AccountSecurityTest {

    private static final String PASSWORD = "kettle-sunrise-42";

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private com.etekisi.etekisi.repository.UserRepository users;

    private static String signUp(String email, String phone, String password, String terms) {
        return """
                {"fullName":"Zanele Dube","email":"%s","phoneNumber":"%s","password":"%s"%s}"""
                .formatted(email, phone, password, terms);
    }

    private static final String TERMS = ",\"acceptTerms\":true,\"termsVersion\":\"test-terms-v1\"";

    private long register(MockMvc mvc, String email, String phone) throws Exception {
        String body = mvc.perform(post("/api/auth/register/commuter").contentType(MediaType.APPLICATION_JSON)
                        .content(signUp(email, phone, PASSWORD, TERMS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification.email").value("SENT"))
                .andExpect(jsonPath("$.verification.phone").value("SENT"))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"userId\":(\\d+).*", "$1"));
    }

    private static String unique() {
        return "z" + System.nanoTime() + "@test.dev";
    }

    /** A real signed token issued a minute ago, so a password change after it must revoke it. */
    private String tokenIssuedEarlier(long id) {
        Instant then = Instant.now().minus(Duration.ofMinutes(1));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("tekisi-test").issuedAt(then)
                .expiresAt(then.plus(Duration.ofHours(1))).subject(String.valueOf(id)).claim("role", "COMMUTER").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static String login(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"COMMUTER\"}";
    }

    @Test
    void signUpNeedsConsentToTheCurrentTermsAndAReasonablePassword() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        mvc.perform(post("/api/auth/register/commuter").contentType(MediaType.APPLICATION_JSON)
                        .content(signUp(unique(), "0821234567", PASSWORD, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TERMS_NOT_ACCEPTED"));
        mvc.perform(post("/api/auth/register/commuter").contentType(MediaType.APPLICATION_JSON)
                        .content(signUp(unique(), "0821234567", PASSWORD, ",\"acceptTerms\":true,\"termsVersion\":\"old\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMS_OUTDATED"))
                .andExpect(jsonPath("$.currentVersion").value("test-terms-v1"));
        mvc.perform(post("/api/auth/register/commuter").contentType(MediaType.APPLICATION_JSON)
                        .content(signUp(unique(), "0821234567", "password1234", TERMS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        String email = unique();
        mvc.perform(post("/api/auth/register/commuter").contentType(MediaType.APPLICATION_JSON)
                        .content(signUp(email, "0821234567", "my-" + email.split("@")[0] + "-pw", TERMS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Don't use your email address in your password."));
        mvc.perform(get("/api/legal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("test-terms-v1"));
    }

    @Test
    void unverifiedCommutersCantBookUntilTheyConfirmTheirPhone() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        long id = register(mvc, unique(), "082 555 0101");

        mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLocation\":\"Bree\",\"destination\":\"Soweto\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_VERIFIED"));
        mvc.perform(get("/api/me/status").with(as(Role.COMMUTER, id)))
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.termsAccepted").value(true));

        mvc.perform(post("/api/me/verification/phone/confirm").with(as(Role.COMMUTER, id))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        String code = fixtures.smsCode("+27825550101");
        mvc.perform(post("/api/me/verification/phone/confirm").with(as(Role.COMMUTER, id))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me/status").with(as(Role.COMMUTER, id)))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.phoneVerified").value(true))
                .andExpect(jsonPath("$.emailVerified").value(false));
        mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLocation\":\"Bree\",\"destination\":\"Soweto\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void emailLinkWorksOnceAndResendsAreLimited() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String email = unique();
        long id = register(mvc, email, "0825550102");
        // Sign-up just sent one: asking again straight away is refused.
        mvc.perform(post("/api/me/verification/email").with(as(Role.COMMUTER, id)))
                .andExpect(status().isTooManyRequests());

        String token = fixtures.emailToken(email, "verify-email");
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("COMMUTER"));
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
        mvc.perform(post("/api/me/verification/email").with(as(Role.COMMUTER, id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
    }

    @Test
    void resetByEmailLinkChangesThePasswordAndSignsOutOldSessions() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String email = unique();
        long id = register(mvc, email, "0825550103");
        String oldToken = tokenIssuedEarlier(id);
        mvc.perform(get("/api/auth/commuter/" + id).header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"COMMUTER\"}"))
                .andExpect(status().isAccepted());
        String token = fixtures.emailToken(email, "reset-password");

        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"violet-harbour-77\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/commuter/" + id).header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(email, PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(email, "violet-harbour-77")))
                .andExpect(status().isOk());
        // Reading the inbox proved the address.
        mvc.perform(get("/api/me/status").with(as(Role.COMMUTER, id)))
                .andExpect(jsonPath("$.emailVerified").value(true));
        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"another-good-one-9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
    }

    @Test
    void resetBySmsCodeAllowsFiveTries() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String email = unique();
        register(mvc, email, "0825550104");
        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"COMMUTER\",\"channel\":\"SMS\"}"))
                .andExpect(status().isAccepted());
        String code = fixtures.smsCode("+27825550104");
        String wrong = code.equals("111111") ? "222222" : "111111";
        String reset = "{\"email\":\"" + email + "\",\"role\":\"COMMUTER\",\"code\":\"%s\",\"newPassword\":\"violet-harbour-77\"}";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content(reset.formatted(wrong)))
                    .andExpect(status().isBadRequest());
        }
        // Even the right code is refused now.
        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON).content(reset.formatted(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Too many wrong tries. Ask for a new code."));
    }

    @Test
    void resetBySmsCode() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String email = unique();
        register(mvc, email, "0825550105");
        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"COMMUTER\",\"channel\":\"SMS\"}"))
                .andExpect(status().isAccepted());
        String code = fixtures.smsCode("+27825550105");
        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"COMMUTER\",\"code\":\"" + code + "\",\"newPassword\":\"violet-harbour-77\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(email, "violet-harbour-77")))
                .andExpect(status().isOk());
    }

    @Test
    void forgotPasswordLooksTheSameForUnknownEmails() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + System.nanoTime() + "@test.dev\",\"role\":\"COMMUTER\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("If an account uses that email, we've sent it a link to reset the password."));
    }

    @Test
    void changingThePasswordNeedsTheCurrentOneAndSignsOutOtherSessions() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String email = unique();
        long id = register(mvc, email, "0825550106");
        String oldToken = tokenIssuedEarlier(id);
        mvc.perform(post("/api/me/password").with(as(Role.COMMUTER, id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-one-123\",\"newPassword\":\"violet-harbour-77\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_INCORRECT"));
        mvc.perform(post("/api/me/password").with(as(Role.COMMUTER, id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"violet-harbour-77\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/commuter/" + id).header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void existingAccountsAcceptNewTerms() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        var u = fixtures.commuter();
        u.setTermsVersion(null); // signed up before the current terms
        users.save(u);
        long id = u.getId();
        mvc.perform(get("/api/me/status").with(as(Role.COMMUTER, id)))
                .andExpect(jsonPath("$.termsAccepted").value(false));
        mvc.perform(post("/api/me/terms").with(as(Role.COMMUTER, id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"old\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/me/terms").with(as(Role.COMMUTER, id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"test-terms-v1\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/status").with(as(Role.COMMUTER, id)))
                .andExpect(jsonPath("$.termsAccepted").value(true));
    }
}
