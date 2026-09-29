package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Role;
import jakarta.servlet.Filter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Builds MockMvc with the real security filter chain, and authenticates requests as a given user. */
public final class TestAuth {

    private TestAuth() {}

    /** Same claims {@code JwtTokenService} mints: {@code sub} = user id, {@code role}. */
    public static RequestPostProcessor as(Role role, long id) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject(String.valueOf(id)).claim("role", role.name()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    public static MockMvc mvc(WebApplicationContext context, Filter... filters) {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .addFilters(filters)
                .build();
    }
}
