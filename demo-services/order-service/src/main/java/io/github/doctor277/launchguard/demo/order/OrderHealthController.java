package io.github.doctor277.launchguard.demo.order;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OrderHealthController {

    private static final Logger log = LoggerFactory.getLogger(OrderHealthController.class);
    private final AtomicBoolean failureMode = new AtomicBoolean(false);
    private final AtomicLong delayMs = new AtomicLong();
    private final DemoProperties properties;

    public OrderHealthController(DemoProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        long delay = delayMs.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("demo_health_delay_interrupted");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "interrupted"));
            }
        }
        if (failureMode.get()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("status", "unhealthy"));
        }
        return ResponseEntity.ok(Map.of("status", "healthy"));
    }

    @PostMapping("/admin/fail")
    public Map<String, String> fail() {
        failureMode.set(true);
        log.info("demo_failure_enabled");
        return Map.of("status", "failure mode enabled");
    }

    @PostMapping("/admin/recover")
    public Map<String, String> recover() {
        failureMode.set(false);
        log.info("demo_failure_disabled");
        return Map.of("status", "healthy");
    }

    @PostMapping("/admin/slow")
    public DelayResponse slow(@RequestParam(required = false) Integer delayMs) {
        int requested = delayMs == null ? properties.slowDelayMs() : delayMs;
        if (requested < 1 || requested > DemoProperties.MAX_DELAY_MS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delayMs must be between 1 and 30000");
        }
        this.delayMs.set(requested);
        log.info("demo_delay_enabled delayMs={}", requested);
        return new DelayResponse("slow", requested);
    }

    @PostMapping("/admin/normal")
    public DelayResponse normal() {
        delayMs.set(0);
        log.info("demo_delay_disabled");
        return new DelayResponse("normal", 0);
    }

    public record DelayResponse(String status, long delayMs) {
    }
}
