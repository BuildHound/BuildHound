#!/bin/bash
# PreToolUse guard (Write|Edit|MultiEdit|NotebookEdit): when the session runs
# inside a .claude/worktrees/<name> checkout, block file writes that target the
# main checkout or a sibling worktree. Exit 2 blocks the tool call and feeds
# the corrected path back to the model. Subagents inherit project hooks, so
# this also binds fan-out agents that would otherwise write to the wrong
# checkout.
set -u
proj="${CLAUDE_PROJECT_DIR:-$PWD}"
case "$proj" in
  */.claude/worktrees/*) main="${proj%%/.claude/worktrees/*}" ;;
  *) exit 0 ;; # not a worktree session — nothing to guard
esac
command -v jq >/dev/null 2>&1 || exit 0
target=$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' 2>/dev/null)
[ -z "$target" ] && exit 0
# Best-effort byte-prefix match: relative paths, ".." segments, and symlinked
# spellings of the main checkout fall through to allow. The stray-check Stop
# hook is the backstop for anything that slips past.
case "$target" in
  "$proj"/*) exit 0 ;; # correct checkout
  "$main"/*)
    rel="${target#"$main"/}"
    case "$rel" in
      .claude/worktrees/*)
        hint="path points into a DIFFERENT worktree"
        rel="${rel#.claude/worktrees/*/}"
        ;;
      *)
        hint="path points into the MAIN checkout"
        ;;
    esac
    echo "worktree-guard: BLOCKED — $hint while this session runs in worktree $proj." >&2
    echo "Write to the active worktree instead: $proj/$rel" >&2
    exit 2
    ;;
esac
exit 0
