package com.githubaccessreport.github.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * A repository collaborator. {@code role_name} is the effective role, including custom roles;
 * {@code permissions} holds the base-level flags (admin, maintain, push, triage, pull).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollaboratorJson(
        long id,
        String login,
        @JsonProperty("role_name") String roleName,
        Map<String, Boolean> permissions) {}
