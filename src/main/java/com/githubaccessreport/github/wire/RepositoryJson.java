package com.githubaccessreport.github.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * The subset of GitHub's repository payload this service uses. Flags are boxed so a field GitHub
 * omits reads as "false" instead of failing the whole page (Jackson 3 rejects null primitives).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepositoryJson(
        long id,
        String name,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("private") Boolean isPrivate,
        String visibility,
        Boolean archived,
        Boolean fork,
        @JsonProperty("pushed_at") Instant pushedAt) {

    public boolean privateRepository() {
        return Boolean.TRUE.equals(isPrivate);
    }

    public boolean archivedRepository() {
        return Boolean.TRUE.equals(archived);
    }

    public boolean forkRepository() {
        return Boolean.TRUE.equals(fork);
    }
}
