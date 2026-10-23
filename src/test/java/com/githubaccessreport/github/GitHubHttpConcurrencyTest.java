package com.githubaccessreport.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.githubaccessreport.github.wire.UserJson;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Proves the shared concurrency limit holds: a server that counts requests in flight sees at most
 * {@code max-concurrency} at once, and more than one (calls really do run in parallel).
 */
class GitHubHttpConcurrencyTest {

    private static final int MAX_CONCURRENCY = 4;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
                byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void neverExceedsConfiguredConcurrency() throws Exception {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        GitHubHttp http = GitHubTestSupport.http(
                GitHubTestSupport.properties(baseUrl, MAX_CONCURRENCY, Duration.ofSeconds(5)), new RateLimitTracker());
        var type = new ParameterizedTypeReference<List<UserJson>>() {};

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<UserJson>>> calls = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int repo = i;
                calls.add(callers.submit(() -> http.getAllPages("/repos/acme/r{n}/collaborators", type, repo)));
            }
            for (Future<List<UserJson>> call : calls) {
                call.get();
            }
        }

        assertThat(maxInFlight.get()).isLessThanOrEqualTo(MAX_CONCURRENCY).isGreaterThan(1);
    }
}
