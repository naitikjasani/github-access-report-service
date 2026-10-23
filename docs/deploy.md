# Deploying the public demo (Render, free tier)

The demo is safe to leave public because of three controls, all enforced server-side:

1. **The token can only see one throwaway organization.** A fine-grained token is scoped to a
   single resource owner and is read-only.
2. **The allowlist.** `REPORT_ALLOWED_ORGS` rejects every other organization with a 403 before any
   GitHub call is made.
3. **The cache bounds GitHub load.** With one allowed organization and `sync=true` caching, at most
   one report is built per TTL (10 minutes), however much traffic arrives. For an organization with
   R repositories that is at most `6 × (R + 3)` GitHub requests per hour.

## 1. Create the demo organization

1. Create a free organization, for example `<you>-access-demo`.
2. Add about six repositories: one archived, one fork, one private, the rest public.
3. Create a team and give it write access to a couple of repositories.
4. Add an outside collaborator on one repository. Use a consenting friend's account or a
   [machine account](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service#3-account-requirements).

## 2. Create a fine-grained token

At Settings → Developer settings → Fine-grained tokens:

- **Resource owner:** the demo organization (only).
- **Repository access:** all repositories.
- **Repository permissions:** *Metadata: Read-only*. Listing collaborators also requires that the
  token's user has write access or higher on each repository, which an org owner does.
- **Organization permissions:** *Members: Read-only* (owners and outside collaborators).
- **Expiration:** the shortest you're happy rotating.

Before deploying, check the token locally:

```bash
GITHUB_TOKEN=github_pat_... REPORT_ALLOWED_ORGS=<you>-access-demo docker compose up --build
curl -s localhost:8080/api/v1/orgs/<you>-access-demo/access/users | jq '.coverage'
```

`coverage.complete` should be `true`. If it isn't, `skippedRepositories` and `affiliationsKnown` say
which permission is missing.

## 3. Deploy on Render

1. Render dashboard → **New → Blueprint** → select this repository. It reads `render.yaml`.
2. Enter `GITHUB_TOKEN` and `REPORT_ALLOWED_ORGS` when prompted.
3. Render builds the Dockerfile and checks `/actuator/health/readiness`. `PORT` is provided by
   Render and picked up through `server.port: ${PORT:8080}`.
4. Under Settings, confirm auto-deploy is set to deploy after checks pass.

## 4. After deploying

- Visit `https://<service>.onrender.com/swagger-ui/index.html` and
  `/api/v1/orgs/<you>-access-demo/access/users`.
- Confirm another organization returns `403 organization-not-allowed`.
- Free instances spin down when idle, so measure the first request after a pause with
  `curl -o /dev/null -s -w '%{time_total}\n' https://<service>.onrender.com/actuator/health/liveness`
  and record it in the README's *Limits* table.
- Add the demo URL to the README header.
