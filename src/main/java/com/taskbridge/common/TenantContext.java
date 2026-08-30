package com.taskbridge.common;
import java.util.UUID;
public record TenantContext(UUID userId, UUID organisationId, String ipAddress) {}