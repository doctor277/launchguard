package io.github.doctor277.launchguard.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.doctor277.launchguard.dto.CreateDeploymentRequest;
import io.github.doctor277.launchguard.dto.DeploymentResponse;
import io.github.doctor277.launchguard.service.DeploymentNotFoundException;
import io.github.doctor277.launchguard.service.DeploymentService;
import io.github.doctor277.launchguard.service.ServiceNotFoundException;
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
class DeploymentControllerTest {

    @Mock
    private DeploymentService deploymentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new DeploymentController(deploymentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createsDeploymentAndReturnsLocation() throws Exception {
        UUID serviceId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        Instant deployedAt = Instant.parse("2026-09-24T12:00:00Z");
        when(deploymentService.create(org.mockito.ArgumentMatchers.eq(serviceId), any(CreateDeploymentRequest.class)))
                .thenReturn(new DeploymentResponse(deploymentId, serviceId, "v1.0.0", "a921fc7",
                        "First release", deployedAt, deployedAt, true));

        mockMvc.perform(post("/api/services/{serviceId}/deployments", serviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"v1.0.0","commitSha":"a921fc7","description":"First release"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/services/" + serviceId + "/deployments/" + deploymentId))
                .andExpect(jsonPath("$.id").value(deploymentId.toString()))
                .andExpect(jsonPath("$.current").value(true));
    }

    @Test
    void rejectsInvalidDeploymentRequestWithStructuredViolations() throws Exception {
        UUID serviceId = UUID.randomUUID();

        mockMvc.perform(post("/api/services/{serviceId}/deployments", serviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":" ","commitSha":"bad!","description":"valid description"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.violations.length()").value(2));
    }

    @Test
    void reportsMissingService() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(deploymentService.findAll(serviceId, 0, 20))
                .thenThrow(new ServiceNotFoundException(serviceId));

        mockMvc.perform(get("/api/services/{serviceId}/deployments", serviceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void reportsDeploymentFromAnotherServiceAsNotFound() throws Exception {
        UUID serviceId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(deploymentService.findById(serviceId, deploymentId))
                .thenThrow(new DeploymentNotFoundException(serviceId, deploymentId));

        mockMvc.perform(get("/api/services/{serviceId}/deployments/{deploymentId}", serviceId, deploymentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
