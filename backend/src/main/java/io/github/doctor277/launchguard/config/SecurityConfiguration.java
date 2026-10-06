package io.github.doctor277.launchguard.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    private static final Set<String> APPLICATION_ROLES = Set.of("VIEWER", "OPERATOR", "ADMIN");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties properties) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Browsers do not attach Authorization bearer tokens automatically, so this stateless API is not
                // vulnerable to cookie-based CSRF. The OIDC authorization response is validated by the SPA client.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/services").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/services/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/services/*/check",
                                "/api/services/*/check/async", "/api/services/*/deployments")
                                .hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**")
                                .hasAnyRole("VIEWER", "OPERATOR", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(properties)))
                        .authenticationEntryPoint((request, response, exception) ->
                                SecurityErrorWriter.write(request, response, HttpStatus.UNAUTHORIZED.value(),
                                        "Unauthorized", "A valid bearer access token is required")))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, exception) ->
                        SecurityErrorWriter.write(request, response, HttpStatus.FORBIDDEN.value(),
                                "Forbidden", "The authenticated user is not permitted to perform this action")))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                .addFilterBefore(new SecurityHeadersFilter(properties.isHstsEnabled()),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new OperationalRateLimitFilter(properties.getRateLimit()),
                        BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new AuditLoggingFilter(), ExceptionTranslationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter(SecurityProperties properties) {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        Converter<Jwt, Collection<GrantedAuthority>> authorities = jwt -> {
            Collection<GrantedAuthority> converted = new ArrayList<>(scopes.convert(jwt));
            Object claim = jwt.getClaims().get(properties.getRolesClaim());
            if (claim instanceof Collection<?> values) {
                values.stream().map(Object::toString).map(SecurityConfiguration::roleAuthority)
                        .filter(authority -> authority != null).forEach(converted::add);
            } else if (claim instanceof String value) {
                for (String role : value.split("[ ,]")) {
                    GrantedAuthority authority = roleAuthority(role);
                    if (authority != null) converted.add(authority);
                }
            }
            return converted;
        };
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static GrantedAuthority roleAuthority(String role) {
        String normalized = role.toUpperCase(Locale.ROOT);
        return APPLICATION_ROLES.contains(normalized) ? new SimpleGrantedAuthority("ROLE_" + normalized) : null;
    }
}
