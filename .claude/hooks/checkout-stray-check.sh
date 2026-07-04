#!/bin/bash
# SessionStart ("baseline") + Stop/SubagentStop ("check"): detect files that
# appeared in the MAIN checkout while this session ran in a worktree — the
# "subagent wrote to the wrong checkout" failure mode. Baseline at session
# start means pre-existing dirt in the main checkout (the user's own edits)
# never triggers the warning; only NEW dirt accumulated during the session
# does. Warn-only: never blocks the session from stopping.
set -u
mode="${1:-check}"
proj="${CLAUDE_PROJECT_DIR:-$PWD}"
case "$proj" in
  */.claude/worktrees/*) main="${proj%%/.claude/worktrees/*}" ;;
  *) exit 0 ;;
esac
# Key the baseline on worktree path + session id so two concurrent sessions in
# the same worktree don't clobber each other's baseline. session_id arrives on
# stdin (hook input JSON); fall back to path-only when unavailable.
sid=""
if command -v jq >/dev/null 2>&1; then
  sid=$(jq -r '.session_id // empty' 2>/dev/null)
fi
state="/tmp/bh-stray-baseline-$(printf '%s%s' "$proj" "$sid" | cksum | cut -d' ' -f1)"
snapshot() { git -C "$main" status --porcelain 2>/dev/null | sort; }
if [ "$mode" = "baseline" ]; then
  snapshot > "$state"
  exit 0
fi
[ -f "$state" ] || exit 0
new=$(comm -13 "$state" <(snapshot) | head -20)
[ -z "$new" ] && exit 0
msg="stray-check: the MAIN checkout ($main) gained uncommitted changes during this worktree session:
$new
If any of this is the session's work, migrate it into the worktree ($proj) and clean the main checkout."
if command -v jq >/dev/null 2>&1; then
  jq -cn --arg m "$msg" '{systemMessage: $m}'
else
  echo "$msg" >&2
fi
exit 0
