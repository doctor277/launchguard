package io.github.doctor277.launchguard.dto;

import java.util.UUID;

public record AsyncCheckResponse(UUID requestId, UUID serviceId, String status) {
}
