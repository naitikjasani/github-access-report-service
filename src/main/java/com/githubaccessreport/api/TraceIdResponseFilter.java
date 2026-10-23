package com.githubaccessreport.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Echoes the trace id as {@code X-Trace-Id} so a caller can quote it and we can find the logs. */
@Component
public class TraceIdResponseFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Trace-Id";

    private final TraceIds traceIds;

    public TraceIdResponseFilter(TraceIds traceIds) {
        this.traceIds = traceIds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        traceIds.current().ifPresent(id -> response.setHeader(HEADER, id));
        chain.doFilter(request, response);
    }
}
