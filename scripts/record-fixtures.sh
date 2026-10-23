#!/usr/bin/env bash
# Records real GitHub responses for one organization into ./recorded-<org>/ for use as test
# fixtures. Manual only: needs GITHUB_TOKEN and is never run in CI. Review before committing.
set -euo pipefail

org="${1:?usage: $0 <org>}"
: "${GITHUB_TOKEN:?set GITHUB_TOKEN to a read-only token for $org}"
out="recorded-${org}"
mkdir -p "$out"

gh_get() {
  curl --fail-with-body -sS \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -D "$2.headers" -o "$2" "https://api.github.com$1"
}

gh_get "/orgs/${org}/repos?type=all&per_page=100&page=1" "$out/repos.json"
gh_get "/orgs/${org}/members?role=admin&per_page=100" "$out/owners.json"
gh_get "/orgs/${org}/outside_collaborators?per_page=100" "$out/outside_collaborators.json" || true

for repo in $(jq -r '.[].name' "$out/repos.json"); do
  gh_get "/repos/${org}/${repo}/collaborators?affiliation=all&per_page=100" "$out/collaborators-${repo}.json" || true
done

# Headers are kept for reference only; drop anything request-specific before committing.
echo "Recorded $(ls "$out" | grep -vc headers) responses into $out/"
