# No fetches/imports/compiler: use only the parent's verified existing store paths.
{ system, bash, coreutils, prototypePath, expectedNamespace }:
let
  bashPath = builtins.storePath bash;
  corePath = builtins.storePath coreutils;
  prototype = builtins.storePath prototypePath;
in
builtins.derivation {
  name = "spoonbill-jsc-installed-oracle-proof";
  inherit system;
  builder = "${bashPath}/bin/bash";
  expectedCgroupNamespace = expectedNamespace;
  args = [ "-euc" ''
    test "$EUID" -ne 0
    test "$(${corePath}/bin/cat /proc/self/cgroup)" = '0::/'
    test "$(${corePath}/bin/readlink /proc/self/ns/cgroup)" = "$expectedCgroupNamespace"
    status=$(${corePath}/bin/cat /proc/self/status)
    case "$status" in *$'CapEff:\t0000000000000000'*) ;; *) exit 125 ;; esac
    test "$NIX_BUILD_CORES" = 1
    test ! -w /sys/fs/cgroup/cgroup.procs
    test ! -S /nix/var/nix/daemon-socket/socket
    unset LD_LIBRARY_PATH LD_PRELOAD
    test -x ${prototype}/bin/jsc
    test -f ${prototype}/lib/libJavaScriptCore.so.1.0.0
    test -f ${prototype}/share/jsc-prototype/oracle.js
    test -f ${prototype}/share/jsc-prototype/CMakeCache.txt
    ${corePath}/bin/printf '%s  %s\n' \
      '1bdf17d0c17fd243fef766f57e56ad8d9312d286eb55549bfe7532c9309af1cc' \
      '${prototype}/share/jsc-prototype/oracle.js' | ${corePath}/bin/sha256sum --check --status
    ${corePath}/bin/mkdir -p "$out"
    cd "$out"
    ${corePath}/bin/timeout --kill-after=5s 90s \
      ${prototype}/bin/jsc --preciseAllocationCutoff=400 \
      ${prototype}/share/jsc-prototype/oracle.js > "$out/oracle.json"
    test -s "$out/oracle.json"
    ${corePath}/bin/cat "$out/oracle.json"
    ${corePath}/bin/printf '%s\n' 'installed-oracle-completed' > "$out/proof"
  '' ];
  preferLocalBuild = true;
  allowSubstitutes = false;
}
