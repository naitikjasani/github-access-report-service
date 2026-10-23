# Recorded GitHub responses

Real responses saved from api.github.com, used by contract tests so the wire DTOs are checked
against GitHub's actual payloads (all ~80 fields, not just the ones we read). CI never calls GitHub.

| File | Source | Recorded |
|------|--------|----------|
| `octo-org-repos.json` | `GET /orgs/octo-org/repos?per_page=3&page=1` (public, unauthenticated) | 2026-10-23 |
| `octo-org-repos.link` | `Link` header of the same response | 2026-10-23 |

Collaborator and membership endpoints need an authenticated token with access to the
organization, so they are not recorded here. To record them for your own demo organization, run
`scripts/record-fixtures.sh <org>` with `GITHUB_TOKEN` set, review the output for anything you
don't want committed, and copy it into this directory.
