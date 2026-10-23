package com.githubaccessreport.config;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class GitHubClientConfig {

    static final String GITHUB_API_VERSION = "2022-11-28";

    /**
     * Built from Boot's auto-configured builder so every call is observed by Micrometer, on the JDK
     * HttpClient: blocking sends are cheap on virtual threads and abort when the thread is
     * interrupted.
     */
    @Bean
    public RestClient gitHubRestClient(RestClient.Builder builder, GitHubProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
