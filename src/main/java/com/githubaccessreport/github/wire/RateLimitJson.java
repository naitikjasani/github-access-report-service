package com.githubaccessreport.github.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** {@code GET /rate_limit}: current quota per resource. Calling it does not count against the limit. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RateLimitJson(Map<String, Resource> resources) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resource(long limit, long remaining, long used, long reset) {}
}
