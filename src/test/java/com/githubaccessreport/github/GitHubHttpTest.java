package com.githubaccessreport.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.githubaccessreport.github.wire.RepositoryJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.core.ParameterizedTypeReference;

class GitHubHttpTest {

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final ParameterizedTypeReference<List<RepositoryJson>> REPOS = new ParameterizedTypeReference<>() {};
    private static final String REPOS_PATH = "/orgs/acme/repos";

    private RateLimitTracker tracker;
    private SimpleMeterRegistry metrics;
    private GitHubHttp http;

    @BeforeEach
    void setUp() {
        tracker = new RateLimitTracker();
        metrics = new SimpleMeterRegistry();
        http = GitHubTestSupport.http(
                GitHubTestSupport.properties(github.baseUrl(), 4, Duration.ofMillis(500)), tracker, metrics);
    }

    @Test
    void followsLinkHeaderAcrossPages() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .withQueryParam("page", equalTo("1"))
                .willReturn(okJson(repos(1, 100))
                        .withHeader(
                                "Link", "<" + github.baseUrl() + REPOS_PATH + "?per_page=100&page=2>; rel=\"next\"")));
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .withQueryParam("page", equalTo("2"))
                .willReturn(okJson(repos(101, 30))));

        List<RepositoryJson> result = http.getAllPages(REPOS_PATH, REPOS);

        assertThat(result).hasSize(130);
        assertThat(result.getLast().name()).isEqualTo("repo-130");
        github.verify(2, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
    }

    @Test
    void exactlyOneFullPageNeedsOneRequest() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(okJson(repos(1, 100))));

        assertThat(http.getAllPages(REPOS_PATH, REPOS)).hasSize(100);
        github.verify(1, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
    }

    @Test
    void sendsTokenAndApiVersionHeaders() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(okJson("[]")));

        http.getAllPages(REPOS_PATH, REPOS);

        github.verify(getRequestedFor(urlPathEqualTo(REPOS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + GitHubTestSupport.TOKEN))
                .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28"))
                .withHeader("Accept", equalTo("application/vnd.github+json")));
    }

    @Test
    void retriesTransientServerErrors() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(502))
                .willSetStateTo("recovered"));
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(repos(1, 3))));

        assertThat(http.getAllPages(REPOS_PATH, REPOS)).hasSize(3);
        github.verify(2, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
        assertThat(metrics.counter("github.retries").count()).isEqualTo(1);
    }

    @Test
    void givesUpAfterMaxRetries() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> http.getAllPages(REPOS_PATH, REPOS)).isInstanceOf(GitHubException.Unavailable.class);
        github.verify(3, getRequestedFor(urlPathEqualTo(REPOS_PATH))); // 1 attempt + 2 retries
    }

    @Test
    void readTimeoutIsTransientAndBounded() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(okJson("[]").withFixedDelay(2_000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> http.getAllPages(REPOS_PATH, REPOS)).isInstanceOf(GitHubException.Unavailable.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 3 attempts x 500 ms read timeout + backoff (~200 + ~400 ms, +/- jitter): well under 2 s each.
        assertThat(elapsed).isLessThan(Duration.ofMillis(3_500));
    }

    @Test
    void primaryRateLimitFailsFastWithoutRetrying() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("x-ratelimit-limit", "5000")
                        .withHeader("x-ratelimit-remaining", "0")
                        .withHeader("x-ratelimit-reset", "1900000000")
                        .withBody("{\"message\":\"API rate limit exceeded\"}")));

        assertThatThrownBy(() -> http.getAllPages(REPOS_PATH, REPOS))
                .isInstanceOfSatisfying(
                        GitHubException.PrimaryRateLimitExceeded.class,
                        e -> assertThat(e.resetAt()).isEqualTo(Instant.ofEpochSecond(1_900_000_000)));
        github.verify(1, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
        assertThat(tracker.latest("core"))
                .hasValueSatisfying(s -> assertThat(s.remaining()).isZero());
        assertThat(metrics.counter("github.ratelimit.hits", "type", "primary").count())
                .isEqualTo(1);
    }

    @Test
    void waitsForShortSecondaryLimitThenSucceeds() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .inScenario("secondary")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Retry-After", "1")
                        .withBody("{\"message\":\"You have exceeded a secondary rate limit\"}"))
                .willSetStateTo("cooled-down"));
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .inScenario("secondary")
                .whenScenarioStateIs("cooled-down")
                .willReturn(okJson(repos(1, 2))));

        long start = System.nanoTime();
        assertThat(http.getAllPages(REPOS_PATH, REPOS)).hasSize(2);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        github.verify(2, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
        assertThat(metrics.counter("github.ratelimit.secondary.waits").count()).isEqualTo(1);
    }

    @Test
    void longSecondaryLimitIsNotWaitedOut() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));

        assertThatThrownBy(() -> http.getAllPages(REPOS_PATH, REPOS))
                .isInstanceOfSatisfying(
                        GitHubException.SecondaryRateLimitExceeded.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(120)));
        github.verify(1, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
    }

    @Test
    void permissionErrorsAreNotRetried() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("x-ratelimit-remaining", "4000")
                        .withBody("{\"message\":\"Must have push access\"}")));

        assertThatThrownBy(() -> http.getAllPages(REPOS_PATH, REPOS)).isInstanceOf(GitHubException.Forbidden.class);
        github.verify(1, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
    }

    @Test
    void unauthorizedAndNotFoundAreDistinct() {
        github.stubFor(get(urlPathEqualTo("/orgs/bad-token/repos"))
                .willReturn(aResponse().withStatus(401)));
        github.stubFor(get(urlPathEqualTo("/orgs/missing/repos"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> http.getAllPages("/orgs/{org}/repos", REPOS, "bad-token"))
                .isInstanceOf(GitHubException.Unauthorized.class);
        assertThatThrownBy(() -> http.getAllPages("/orgs/{org}/repos", REPOS, "missing"))
                .isInstanceOf(GitHubException.NotFound.class);
    }

    @Test
    void toleratesMissingOptionalFields() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(okJson("[{\"id\": 7, \"name\": \"minimal\"}]")));

        assertThat(http.getAllPages(REPOS_PATH, REPOS))
                .singleElement()
                .satisfies(repo -> assertThat(repo.archivedRepository()).isFalse());
    }

    @Test
    void unreadableBodyIsAnUnexpectedResponseNotRetried() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(okJson("{\"not\": \"an array\"")));

        assertThatThrownBy(() -> http.getAllPages(REPOS_PATH, REPOS))
                .isInstanceOf(GitHubException.UnexpectedResponse.class);
        github.verify(1, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
    }

    @Test
    void connectionDroppedMidResponseIsRetried() {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .inScenario("drop")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE))
                .willSetStateTo("stable"));
        github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                .inScenario("drop")
                .whenScenarioStateIs("stable")
                .willReturn(okJson(repos(1, 1))));

        assertThat(http.getAllPages(REPOS_PATH, REPOS)).hasSize(1);
        github.verify(2, getRequestedFor(urlPathEqualTo(REPOS_PATH)));
    }

    static String repos(int firstId, int count) {
        return IntStream.range(firstId, firstId + count)
                .mapToObj(i -> """
                        {"id": %d, "name": "repo-%d", "full_name": "acme/repo-%d", "private": false,
                         "visibility": "public", "archived": false, "fork": false, "pushed_at": "2026-01-01T00:00:00Z"}""".formatted(i, i, i))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
