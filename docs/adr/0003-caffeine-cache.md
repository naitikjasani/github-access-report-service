# ADR-0003: In-process Caffeine cache instead of Redis

**Status:** accepted

## Context

Building a report costs about `repos + 3` GitHub requests and seconds of latency, so repeated
requests must be served from a cache. The first version used `ConcurrentMapCacheManager`, which
has three problems:

- **No TTL.** An access report is security data; serving a revoked admin forever is wrong.
- **No size bound.**
- **No stampede protection.** Ten simultaneous requests for one organization meant ten full
  fan-outs.

## Decision

Use Caffeine through Spring's cache abstraction:
- `expireAfterWrite = report.cache-ttl` (default 10 min)
- `maximumSize = report.cache-max-size` (default 100)
- `recordStats()` for the `cache.gets{result}` metrics
- `@Cacheable(sync = true)`, keyed by the lower-cased organization

The cached value is the immutable, canonical `AccessReport`. Both views, every filter and every
page are computed from it on read, so one GitHub fetch serves all query shapes.

Freshness is visible to callers: every response carries `generatedAt` and `ageSeconds`.

## Consequences

**Measured:**
- `CacheIT`: 10 concurrent requests for one organization cause exactly one GitHub fetch.
- `CacheIT`: entries expire after the TTL.

**A useful side effect: GitHub load is set by the TTL, not by traffic.** With one allow-listed
organization, at most 6 reports per hour are built, whatever the request rate. This is what makes
the public demo safe.

**Below the report cache: conditional requests.** `GitHubHttp` also remembers each URL's `ETag`
and body, and revalidates with `If-None-Match`. When the report cache expires, rebuilding an
unchanged organization costs 304s, which GitHub doesn't count against the primary rate limit for
authorized requests. `ConditionalRequestsIT` shows 5 of 5 rebuild requests answered 304, with an
identical report. The two caches answer different questions:
- The report cache decides how stale an answer may be.
- The ETag cache makes refreshing it cheap.

**Given up:**
- The cache is per instance and lost on restart.

That is acceptable: there is one instance, and the data is cheap to rebuild.

**Would revisit if:** we ran several instances behind a load balancer (each would build its own
copy and multiply GitHub load), or needed reports to survive restarts. That is when Redis, or a
database for report history, earns its operational cost.
