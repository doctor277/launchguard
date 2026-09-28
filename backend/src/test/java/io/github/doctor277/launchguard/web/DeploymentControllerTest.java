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
import io.github.doctor277.launchguard.dto.DeploymentRegistration;
import io.github.doctor277.launchguard.domain.DeploymentSource;
import io.github.doctor277.launchguard.service.DeploymentExternalIdConflictException;
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
        when(deploymentService.createOrReplay(org.mockito.ArgumentMatchers.eq(serviceId), any(CreateDeploymentRequest.class)))
                .thenReturn(new DeploymentRegistration(new DeploymentResponse(deploymentId, serviceId, "v1.0.0", "a921fc7",
                        "First release", deployedAt, deployedAt, true), true));

        mockMvc.perform(post("/api/services/{serviceId}/deployments", serviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"v1.0.0","commitSha":"a921fc7","description":"First release"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/services/" + serviceId + "/deployments/" + deploymentId))
                .andExpect(jsonPath("$.id").value(deploymentId.toString()))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.source").value("MANUAL"));

        org.mockito.Mockito.verify(deploymentService).createOrReplay(org.mockito.ArgumentMatchers.eq(serviceId),
                org.mockito.ArgumentMatchers.argThat(request -> request.source() == DeploymentSource.MANUAL
                        && request.externalId() == null));
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
    void identicalCiReplayReturns200AndMetadata() throws Exception {
        UUID serviceId = UUID.randomUUID();
        Instant now = Instant.now();
        var response = new DeploymentResponse(UUID.randomUUID(), serviceId, "v1", "a921fc7", null, now, now,
                true, DeploymentSource.CI, "local", "sha-a921fc7", "run-1");
        when(deploymentService.createOrReplay(org.mockito.ArgumentMatchers.eq(serviceId), any()))
                .thenReturn(new DeploymentRegistration(response, false));
        mockMvc.perform(post("/api/services/{serviceId}/deployments", serviceId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"version":"v1","commitSha":"a921fc7","source":"CI","environment":"local",
                 "imageTag":"sha-a921fc7","externalId":"run-1"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CI"))
                .andExpect(jsonPath("$.environment").value("local"))
                .andExpect(jsonPath("$.imageTag").value("sha-a921fc7"))
                .andExpect(jsonPath("$.externalId").value("run-1"));
    }

    @Test
    void conflictingReplayReturnsStructured409() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(deploymentService.createOrReplay(org.mockito.ArgumentMatchers.eq(serviceId), any()))
                .thenThrow(new DeploymentExternalIdConflictException());
        mockMvc.perform(post("/api/services/{serviceId}/deployments", serviceId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"version":"v2","externalId":"run-1"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("externalId already identifies a deployment with different metadata for this service"));
    }

    @Test
    void rejectsInvalidCiMetadataAndUnknownSource() throws Exception {
        UUID serviceId = UUID.randomUUID();
        mockMvc.perform(post("/api/services/{serviceId}/deployments", serviceId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"version":"v1","environment":" ","externalId":" ","imageTag":" "}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.length()").value(3));
        mockMvc.perform(post("/api/services/{serviceId}/deployments", serviceId)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"version":"v1","source":"MAGIC"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
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
