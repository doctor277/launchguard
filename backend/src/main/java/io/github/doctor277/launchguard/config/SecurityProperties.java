package io.github.doctor277.launchguard.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("launchguard.security")
public class SecurityProperties {

    private String rolesClaim = "roles";
    private List<String> allowedOrigins = new ArrayList<>();
    private boolean hstsEnabled;
    private final RateLimit rateLimit = new RateLimit();

    public String getRolesClaim() { return rolesClaim; }

    public void setRolesClaim(String rolesClaim) { this.rolesClaim = rolesClaim; }

    public List<String> getAllowedOrigins() { return allowedOrigins; }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }

    public boolean isHstsEnabled() { return hstsEnabled; }

    public void setHstsEnabled(boolean hstsEnabled) { this.hstsEnabled = hstsEnabled; }

    public RateLimit getRateLimit() { return rateLimit; }

    public static class RateLimit {
        private boolean enabled = true;
        private int requests = 60;
        private Duration window = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }

        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getRequests() { return requests; }

        public void setRequests(int requests) {
            if (requests < 1) {
                throw new IllegalArgumentException("launchguard.security.rate-limit.requests must be positive");
            }
            this.requests = requests;
        }

        public Duration getWindow() { return window; }

        public void setWindow(Duration window) {
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("launchguard.security.rate-limit.window must be positive");
            }
            this.window = window;
        }
    }
}
