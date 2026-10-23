#!/usr/bin/env bash
set -euo pipefail

# Run from a full checkout of main, without signing or publishing credentials.
# Event values are data, never interpolated into shell source or git options.
version_pattern='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'
[[ "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid event commit' >&2; exit 1; }
sha="$GITHUB_SHA"
case "${GITHUB_EVENT_NAME:-}" in
  workflow_dispatch)
    [[ "${GITHUB_REF:-}" == refs/heads/main ]] || { echo 'Dispatch releases from main only' >&2; exit 1; }
    version="${REQUESTED_VERSION:-}"
    ;;
  release)
    version="${RELEASE_TAG:-}"
    version="${version#v}"
    [[ "$version" =~ $version_pattern ]] || { echo 'Invalid release tag' >&2; exit 1; }
    [[ "${GITHUB_REF:-}" == "refs/tags/$RELEASE_TAG" ]] || { echo 'Release ref/tag mismatch' >&2; exit 1; }
    tagged_sha=$(git rev-parse --verify "refs/tags/$RELEASE_TAG^{commit}")
    [[ "$tagged_sha" == "$sha" ]] || { echo 'Release tag moved after the event' >&2; exit 1; }
    ;;
  *) echo 'Unsupported release event' >&2; exit 1 ;;
esac
[[ "$version" =~ $version_pattern ]] || { echo 'Expected MAJOR.MINOR.PATCH with an optional prerelease suffix' >&2; exit 1; }
git merge-base --is-ancestor "$sha" refs/remotes/origin/main || {
  echo 'Release commit must belong to reviewed main history' >&2
  exit 1
}
printf 'sha=%s\nversion=%s\n' "$sha" "$version" >> "${GITHUB_OUTPUT:?Missing GITHUB_OUTPUT}"
