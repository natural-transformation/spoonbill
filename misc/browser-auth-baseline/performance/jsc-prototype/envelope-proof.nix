# No fetches/imports: root supplies already-copied bash/coreutils store paths.
{ system, bash, coreutils, expectedNamespace }:
let
  bashPath = builtins.storePath bash;
  corePath = builtins.storePath coreutils;
in
builtins.derivation {
  name = "spoonbill-jsc-builder-containment-proof";
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
    # A real descendant checks placement too. Merely testing the Nix client is
    # insufficient; this code executes as an actual Nix derivation builder.
    ${bashPath}/bin/bash -euc '
      test "$EUID" -ne 0
      test "$(${corePath}/bin/cat /proc/self/cgroup)" = "0::/"
      test "$(${corePath}/bin/readlink /proc/self/ns/cgroup)" = "$expectedCgroupNamespace"
    '
    ${corePath}/bin/mkdir -p "$out"
    ${corePath}/bin/printf '%s\n' 'actual-builder-and-child-contained' > "$out/proof"
  '' ];
  preferLocalBuild = true;
  allowSubstitutes = false;
}
