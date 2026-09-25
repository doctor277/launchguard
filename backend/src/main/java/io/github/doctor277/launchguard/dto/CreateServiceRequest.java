package io.github.doctor277.launchguard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateServiceRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 2048)
        @Pattern(regexp = "https?://.+", message = "must be an absolute HTTP or HTTPS URL") String baseUrl,
        @NotBlank @Size(max = 1024)
        @Pattern(regexp = "/.*", message = "must start with '/'") String healthPath) {
}
