package com.githubaccessreport.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Guard rail: the test profile must never point at the real GitHub API, so a missing WireMock
 * override fails fast (connection refused on 127.0.0.1:1) instead of silently calling GitHub.
 */
class NoLiveGitHubCallsTest {

    @Test
    void testProfileTargetsLocalhostOnly() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-test.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("github.base-url")).startsWith("http://127.0.0.1:");
    }
}
