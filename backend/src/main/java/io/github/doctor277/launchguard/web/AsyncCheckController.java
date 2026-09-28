package io.github.doctor277.launchguard.web;

import io.github.doctor277.launchguard.dto.AsyncCheckResponse;
import io.github.doctor277.launchguard.messaging.ProbeDispatcher;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AsyncCheckController {
    private final ProbeDispatcher dispatcher;

    public AsyncCheckController(ProbeDispatcher dispatcher) { this.dispatcher = dispatcher; }

    @PostMapping("/api/services/{id}/check/async")
    public ResponseEntity<AsyncCheckResponse> dispatch(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(dispatcher.dispatch(id));
    }
}
