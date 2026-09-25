package io.github.doctor277.launchguard.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.dto.CreateServiceRequest;
import io.github.doctor277.launchguard.dto.ServiceResponse;
import io.github.doctor277.launchguard.service.HealthCheckService;
import io.github.doctor277.launchguard.service.ServiceManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class ServiceControllerTest {

    @Mock
    private ServiceManager serviceManager;

    @Mock
    private HealthCheckService healthCheckService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ServiceController(serviceManager, healthCheckService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void registersService() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        ServiceResponse response = new ServiceResponse(id, "payment-service", "http://localhost:8081",
                "/health", ServiceStatus.UNKNOWN, null, now, now);
        when(serviceManager.create(any(CreateServiceRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "payment-service",
                                  "baseUrl": "http://localhost:8081",
                                  "healthPath": "/health"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/services/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void rejectsInvalidRegistration() throws Exception {
        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " ",
                                  "baseUrl": "ftp://example.com",
                                  "healthPath": "health"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.violations.length()").value(3));
    }
}
