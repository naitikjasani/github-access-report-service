package com.githubaccessreport.api;

import com.githubaccessreport.config.ReportProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Optional shared-secret protection for {@code /api/**}. Off unless {@code report.api-key} is set;
 * health, metrics and the OpenAPI docs stay open. A real multi-user deployment would sit behind an
 * identity-aware proxy instead; the allowlist is the primary control for the public demo.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final ReportProperties properties;
    private final JsonMapper jsonMapper;
    private final TraceIds traceIds;

    public ApiKeyFilter(ReportProperties properties, JsonMapper jsonMapper, TraceIds traceIds) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.traceIds = traceIds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.requiresApiKey() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Send the API key in the " + HEADER + " header.");
        problem.setType(URI.create(Problems.DOCS + "unauthorized"));
        problem.setTitle("Missing or invalid API key");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "unauthorized");
        traceIds.current().ifPresent(id -> problem.setProperty("traceId", id));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("WWW-Authenticate", "ApiKey header=\"" + HEADER + "\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }

    /** Constant-time comparison, so response timing does not reveal how much of the key matched. */
    private boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), properties.apiKey().getBytes(StandardCharsets.UTF_8));
    }
}
