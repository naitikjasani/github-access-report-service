# ADR-0004: REST and a personal token now; GraphQL and GitHub App as next steps

**Status:** accepted

## Context

Two big levers would improve large-organization behaviour. Neither is free.

### GraphQL instead of N+1 REST calls

A single GraphQL query can page through `organization.repositories` with nested `collaborators`,
about 100 repositories per round trip instead of one REST call per repository. Its collaborator
edges also expose `permissionSources`, which can say whether access comes from a team, a direct
grant or the organization's base permission. REST can't tell team access from direct access
cheaply; the current report labels members only as `MEMBER`.

### GitHub App instead of a personal access token

An App installation gets its own rate limit, which scales with the organization's size; a token
has 5,000 requests per hour per user. It also gets short-lived installation tokens, and access is
not tied to one person's account. The cost:
- a private key to protect
- JWT signing
- an installation-token refresh loop

## Decision

Keep REST and a fine-grained personal token for now. The port is designed so either lever can be
added without touching the report logic:

- `OrgAccessSource.fetch(org, deadline)` is defined at the level of the whole use case, not per
  endpoint, so a `GraphQlOrgAccessSource` could batch freely.
- Authentication is confined to how the `RestClient` bean is built (`GitHubClientConfig`), so an
  installation-token provider would plug in there.

## Consequences

- This version makes no claims about team-versus-direct provenance. The README lists it as a
  known gap.
- **GraphQL adapter, when added:** it should be measured on the same fixtures for request count
  (`LargeOrganizationIT`) and rate-limit cost (`rateLimit { cost }` vs. the REST
  `x-ratelimit-used` delta). Only measured numbers should be published.
- Both changes need a real organization and token to validate the response shapes. That is why
  they were not built against hand-written fixtures alone.
