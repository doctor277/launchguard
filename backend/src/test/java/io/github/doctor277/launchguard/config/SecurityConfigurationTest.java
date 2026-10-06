package io.github.doctor277.launchguard.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityConfigurationTest.SecurityProbeController.class,
        properties = {
                "launchguard.security.allowed-origins=http://localhost:3001",
                "launchguard.security.rate-limit.enabled=false",
                "launchguard.kafka.health-enabled=false"
        })
@Import({SecurityConfiguration.class, SecurityConfigurationTest.SecurityProbeController.class})
@ImportAutoConfiguration({SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigurationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (token.equals("malformed")) throw new BadJwtException("invalid token");
            return jwt(token, token.toUpperCase());
        });
    }

    @Test
    void anonymousApiRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/api/services"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void viewerCanReadButCannotTriggerAProbe() throws Exception {
        mvc.perform(get("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer viewer"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/services/00000000-0000-0000-0000-000000000001/check")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void authenticatedAuthorizationFailureIsAuditLogged(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer viewer"))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(output.getOut())
                .contains("security_audit action=POST path=/api/services subject=viewer")
                .contains("roles=[ROLE_VIEWER] status=403");
    }

    @Test
    void operatorCanTriggerProbeAndRegisterDeploymentButCannotAdministerServices() throws Exception {
        mvc.perform(post("/api/services/00000000-0000-0000-0000-000000000001/check")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/services/00000000-0000-0000-0000-000000000001/deployments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer operator"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateAndDeleteServices() throws Exception {
        mvc.perform(post("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer admin"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/services/00000000-0000-0000-0000-000000000001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin"))
                .andExpect(status().isNoContent());
    }

    @Test
    void malformedTokenIsUnauthorizedWithoutLeakingDecoderDetails() throws Exception {
        mvc.perform(get("/api/services").header(HttpHeaders.AUTHORIZATION, "Bearer malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("A valid bearer access token is required"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid token"))));
    }

    @Test
    void healthAndPrometheusRemainAnonymousButCarryHardeningHeaders() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void corsAllowsOnlyTheConfiguredDevelopmentOrigin() throws Exception {
        mvc.perform(options("/api/services")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3001")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3001"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        mvc.perform(options("/api/services")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    private static Jwt jwt(String token, String role) {
        Instant now = Instant.now();
        return new Jwt(token, now.minusSeconds(1), now.plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", role.toLowerCase(), "roles", List.of(role), "scope", "openid"));
    }

    @RestController
    @RequestMapping
    static class SecurityProbeController {
        @GetMapping({"/api/services", "/actuator/health/liveness", "/actuator/prometheus"})
        Map<String, String> read() { return Map.of("status", "ok"); }

        @PostMapping({"/api/services", "/api/services/{id}/check", "/api/services/{id}/deployments"})
        Map<String, String> write() { return Map.of("status", "ok"); }

        @DeleteMapping("/api/services/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void delete() { }
    }
}
