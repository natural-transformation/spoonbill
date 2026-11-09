#!/usr/bin/env bash
# Verify public artifacts in a disposable independent consumer, never publish them.
set -euo pipefail
umask 077

if [[ $# -ne 1 || ! "$1" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Usage: bash scripts/verify-recovery-kit-release.sh <stable-version X.Y.Z>" >&2
  exit 2
fi
if [[ -z "${IN_NIX_SHELL:-}" ]]; then
  echo "Run this check through the repository flake: nix develop --no-write-lock-file --command bash scripts/verify-recovery-kit-release.sh $1" >&2
  exit 2
fi

release_version="$1"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verification_root="$(mktemp -d "${TMPDIR:-/tmp}/spoonbill-released-kit.XXXXXX")"
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  rm -rf -- "$verification_root"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$verification_root/consumer/project" "$verification_root/home" \
  "$verification_root/config" "$verification_root/cache" "$verification_root/tmp"
# Do not carry target/, project/target/, plugins or local launcher options over.
cp "$repo_root/misc/recovery-adapter-consumer/consumer.sbt" "$verification_root/consumer/"
cp "$repo_root/misc/recovery-adapter-consumer/project/build.properties" "$verification_root/consumer/project/"
cp -R "$repo_root/misc/recovery-adapter-consumer/src" "$verification_root/consumer/"
# The Nix sbt launcher otherwise falls back to /etc/sbt/sbtopts.
: > "$verification_root/sbtopts"
cat > "$verification_root/repositories" <<'REPOSITORIES'
[repositories]
maven-central: https://repo.maven.apache.org/maven2
REPOSITORIES

echo "Verifying Spoonbill $release_version and its recovery kit from https://repo.maven.apache.org/maven2"
cd "$verification_root/consumer"
# Keep the Nix toolchain, but discard inherited JVM, sbt and resolver overrides.
# HOME itself is unchanged; Java and sbt use explicit disposable directories.
env -i PATH="$PATH" HOME="${HOME:-}" JAVA_HOME="${JAVA_HOME:-}" \
  TMPDIR="$verification_root/tmp" \
  XDG_CONFIG_HOME="$verification_root/config" XDG_CACHE_HOME="$verification_root/cache" \
  SBT_ETC_FILE="$verification_root/sbtopts" \
  COURSIER_CACHE="$verification_root/coursier" \
  COURSIER_REPOSITORIES="https://repo.maven.apache.org/maven2" SBT_NATIVE_CLIENT=false \
  sbt --batch --supershell=false \
  -J-Xms256m -J-Xmx2g -J-XX:MaxMetaspaceSize=768m -J-XX:ActiveProcessorCount=2 \
  "-J-Duser.home=$verification_root/home" \
  "-J-Djava.io.tmpdir=$verification_root/tmp" \
  "-Dsbt.global.base=$verification_root/sbt-global" \
  "-Dsbt.boot.directory=$verification_root/sbt-boot" \
  "-Dsbt.ivy.home=$verification_root/ivy" \
  "-Dsbt.preloaded=$verification_root/preloaded" \
  "-Dcoursier.cache=$verification_root/coursier" \
  "-Dsbt.repository.config=$verification_root/repositories" \
  -Dsbt.override.build.repos=true -Dsbt.server.autostart=false \
  "-Dspoonbill.version=$release_version" -Dspoonbill.released=true \
  verifyKitGraph test
echo "Released recovery-kit graph and adapter laws passed for $release_version."
