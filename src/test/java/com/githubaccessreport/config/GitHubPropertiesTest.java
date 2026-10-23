package com.githubaccessreport.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class GitHubPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "github.base-url=https://api.github.com",
                    "github.connect-timeout=5s",
                    "github.read-timeout=15s",
                    "github.max-concurrency=8",
                    "github.max-retries=2",
                    "github.max-retry-after=10s");

    @Test
    void startupFailsWhenTokenIsMissing() {
        contextRunner
                .withPropertyValues("github.token=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("github")
                        .hasMessageContaining("token"));
    }

    @Test
    void startupSucceedsWithToken() {
        contextRunner
                .withPropertyValues("github.token=abc")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(GitHubProperties.class));
    }

    @Test
    void toStringNeverContainsTheToken() {
        var properties = new GitHubProperties(
                "ghp_super_secret_value",
                URI.create("https://api.github.com"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                8,
                2,
                Duration.ofSeconds(10));

        assertThat(properties.toString())
                .doesNotContain("ghp_super_secret_value")
                .contains("token=****");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GitHubProperties.class)
    static class PropertiesConfig {}
}
