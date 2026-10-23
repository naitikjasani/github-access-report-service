# `acme` fixture organization

Hand-written in the shape of GitHub REST API v2022-11-28 responses (fields trimmed to those the
service reads, plus a few it ignores, to prove unknown fields are tolerated).

| Repository    | Notes                                                        |
|---------------|--------------------------------------------------------------|
| `api`         | private; collaborators split over two pages (Link header)    |
| `web`         | public; `bob` has a custom role (`security-reviewer`) = TRIAGE |
| `legacy`      | archived; `bob` is admin                                     |
| `fork-of-lib` | fork                                                         |
| `secret`      | collaborators call returns 403 → reported as skipped         |

`alice` is an owner, `olga` is an outside collaborator, `bob` and `carol` are members.
