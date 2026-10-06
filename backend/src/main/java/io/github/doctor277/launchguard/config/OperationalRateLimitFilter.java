package io.github.doctor277.launchguard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class OperationalRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_IDENTITIES = 10_000;
    private final boolean enabled;
    private final int requestLimit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong();

    OperationalRateLimitFilter(SecurityProperties.RateLimit properties) {
        this(properties.isEnabled(), properties.getRequests(), properties.getWindow(), Clock.systemUTC());
    }

    OperationalRateLimitFilter(boolean enabled, int requestLimit, Duration window, Clock clock) {
        this.enabled = enabled;
        this.requestLimit = requestLimit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/")
                || !(request.getMethod().equals("POST") || request.getMethod().equals("DELETE"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = clock.millis();
        if ((requestCounter.incrementAndGet() & 1023) == 0) {
            windows.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        }
        if (windows.size() >= MAX_IDENTITIES && !windows.containsKey(authentication.getName())) {
            reject(request, response, 1);
            return;
        }

        Window current = windows.compute(authentication.getName(), (key, previous) -> {
            if (previous == null || previous.expiresAt() <= now) {
                return new Window(1, now + windowMillis);
            }
            return new Window(previous.requests() + 1, previous.expiresAt());
        });
        if (current.requests() > requestLimit) {
            long retryAfter = Math.max(1, (current.expiresAt() - now + 999) / 1000);
            reject(request, response, retryAfter);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response,
                               long retryAfterSeconds) throws IOException {
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        SecurityErrorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests", "Operational request rate limit exceeded");
    }

    private record Window(int requests, long expiresAt) {
    }
}
