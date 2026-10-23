package com.githubaccessreport.github;

import com.githubaccessreport.config.GitHubClientConfig;
import com.githubaccessreport.config.GitHubProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.web.client.RestClient;

/** Builds the real transport stack against a local stub server, without a Spring context. */
final class GitHubTestSupport {

    static final String TOKEN = "test-token-never-real";

    private GitHubTestSupport() {}

    static GitHubProperties properties(String baseUrl, int maxConcurrency, Duration readTimeout) {
        return new GitHubProperties(TOKEN, URI.create(baseUrl), Duration.ofSeconds(1), readTimeout,
                maxConcurrency, 2, Duration.ofSeconds(5));
    }

    static GitHubHttp http(GitHubProperties properties, RateLimitTracker tracker) {
        RestClient restClient = new GitHubClientConfig().gitHubRestClient(RestClient.builder(), properties);
        return new GitHubHttp(restClient, properties, tracker, Clock.systemUTC());
    }
}
