package com.githubaccessreport.github.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** The subset of GitHub's repository payload this service uses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepositoryJson(
        long id,
        String name,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("private") boolean isPrivate,
        String visibility,
        boolean archived,
        boolean fork,
        @JsonProperty("pushed_at") Instant pushedAt) {}
