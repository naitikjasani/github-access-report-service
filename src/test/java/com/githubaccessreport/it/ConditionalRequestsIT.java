package com.githubaccessreport.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * After the report cache expires, rebuilding an unchanged organization revalidates every page with
 * {@code If-None-Match}: GitHub answers 304 (no quota used) and the report is identical.
 */
@SpringBootTest(properties = "report.cache-ttl=1s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
class ConditionalRequestsIT {

    @RegisterExtension
    static WireMockExtension github = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void githubBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("github.base-url", github::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void rebuildOfUnchangedOrganizationIsAllNotModified() throws Exception {
        Map<String, String> endpoints = Map.of(
                "/orgs/etag/repos", "[{\"id\": 1, \"name\": \"one\"}, {\"id\": 2, \"name\": \"two\"}]",
                "/orgs/etag/members", "[{\"id\": 10, \"login\": \"owner\"}]",
                "/orgs/etag/outside_collaborators", "[]",
                "/repos/etag/one/collaborators", "[{\"id\": 10, \"login\": \"owner\", \"role_name\": \"admin\"}]",
                "/repos/etag/two/collaborators", "[{\"id\": 11, \"login\": \"dev\", \"role_name\": \"write\"}]");
        endpoints.forEach((path, body) -> {
            String etag = "W/\"" + path.hashCode() + "\"";
            github.stubFor(
                    WireMock.get(urlPathEqualTo(path)).willReturn(okJson(body).withHeader("ETag", etag)));
            github.stubFor(WireMock.get(urlPathEqualTo(path))
                    .withHeader("If-None-Match", equalTo(etag))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(304)));
        });

        String first = mvc.perform(get("/api/v1/orgs/etag/access/users"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Thread.sleep(1_500); // let the report cache expire
        String second = mvc.perform(get("/api/v1/orgs/etag/access/users"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Integer> statuses = github.getAllServeEvents().stream()
                .map(ServeEvent::getResponse)
                .map(response -> response.getStatus())
                .toList();
        assertThat(statuses).hasSize(10);
        assertThat(statuses).filteredOn(s -> s == 304).hasSize(5); // the whole rebuild
        assertThat(withoutTimestamps(second)).isEqualTo(withoutTimestamps(first));
        github.verify(5, WireMock.getRequestedFor(anyUrl()).withHeader("If-None-Match", WireMock.matching(".+")));
    }

    private static String withoutTimestamps(String json) {
        return json.replaceAll("\"generatedAt\":\"[^\"]+\"", "").replaceAll("\"ageSeconds\":\\d+", "");
    }
}
