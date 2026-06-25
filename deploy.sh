#!/usr/bin/env bash
#
# deploy.sh — Promote the current main to production.
#
# Safe, repeatable deploy: refuses to run unless you're on a clean main, pulls
# the latest code, runs the unit tests as a final gate, then launches the bots.
# Production should only ever run code that is on main and passed CI — this
# script enforces that locally too.
#
# Usage (from the project root, on the production host):
#   ./deploy.sh
#
# Windows: run it from Git Bash (the same shell the project's docs assume).

set -euo pipefail

PYTHON="${PYTHON:-python3}"

echo "==> Checking branch..."
branch="$(git rev-parse --abbrev-ref HEAD)"
if [ "$branch" != "main" ]; then
  echo "ERROR: you are on '$branch', not 'main'. Production only deploys main." >&2
  echo "       Run: git checkout main" >&2
  exit 1
fi

echo "==> Checking for uncommitted changes..."
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "ERROR: working tree is dirty. Commit, stash, or discard changes first." >&2
  git status --short >&2
  exit 1
fi

echo "==> Pulling latest main..."
git pull --ff-only origin main

echo "==> Running tests (final gate)..."
if ! "$PYTHON" -m unittest discover -s tests; then
  echo "ERROR: tests failed. Aborting deploy — production was NOT changed." >&2
  exit 1
fi

echo "==> Tests passed. Launching bots..."
exec "$PYTHON" start.py
