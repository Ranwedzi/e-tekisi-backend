package com.etekisi.etekisi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase Frontend-Wireup — OpenAPI 3 metadata for the Tekisi backend.
 *
 * <p>Frontend devs hit:
 * <ul>
 *   <li>{@code GET /swagger-ui.html} — interactive console.</li>
 *   <li>{@code GET /v3/api-docs} — raw OpenAPI JSON for codegen.</li>
 * </ul>
 *
 * <p>Both paths are explicitly permitted in {@link SecurityConfig}; production
 * deployments may opt to restrict {@code /swagger-ui.html} via reverse-proxy
 * rule rather than at the app layer.
 *
 * <p>The bearer scheme below appears in Swagger UI's "Authorize" dialog so a
 * frontend dev can paste a token from {@code POST /api/auth/login} and exercise
 * gated endpoints without leaving the page.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tekisiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tekisi Backend API")
                        .version("1.0")
                        .description("Mini-bus taxi platform — commuter / driver / operator REST surface. " +
                                "Every state-changing endpoint requires a Bearer JWT obtained from " +
                                "POST /api/auth/login."))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("HS256 JWT minted by /api/auth/login. " +
                                        "Subject = userId, claim 'role' = COMMUTER|DRIVER|OPERATOR|ADMIN.")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
