package io.github.doctor277.launchguard.demo.payment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentHealthController {

    private final AtomicBoolean failureMode = new AtomicBoolean(false);

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        if (failureMode.get()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "unhealthy"));
        }
        return ResponseEntity.ok(Map.of("status", "healthy"));
    }

    @PostMapping("/admin/fail")
    public Map<String, String> fail() {
        failureMode.set(true);
        return Map.of("status", "failure mode enabled");
    }

    @PostMapping("/admin/recover")
    public Map<String, String> recover() {
        failureMode.set(false);
        return Map.of("status", "healthy");
    }
}
