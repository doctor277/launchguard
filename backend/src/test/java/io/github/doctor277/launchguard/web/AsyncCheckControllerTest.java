package io.github.doctor277.launchguard.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.doctor277.launchguard.dto.AsyncCheckResponse;
import io.github.doctor277.launchguard.messaging.ProbeDispatcher;
import io.github.doctor277.launchguard.messaging.ProbeDispatchException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AsyncCheckControllerTest {
    @Test
    void returns202WithRequestIdWithoutWaitingForAResult() throws Exception {
        var dispatcher = mock(ProbeDispatcher.class);
        UUID serviceId = UUID.randomUUID(), requestId = UUID.randomUUID();
        when(dispatcher.dispatch(serviceId)).thenReturn(new AsyncCheckResponse(requestId, serviceId, "QUEUED"));
        MockMvcBuilders.standaloneSetup(new AsyncCheckController(dispatcher)).build()
                .perform(post("/api/services/{id}/check/async", serviceId))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }
    @Test
    void immediatePublishFailureUsesStructured503() throws Exception {
        var dispatcher = mock(ProbeDispatcher.class);
        UUID serviceId = UUID.randomUUID();
        when(dispatcher.dispatch(serviceId)).thenThrow(new ProbeDispatchException());
        MockMvcBuilders.standaloneSetup(new AsyncCheckController(dispatcher))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/api/services/{id}/check/async", serviceId))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503));
    }
}
