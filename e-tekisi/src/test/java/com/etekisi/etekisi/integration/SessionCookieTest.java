package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browser sessions in httpOnly cookies: sign-in, refresh with rotation,
 * reuse detection, sign-out, sign-out everywhere, password changes, and the
 * X-Requested-With requirement for cookie-authenticated writes.
 */
@SpringBootTest
class SessionCookieTest {

    static final String ACCESS = "__Host-tekisi_at";
    static final String REFRESH = "__Secure-tekisi_rt";
    static final String PASSWORD = "copper-lantern-58";
    static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 Chrome/141.0 Mobile Safari/537.36";

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private UserRepository users;
    @Autowired private BCryptPasswordEncoder passwords;

    private User commuter() {
        User u = fixtures.commuter();
        u.setPassword(passwords.encode(PASSWORD));
        return users.save(u);
    }

    private MockHttpServletResponse signIn(MockMvc mvc, User u) throws Exception {
        return mvc.perform(post("/api/auth/login").header("X-Session", "cookie").header("User-Agent", UA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"COMMUTER\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private MockHttpServletResponse refresh(MockMvc mvc, Cookie refresh) throws Exception {
        return mvc.perform(post("/api/auth/session/refresh").cookie(refresh).header("X-Requested-With", "etekisi"))
                .andReturn().getResponse();
    }

    @Test
    void signInRefreshAndSignOutWithCookies() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User u = commuter();
        MockHttpServletResponse login = signIn(mvc, u);

        // The page never sees a token: it's in httpOnly cookies.
        assertThat(login.getContentAsString()).doesNotContain("\"token\"").contains("\"userId\":" + u.getId());
        Cookie access = login.getCookie(ACCESS);
        Cookie refresh1 = login.getCookie(REFRESH);
        assertThat(access).isNotNull();
        assertThat(refresh1).isNotNull();
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getSecure()).isTrue();
        assertThat(access.getPath()).isEqualTo("/");
        assertThat(access.getMaxAge()).isEqualTo(15 * 60);
        assertThat(refresh1.isHttpOnly()).isTrue();
        assertThat(refresh1.getPath()).isEqualTo("/api/auth/session");
        assertThat(login.getHeaders("Set-Cookie")).allMatch(c -> c.contains("SameSite=Strict"));

        // The cookie signs requests in, and lists this device.
        mvc.perform(get("/api/me/sessions").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].device").value("Chrome on Android"));

        // Writes with a cookie need X-Requested-With (CSRF).
        String body = "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/none\"}";
        mvc.perform(post("/api/me/push/unsubscribe").cookie(access).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_HEADER_MISSING"));
        mvc.perform(post("/api/me/push/unsubscribe").cookie(access).header("X-Requested-With", "etekisi")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/session/refresh").cookie(refresh1))
                .andExpect(status().isForbidden());

        // Refresh replaces the refresh token.
        MockHttpServletResponse r2 = refresh(mvc, refresh1);
        assertThat(r2.getStatus()).isEqualTo(200);
        Cookie refresh2 = r2.getCookie(REFRESH);
        assertThat(refresh2.getValue()).isNotEqualTo(refresh1.getValue());
        assertThat(r2.getCookie(ACCESS).getValue()).isNotBlank();
        assertThat(r2.getContentAsString()).contains("\"role\":\"COMMUTER\"").doesNotContain("\"token\"");

        MockHttpServletResponse r3 = refresh(mvc, refresh2);
        assertThat(r3.getStatus()).isEqualTo(200);

        // An old token coming back means it was copied: the whole session ends.
        MockHttpServletResponse reuse = refresh(mvc, refresh1);
        assertThat(reuse.getStatus()).isEqualTo(401);
        assertThat(reuse.getContentAsString()).contains("SESSION_ENDED");
        assertThat(reuse.getCookie(REFRESH).getMaxAge()).isZero();
        assertThat(refresh(mvc, r3.getCookie(REFRESH)).getStatus()).isEqualTo(401);

        // Signing out ends that browser's session.
        MockHttpServletResponse again = signIn(mvc, u);
        mvc.perform(post("/api/auth/session/logout").cookie(again.getCookie(REFRESH)).header("X-Requested-With", "etekisi"))
                .andExpect(status().isOk());
        assertThat(refresh(mvc, again.getCookie(REFRESH)).getStatus()).isEqualTo(401);
    }

    @Test
    void aPasswordChangeEndsSessions() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User u = commuter();
        Cookie refresh = signIn(mvc, u).getCookie(REFRESH);
        u = users.findById(u.getId()).orElseThrow();
        u.setCredentialsChangedAt(Instant.now().plusSeconds(2));
        users.save(u);
        MockHttpServletResponse r = refresh(mvc, refresh);
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentAsString()).contains("SESSION_ENDED");
    }

    @Test
    void signOutEverywhereEndsEverySession() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User u = commuter();
        MockHttpServletResponse phone = signIn(mvc, u);
        MockHttpServletResponse laptop = signIn(mvc, u);
        mvc.perform(get("/api/me/sessions").cookie(phone.getCookie(ACCESS)))
                .andExpect(jsonPath("$.length()").value(2));

        Thread.sleep(1100); // access tokens carry whole-second issue times
        mvc.perform(post("/api/me/sessions/revoke-all").cookie(laptop.getCookie(ACCESS)).header("X-Requested-With", "etekisi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionsEnded").value(2));

        assertThat(refresh(mvc, phone.getCookie(REFRESH)).getStatus()).isEqualTo(401);
        assertThat(refresh(mvc, laptop.getCookie(REFRESH)).getStatus()).isEqualTo(401);
        mvc.perform(get("/api/me/sessions").cookie(phone.getCookie(ACCESS))).andExpect(status().isUnauthorized());
    }

    @Test
    void apiClientsStillGetABearerTokenAndStaleCookiesDontBlockSignIn() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User u = commuter();
        String body = "{\"email\":\"" + u.getEmail() + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"COMMUTER\"}";

        MockHttpServletResponse bearer = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse();
        assertThat(bearer.getHeaders("Set-Cookie")).isEmpty();

        // An expired or broken access cookie is ignored on sign-in, refused elsewhere.
        Cookie junk = new Cookie(ACCESS, "not-a-jwt");
        mvc.perform(post("/api/auth/login").cookie(junk).header("X-Requested-With", "etekisi").header("X-Session", "cookie")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/sessions").cookie(junk)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/session/refresh").header("X-Requested-With", "etekisi"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_ENDED"));
    }
}
