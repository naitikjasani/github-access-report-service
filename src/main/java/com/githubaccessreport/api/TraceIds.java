package com.githubaccessreport.api;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The current request's trace id, the same one that appears in every log line for that request. */
@Component
public class TraceIds {

    private final ObjectProvider<Tracer> tracer;

    public TraceIds(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public Optional<String> current() {
        return Optional.ofNullable(tracer.getIfAvailable())
                .map(Tracer::currentSpan)
                .map(Span::context)
                .map(context -> context.traceId())
                .filter(id -> !id.isBlank() && !id.chars().allMatch(c -> c == '0'));
    }
}
