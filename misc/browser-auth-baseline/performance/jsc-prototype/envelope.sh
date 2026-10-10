#!/usr/bin/env bash
# Linux guest only. Root prepares the unit/mount; this script changes neither.
set -euo pipefail

die() { printf '%s\n' "envelope-refused:$1" >&2; exit 125; }
decimal() { [[ "$1" =~ ^[0-9]{1,16}$ ]]; }
validate_budget() {
  local maximum=$1 high=$2 swap=$3 oom=$4 quota=$5 period=$6 pids=$7 disk=$8
  for value in "$maximum" "$high" "$swap" "$oom" "$quota" "$period" "$pids" "$disk"; do
    decimal "$value" || return 1
  done
  (( 10#$maximum > 0 && 10#$maximum <= 16000000000 &&
     10#$high > 0 && 10#$high <= 14000000000 && 10#$high <= 10#$maximum &&
     10#$swap == 0 && 10#$oom == 1 && 10#$quota > 0 && 10#$period > 0 &&
     10#$quota <= 2 * 10#$period && 10#$pids > 0 && 10#$pids <= 256 &&
     10#$disk > 0 && 10#$disk <= 16106127360 ))
}
has_option() { [[ ",$1," == *",$2,"* ]]; }
priority_lock_clear() {
  [[ "$1" =~ ^/[A-Za-z0-9_./-]+$ && "$1" != / && ! -e "$1" && ! -L "$1" ]]
}
validate_nix_identity() {
  [[ "$1" == "$2" && "$1" == 'nix (Nix) 2.35pre'* && "$1" == *53ab7375* ]]
}
timespan_seconds() {
  local rest=$1 total=0 amount unit part
  while [[ -n "$rest" ]]; do
    [[ "$rest" =~ ^([0-9]{1,8})(h|min|s)([[:space:]]|$) ]] || return 1
    amount=${BASH_REMATCH[1]}; unit=${BASH_REMATCH[2]}; part=${BASH_REMATCH[0]}
    case "$unit" in h) total=$((total + 10#$amount * 3600)) ;; min) total=$((total + 10#$amount * 60)) ;; s) total=$((total + 10#$amount)) ;; esac
    rest=${rest#"$part"}
  done
  printf '%s\n' "$total"
}

check_ci() {
  local current files machines available
  # The controller provides a RO bind of the runner cgroup subtree. It is not
  # a stale copied PID file. No runner API token or service mutation is used.
  files="$(find "$JSC_CI_CGROUP" -name cgroup.procs -type f -print)" || return 1
  [[ -n "$files" && $(printf '%s\n' "$files" | wc -l) -le 256 ]] || return 1
  current="$(while IFS= read -r file; do cat -- "$file" || exit 1; done <<< "$files" | sort -nu)" || return 1
  [[ "$current" == "$JSC_CI_BASELINE_PIDS" ]] || return 1
  machines="$(machinectl list --no-legend --no-pager 2>/dev/null)" || return 1
  [[ -z "$machines" ]] || return 1
  available="$(awk '$1 == "MemAvailable:" { print $2 }' /proc/meminfo)"
  decimal "$available" && (( 10#$available >= 8000000 ))
}

preflight() {
  [[ $(uname -s) == Linux && $EUID == 0 ]] || die linux-trusted-root-controller-required
  for name in JSC_WORK JSC_UNIT JSC_DEADLINE_EPOCH JSC_CI_CGROUP JSC_CI_BASELINE_PIDS JSC_EXPECTED_USERPOOL_DEVINO JSC_EXPECTED_NIX_VERSION JSC_PRIORITY_LOCK; do
    [[ -n ${!name:-} ]] || die missing-controller-setting
  done
  [[ -z ${NIX_STATE_DIR+x} ]] || die nix-state-dir-override-forbidden
  validate_nix_identity "$(nix --version)" "$JSC_EXPECTED_NIX_VERSION" || die unaudited-nix-version
  [[ "$JSC_EXPECTED_USERPOOL_DEVINO" =~ ^[0-9]+:[0-9]+$ && -d /nix/var/nix/userpool && ! -L /nix/var/nix/userpool &&
     $(stat -c '%d:%i' /nix/var/nix/userpool) == "$JSC_EXPECTED_USERPOOL_DEVINO" ]] || die shared-build-user-locks-not-identical
  has_option "$(findmnt -rn -T /nix/var/nix/userpool -o VFS-OPTIONS)" rw && [[ -w /nix/var/nix/userpool ]] || die shared-build-user-locks-readonly
  [[ "$JSC_WORK" =~ ^/[A-Za-z0-9_./-]+$ && "$JSC_WORK" != / &&
     "$JSC_CI_CGROUP" =~ ^/[A-Za-z0-9_./-]+$ ]] || die invalid-owned-path
  [[ $(realpath -e -- "$JSC_WORK") == "$JSC_WORK" ]] || die noncanonical-workspace
  [[ "$JSC_UNIT" =~ ^spoonbill-jsc-[a-z0-9-]+\.service$ ]] || die invalid-owned-unit
  decimal "$JSC_DEADLINE_EPOCH" || die invalid-deadline
  local now remaining cg_type cg_options work_type work_source work_target ci_type ci_options quota period disk leader
  now=$(date +%s); remaining=$((10#$JSC_DEADLINE_EPOCH - now))
  (( remaining > 0 && remaining <= 7200 )) || die approved-deadline-exceeded
  leader=$(systemctl show "$JSC_UNIT" --value -p MainPID)
  decimal "$leader" && (( 10#$leader > 1 )) || die missing-owned-controller
  export JSC_EXPECTED_NAMESPACE
  JSC_EXPECTED_NAMESPACE=$(readlink "/proc/$leader/ns/cgroup")
  [[ $(cat /proc/self/cgroup) == '0::/' && $(cat "/proc/$leader/cgroup") == '0::/' &&
     $(readlink /proc/self/ns/cgroup) == "$JSC_EXPECTED_NAMESPACE" ]] || die wrong-cgroup-namespace
  cg_type=$(findmnt -rn -T /sys/fs/cgroup -o FSTYPE)
  cg_options=$(findmnt -rn -T /sys/fs/cgroup -o VFS-OPTIONS)
  [[ "$cg_type" == cgroup2 ]] && has_option "$cg_options" ro || die private-readonly-cgroup-required
  # A global cgroup root has no memory.max. These files must be the unit's
  # resource-controlled namespace root, not an arbitrary delegated child.
  read -r quota period < /sys/fs/cgroup/cpu.max
  work_type=$(findmnt -rn -M "$JSC_WORK" -o FSTYPE)
  work_source=$(findmnt -rn -M "$JSC_WORK" -o SOURCE)
  work_target=$(findmnt -rn -M "$JSC_WORK" -o TARGET)
  [[ "$work_type" == ext4 && "$work_target" == "$JSC_WORK" && "$work_source" =~ ^/dev/loop[0-9]+$ ]] || die bounded-owned-ext4-loop-required
  disk=$(blockdev --getsize64 "$work_source")
  validate_budget "$(cat /sys/fs/cgroup/memory.max)" "$(cat /sys/fs/cgroup/memory.high)" \
    "$(cat /sys/fs/cgroup/memory.swap.max)" "$(cat /sys/fs/cgroup/memory.oom.group)" \
    "$quota" "$period" "$(cat /sys/fs/cgroup/pids.max)" "$disk" || die controller-budget-mismatch
  ci_type=$(findmnt -rn -T "$JSC_CI_CGROUP" -o FSTYPE)
  ci_options=$(findmnt -rn -T "$JSC_CI_CGROUP" -o VFS-OPTIONS)
  [[ "$ci_type" == cgroup2 ]] && has_option "$ci_options" ro || die readonly-live-ci-cgroup-required
  [[ $(printf '%s\n' "$JSC_CI_BASELINE_PIDS" | sort -nu) == "$JSC_CI_BASELINE_PIDS" &&
     "$JSC_CI_BASELINE_PIDS" =~ ^[0-9]+$ ]] || die one-verified-idle-runner-pid-required
  local property expected
  for property in KillMode OOMPolicy ProtectControlGroups Nice CPUWeight IOWeight OOMScoreAdjust NoNewPrivileges; do
    case "$property" in
      KillMode) expected=control-group ;; OOMPolicy) expected=kill ;; ProtectControlGroups) expected=protected ;;
      Nice) expected=19 ;; CPUWeight|IOWeight) expected=1 ;; OOMScoreAdjust) expected=1000 ;; NoNewPrivileges) expected=yes ;;
    esac
    local actual
    actual=$(systemctl show "$JSC_UNIT" --value -p "$property")
    if [[ "$property" == ProtectControlGroups ]]; then
      [[ "$actual" == strict || "$actual" == yes ]] || die unit-policy-mismatch
    else [[ "$actual" == "$expected" ]] || die unit-policy-mismatch; fi
  done
  local stop_seconds runtime_seconds
  stop_seconds=$(timespan_seconds "$(systemctl show "$JSC_UNIT" --value -p TimeoutStopUSec)") || die unit-deadline-mismatch
  runtime_seconds=$(timespan_seconds "$(systemctl show "$JSC_UNIT" --value -p RuntimeMaxUSec)") || die unit-deadline-mismatch
  (( stop_seconds > 0 && stop_seconds <= 10 && runtime_seconds > 0 && runtime_seconds <= 7200 )) || die unit-deadline-mismatch
  priority_lock_clear "$JSC_PRIORITY_LOCK" || die priority-operation-present-or-invalid-lock
  check_ci || die ci-busy-or-unverified
}

configure_nix() {
  mkdir -p -- "$JSC_WORK"/{tmp,cache,config,logs,nix-root}
  chmod 700 "$JSC_WORK"/{tmp,cache,config,logs}
  export TMPDIR="$JSC_WORK/tmp" XDG_CACHE_HOME="$JSC_WORK/cache" XDG_CONFIG_HOME="$JSC_WORK/config"
  export NIX_CONF_DIR="$JSC_WORK/config" NIX_USER_CONF_FILES=/dev/null
  unset NIX_CONFIG NIX_PATH NIX_SSHOPTS
  export NIX_REMOTE="local?root=$JSC_WORK/nix-root"
  export NIX_BUILD_CORES=1 CMAKE_BUILD_PARALLEL_LEVEL=1 MAKEFLAGS=-j1
}

owned_nix() {
  # This wrapper is for actual local builds/evaluation. Copy inputs separately
  # with an explicit local source and this private target, within the same unit.
  [[ -z ${NIX_STATE_DIR+x} ]] || die nix-state-dir-override-forbidden
  case "${1:-}" in
    build|eval|path-info) ;;
    store)
      # Only this reviewed archive acquisition is admitted. No generic store
      # commands, alternate source, flag overrides or hash-less fetches.
      [[ $# == 8 && "$2" == prefetch-file && "$3" == --json &&
         "$4" == --name && "$5" == webkitgtk-2.50.4.tar.xz &&
         "$6" == --expected-hash && "$7" == 'sha256-07+kc4Raz6tyY1utpeDRNP2meSxblcXFzRQbRhJb2OQ=' &&
         "$8" == https://webkitgtk.org/releases/webkitgtk-2.50.4.tar.xz ]] || die unapproved-source-prefetch
      ;;
    *) die unsupported-owned-nix-command ;;
  esac
  for arg in "$@"; do
    case "$arg" in --store*|--option*|--builders*|--extra-builders*|--cores*|--max-jobs*|-j*|--daemon|--use-cgroups*|--no-use-cgroups*|--sandbox*|--no-sandbox*|--build-users-group*|--build-dir*|--auto-allocate-uids*|--no-auto-allocate-uids*|--state-dir*) die forbidden-nix-override ;; esac
  done
  command nix --extra-experimental-features 'nix-command flakes' --store "$NIX_REMOTE" --option build-users-group nixbld --option use-cgroups false \
    --option builders '' --option sandbox true --option auto-allocate-uids false --option max-jobs 1 --option cores 1 \
    --option build-dir "$JSC_WORK/tmp" --option min-free 0 "$@"
}

run_guarded() {
  local controller watcher status=0 task
  controller=$(systemctl show "$JSC_UNIT" --value -p MainPID)
  decimal "$controller" && (( 10#$controller > 1 )) || die missing-owned-controller
  trap 'exit 125' TERM INT
  (
    while kill -0 "$controller" 2>/dev/null; do
      if (( $(date +%s) >= 10#$JSC_DEADLINE_EPOCH )) || ! check_ci; then
        printf '%s\n' 'deadline-or-ci-priority-stop' > "$JSC_WORK/logs/stop-reason" || true
        # Only this attested experiment's MainPID is signalled. MainPID exit
        # invokes systemd's already configured whole-cgroup cleanup.
        kill -TERM "$controller" 2>/dev/null || true
        exit
      fi
      sleep 1
    done
  ) & watcher=$!
  trap 'kill "$watcher" 2>/dev/null || true; wait "$watcher" 2>/dev/null || true' EXIT
  "$@" & task=$!
  wait "$task" || status=$?
  exit "$status"
}

main() {
  [[ $# -ge 1 ]] || die expected-check-or-run
  local mode=$1; shift
  case "$mode" in
    check) [[ $# == 0 ]] || die unexpected-arguments; preflight; printf '%s\n' 'envelope-preflight-passed' ;;
    nix)
      [[ $# -gt 0 ]] || die missing-nix-arguments
      preflight; configure_nix; run_guarded owned_nix "$@"
      ;;
    run)
      [[ $# -gt 0 && "$1" == /* ]] || die explicit-driver-required
      preflight; configure_nix
      [[ $(realpath -e "$1") == "$JSC_WORK/"* ]] || die driver-outside-owned-filesystem
      # Main-process exit invokes the controller-attested systemd group cleanup.
      # This watcher only yields the experiment; it never stops shared services.
      run_guarded "$@"
      ;;
    *) die unsupported-mode ;;
  esac
}
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then main "$@"; fi
