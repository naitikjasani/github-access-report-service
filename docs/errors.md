# Error reference

Every error is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem document
(`Content-Type: application/problem+json`):

```json
{
  "type": "https://github.com/naitikjasani/github-access-report-service/blob/main/docs/errors.md#rate-limited",
  "title": "GitHub rate limit exhausted",
  "status": 429,
  "detail": "The service's GitHub quota is used up until 2026-10-23T14:00:00Z.",
  "instance": "/api/v1/orgs/acme/access/users",
  "code": "rate-limited",
  "traceId": "6ad2e498e8aea8d0e0073aceb6991759",
  "resetAt": "2026-10-23T14:00:00Z"
}
```

Branch on `code`, which is stable. Quote `traceId` when reporting a problem: it is also returned as
the `X-Trace-Id` header and appears on every server log line for the request.

Upstream failures are never returned as 401: a rejected GitHub token is the service's
misconfiguration, not the caller's.

## Request errors

### `invalid-request`
**400.** A path or query parameter is invalid: an organization name that breaks GitHub's login
rules, `size` outside 1–200, or an unknown `minPermission` or `affiliation` value. `detail` names
the parameter.

### `unauthorized`
**401.** This deployment requires an API key (`report.api-key`) and the `X-API-Key` header was
missing or wrong.

### `organization-not-allowed`
**403.** This deployment only reports on the organizations in `report.allowed-orgs`.

### `not-found`
**404.** No such endpoint.

### `organization-not-found`
**404.** GitHub returned 404 for the organization: it doesn't exist, or the service token cannot see
it.

### `report-job-not-found`
**404.** No job with that id. Jobs are kept in memory for one hour and don't survive restarts.

### `method-not-allowed`
**405.**

### `not-acceptable`
**406.** The requested `Accept` type isn't offered.

### `unsupported-media-type`
**415.** `POST /api/v1/reports` expects `Content-Type: application/json`.

## GitHub quota

### `rate-limited`
**429** + `Retry-After`. The service's hourly GitHub quota is used up. `resetAt` says when it
refills. The service fails fast instead of holding your request for up to an hour.

### `rate-limit-budget`
**429** + `Retry-After`. The remaining quota (`remainingRequests`) is lower than the report needs
(`requiredRequests` = repositories + 2). The request is refused *before* any per-repository call,
so the quota isn't burned on a report that couldn't finish.

### `upstream-throttled`
**503** + `Retry-After`. GitHub's secondary (abuse) rate limit asked for a pause longer than the
service is willing to wait inside a request (`github.max-retry-after`, default 10 s). Shorter
pauses are waited out automatically.

## GitHub failures

### `upstream-auth-failed`
**502.** GitHub rejected the service's token (expired, revoked or wrong). Operators: check
`GITHUB_TOKEN`.

### `upstream-forbidden`
**502.** GitHub denied organization-level access to the service token.

### `upstream-unavailable`
**502.** GitHub kept failing (5xx, timeouts, dropped connections) after 2 retries with exponential
backoff and jitter, or returned a payload the service could not parse.

### `report-timeout`
**504.** The report didn't finish within `report.sync-deadline` (default 45 s). Outstanding GitHub
calls are cancelled when this happens. For large organizations, start an asynchronous job with
`POST /api/v1/reports` (deadline `report.async-deadline`, default 10 min). A failed job carries the
same problem in its `problem` field.

## Other

### `internal-error`
**500.** A bug. The response carries no details; the `traceId` finds the stack trace in the logs.
