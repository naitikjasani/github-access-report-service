package com.githubaccessreport.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-process Caffeine cache: one instance, cheap-to-rebuild data and no cross-instance sharing, so
 * Redis would add a network hop and a moving part for no benefit (see docs/adr/0003).
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

    public static final String ACCESS_REPORTS = "accessReports";

    @Bean
    public CacheManager cacheManager(ReportProperties properties) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                // Access data must not be served indefinitely: a revoked admin should drop off within one TTL.
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.cacheMaxSize())
                .recordStats());
        // Declared up front (not created on demand) so cache metrics are registered at startup.
        cacheManager.setCacheNames(List.of(ACCESS_REPORTS));
        return cacheManager;
    }
}
