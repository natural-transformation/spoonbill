#!/usr/bin/env bash
# Root creates the isolated toy unit; this helper never creates/stops services.
set -euo pipefail
refuse() { printf '%s\n' "oom-proof-refused:$1" >&2; exit 125; }
validate_oom_result() {
  local result=$1 main_pid=$2 state=$3 peak=$4 populated=$5 sibling_alive=$6
  [[ "$result" == oom-kill && "$main_pid" == 0 &&
     ( "$state" == failed || "$state" == inactive ) &&
     "$peak" =~ ^[0-9]{1,12}$ && "$populated" == 0 && "$sibling_alive" == 0 ]] || return 1
  # Observed diagnostic ceiling allows bounded kernel accounting overshoot.
  # It does not redefine MemoryMax or claim a strict peak-RSS guarantee.
  (( 10#$peak >= 50331648 && 10#$peak <= 83886080 ))
}
process_start() {
  local line tail
  IFS= read -r line < "/proc/$1/stat" || return 1
  tail=${line##*) }
  local fields
  read -ra fields <<< "$tail"
  [[ ${#fields[@]} -ge 20 && ${fields[19]} =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "${fields[19]}"
}
payload() {
  [[ $# == 1 && $(uname -s) == Linux && $EUID == 0 ]] || refuse payload-arguments
  [[ $(cat /proc/self/cgroup) == '0::/' &&
     $(cat /sys/fs/cgroup/memory.max) == 67108864 &&
     $(cat /sys/fs/cgroup/memory.high) == max &&
     $(cat /sys/fs/cgroup/memory.swap.max) == 0 &&
     $(cat /sys/fs/cgroup/memory.oom.group) == 1 ]] || refuse toy-budget-mismatch
  [[ "$1" == /* && ! -e "$1" && ! -L "$1" ]] || refuse new-absolute-marker-required
  local sibling start bytes
  sleep 600 & sibling=$!
  start=$(process_start "$sibling") || refuse missing-sibling
  # noclobber plus root-owned parent is required; marker survives unit cleanup.
  (set -C; umask 077; printf '%s %s\n' "$sibling" "$start" > "$1") || refuse marker-exists
  # Only 128 MiB requested in a separate 64 MiB unit. Non-NUL bytes force Bash
  # to retain the output; no compiler, download, shared service or large disk IO.
  bytes=$(head -c 134217728 /dev/zero | tr '\000' x)
  [[ ${#bytes} == 134217728 ]] || refuse allocation-incomplete
  refuse allocation-survived-cap
}
verify() {
  [[ $# == 2 && $(uname -s) == Linux ]] || refuse verify-arguments
  local unit=$1 marker=$2 result main_pid state peak cg populated=0 sibling start current alive=0
  [[ "$unit" =~ ^spoonbill-jsc-oom-[a-z0-9-]+\.service$ &&
     -f "$marker" && ! -L "$marker" && $(stat -c %s "$marker") -le 64 ]] || refuse invalid-verification-input
  read -r sibling start < "$marker"
  [[ "$sibling" =~ ^[0-9]+$ && "$start" =~ ^[0-9]+$ ]] || refuse invalid-marker
  result=$(systemctl show "$unit" --value -p Result)
  main_pid=$(systemctl show "$unit" --value -p MainPID)
  state=$(systemctl show "$unit" --value -p ActiveState)
  peak=$(systemctl show "$unit" --value -p MemoryPeak)
  cg=$(systemctl show "$unit" --value -p ControlGroup)
  [[ -z "$cg" || "$cg" == /system.slice/"$unit" ]] || refuse unexpected-cgroup
  # systemd may clear ControlGroup after collecting the empty cgroup.
  cg=/system.slice/"$unit"
  if [[ -e "/sys/fs/cgroup$cg/cgroup.events" ]]; then
    populated=$(awk '$1 == "populated" { print $2 }' "/sys/fs/cgroup$cg/cgroup.events")
  elif [[ -e "/sys/fs/cgroup$cg" ]]; then refuse unavailable-cgroup-events; fi
  if ! current=$(process_start "$sibling" 2>/dev/null); then
    [[ ! -d "/proc/$sibling" ]] || refuse unreadable-extant-sibling
    current=gone
  fi
  [[ "$current" != "$start" ]] || alive=1
  validate_oom_result "$result" "$main_pid" "$state" "$peak" "$populated" "$alive" || refuse oom-or-cleanup-not-proven
  printf '{"kind":"owned-toy-oom-diagnostic","acceptanceEvidence":false,"result":"oom-kill","memoryMaxBytes":67108864,"observedChargedPeakBytes":%s,"cgroupPopulated":false,"siblingAlive":false}\n' "$peak"
}
main() {
  [[ $# -gt 0 ]] || refuse expected-mode
  local mode=$1; shift
  case "$mode" in payload) payload "$@" ;; verify) verify "$@" ;; *) refuse unsupported-mode ;; esac
}
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then main "$@"; fi
