# ADR-0001: Servlet stack on virtual threads instead of WebFlux

**Status:** accepted

## Context

A report is one HTTP request that fans out to about `repositories + 3` GitHub calls and returns
one JSON document. The first version used Spring MVC *and* WebFlux: WebClient with
`Flux.flatMap(..., 10)` for the fan-out, followed by `.block()` (twice, with no timeout) in the
service. It carried two web stacks, and the README called it "non-blocking", which the code
didn't support.

The real requirements are:
- bounded parallel I/O against GitHub
- per-call timeouts and a per-report deadline
- cancelling outstanding work when that deadline passes

## Decision

- Use Spring MVC on Java 25 virtual threads (`spring.threads.virtual.enabled=true`) with a blocking
  `RestClient` on the JDK `HttpClient`.
- For each report, submit one task per repository to a virtual-thread-per-task executor.
- Bound concurrency with **one semaphore shared by all reports** (`github.max-concurrency`,
  default 8) inside the transport, so two concurrent reports cannot add up past GitHub's secondary
  limits.
- On deadline or the first fatal error, call `shutdownNow()`. This interrupts the tasks, and the
  JDK client aborts in-flight exchanges when interrupted.
- Remove WebFlux and Reactor.

## Consequences

**Gains:**
- Plain imperative code, readable stack traces, `try`/`catch` error handling.
- `@Cacheable(sync = true)` works without reactive cache adapters.

**Measured:**
- `GitHubHttpConcurrencyTest` asserts that a server counting in-flight requests never sees more
  than the limit, and sees more than one.
- `DeadlineIT` asserts the caller gets 504 at the deadline (under 2 s for a 1 s deadline, while
  calls take 3 s), and that queued calls never start.

**Why Java 25 rather than 21:** JEP 491 (Java 24) stopped virtual threads pinning their carrier
inside `synchronized`, which removes the main virtual-thread pitfall.

**Given up:**
- Streaming responses.
- End-to-end backpressure to the caller.

Neither is needed for a single bounded document. For very large organizations, an asynchronous
job API is the answer rather than streaming.

**Would revisit if:** the service had to hold thousands of concurrent long-lived client
connections, or stream partial results.
