package com.gateway.shared.security;

/**
 * A registered internal service caller: its logical identifier and the pre-shared secret it must
 * present on every internal API call.
 *
 * @param id the service identifier sent in {@code X-Caller-Service}
 * @param secret the secret value sent in {@code X-Internal-Token}
 */
public record CallerConfig(String id, String secret) {}
