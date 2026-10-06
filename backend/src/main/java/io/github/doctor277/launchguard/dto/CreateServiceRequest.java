package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.validation.HttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateServiceRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[^\\p{Cntrl}]+", message = "must not contain control characters") String name,
        @NotBlank @Size(max = 2048) @HttpUrl String baseUrl,
        @NotBlank @Size(max = 1024)
        @Pattern(regexp = "/[^\\p{Cntrl}]*", message = "must start with '/' and contain no control characters")
        String healthPath) {
}
