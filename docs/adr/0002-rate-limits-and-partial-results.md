# ADR-0002: Rate-limit handling and the partial-results contract

**Status:** accepted

## Context

GitHub signals several different conditions with an HTTP 403:

| Condition | How GitHub says it | Right reaction |
|---|---|---|
| Primary rate limit (hourly quota) | 403/429, `x-ratelimit-remaining: 0`, `x-ratelimit-reset` | stop; nothing works until reset |
| Secondary rate limit (too fast) | 403/429 with `retry-after`, or a body mentioning a secondary rate limit (then wait ≥ 60 s) | pause, then retry |
| Token lacks access to one repository | plain 403 | this repository can't be read |

The first version treated every non-quota 403 on a collaborators call as "no permission" and
returned an empty list. Under load, secondary limits therefore made repositories silently vanish
from the report, and the incomplete report was cached with no expiry. For an access-review tool
that is the worst possible failure: a confident, wrong answer.

The REST API costs `ceil(repos / 100) + 2 + repos` requests per report (1,214 for 1,200
repositories, asserted by `LargeOrganizationIT`). At concurrency 8 and about 200 ms per call that
is roughly 2,400 requests a minute, above GitHub's documented secondary limit of about 900 REST
points per minute. So secondary limits are an expected event for large organizations, not an edge
case.

## Decision

`GitHubErrorClassifier` checks the conditions in the order of the table above. Each one maps to a
deliberate outcome:

- **Transient** (5xx, timeout, I/O, connection dropped mid-body): up to 2 retries with exponential
  backoff (200 ms, ×2, max 2 s) plus jitter, using Spring Framework 7's `RetryTemplate`. After that,
  502.
- **Secondary limit:** wait `Retry-After` plus jitter if it is at most `github.max-retry-after`
  (10 s), at most twice. Otherwise 503 with `Retry-After`.
- **Primary limit:** fail fast with 429 and `Retry-After` set to the reset time.
- **Budget check:** after listing repositories, if the remaining quota is below what the report
  needs, refuse with 429 before fanning out.
- **Repository-level 403 or 404:** this is a *deterministic* gap. The repository is listed in
  `coverage.skippedRepositories` with a reason, and the report is marked `coverage.complete=false`.
- **Caching rule:** reports with deterministic gaps are cached (rebuilding won't change them).
  Failures are never cached.

## Consequences

**Gains:**
- No silent data loss. A consumer can always tell what the report could not see.
- Each row of the table has a WireMock test (`GitHubHttpTest`, `AccessReportIT`, `RateLimitBudgetIT`).
- `github.ratelimit.hits{type}`, `github.ratelimit.secondary.waits`, `github.retries` and the
  `github.ratelimit.remaining` gauge make the behaviour visible in production.

**Given up:**
- No proactive pacing to 900 points per minute. We rely on the concurrency limit plus honouring
  `Retry-After`.
- The budget check is conservative. It assumes every call costs quota, but with ETag revalidation
  (ADR-0003) many calls on a rebuild are free 304s, so it can refuse a rebuild that would have
  succeeded.

**Would revisit if:** `github.ratelimit.hits{type="secondary"}` is regularly non-zero; then add a
token bucket in `GitHubHttp`.
