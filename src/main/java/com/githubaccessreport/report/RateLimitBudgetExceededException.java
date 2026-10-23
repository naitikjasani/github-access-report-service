package com.githubaccessreport.report;

import java.time.Instant;

/** Refused before fanning out: the remaining quota cannot cover the calls this report needs. */
public class RateLimitBudgetExceededException extends RuntimeException {

    private final long required;
    private final long remaining;
    private final Instant resetAt;

    public RateLimitBudgetExceededException(long required, long remaining, Instant resetAt) {
        super("Report needs about %d GitHub requests but only %d remain until %s"
                .formatted(required, remaining, resetAt));
        this.required = required;
        this.remaining = remaining;
        this.resetAt = resetAt;
    }

    public long required() {
        return required;
    }

    public long remaining() {
        return remaining;
    }

    public Instant resetAt() {
        return resetAt;
    }
}
