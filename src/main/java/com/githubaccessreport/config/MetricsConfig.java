package com.githubaccessreport.config;

import com.githubaccessreport.github.RateLimitTracker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    /** Latest quota GitHub reported on any response; NaN until the first call. */
    @Bean
    MeterBinder gitHubRateLimitMetrics(RateLimitTracker tracker) {
        return registry -> {
            Gauge.builder(
                            "github.ratelimit.remaining",
                            tracker,
                            t -> t.latest("core")
                                    .map(s -> (double) s.remaining())
                                    .orElse(Double.NaN))
                    .tag("resource", "core")
                    .description("Requests left in the current GitHub rate-limit window")
                    .register(registry);
            Gauge.builder(
                            "github.ratelimit.limit",
                            tracker,
                            t -> t.latest("core").map(s -> (double) s.limit()).orElse(Double.NaN))
                    .tag("resource", "core")
                    .register(registry);
        };
    }
}
