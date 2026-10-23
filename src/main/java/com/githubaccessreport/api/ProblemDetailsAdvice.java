package com.githubaccessreport.api;

import java.net.URI;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an RFC 9457 problem ({@code application/problem+json}) via {@link
 * Problems}. Extending {@link ResponseEntityExceptionHandler} keeps Spring's own 4xx responses
 * (unknown path, bad parameter, wrong method) as 4xx instead of a catch-all 500.
 *
 * <p>Upstream failures are 502/503/504, never 401: a bad GitHub token is the server's problem, not
 * the caller's.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private final Problems problems;

    public ProblemDetailsAdvice(Problems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handle(Exception e) {
        return problems.from(e);
    }

    /** Name the offending parameter instead of Spring's generic "Validation failure". */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getParameterValidationResults().stream()
                .map(result -> result.getMethodParameter().getParameterName() + " "
                        + result.getResolvableErrors().stream()
                                .map(MessageSourceResolvable::getDefaultMessage)
                                .collect(Collectors.joining(", ")))
                .collect(Collectors.joining("; "));
        ProblemDetail body = ex.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        body.setDetail(detail);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Give Spring's built-in problems (400, 404, 405, ...) the same {@code code} and {@code type} shape. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                String code = switch (statusCode.value()) {
                    case 400 -> "invalid-request";
                    case 404 -> "not-found";
                    case 405 -> "method-not-allowed";
                    case 406 -> "not-acceptable";
                    case 415 -> "unsupported-media-type";
                    default -> "http-" + statusCode.value();
                };
                problem.setType(URI.create(Problems.DOCS + code));
                problem.setProperty("code", code);
            }
            problems.withTraceId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}
