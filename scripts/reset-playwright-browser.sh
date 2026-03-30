#!/usr/bin/env bash

set -euo pipefail

PROFILE_DIR="${HOME}/Library/Caches/ms-playwright/mcp-chrome"

echo "Cleaning Playwright MCP browser state..."

pkill -f 'playwright-mcp|@playwright/mcp|mcp-chrome' >/dev/null 2>&1 || true
sleep 1

if [[ -d "${PROFILE_DIR}" ]]; then
  rm -f \
    "${PROFILE_DIR}/SingletonLock" \
    "${PROFILE_DIR}/SingletonSocket" \
    "${PROFILE_DIR}/SingletonCookie"
  echo "Removed stale singleton locks from ${PROFILE_DIR}"
else
  echo "Profile directory not found: ${PROFILE_DIR}"
fi

echo "Done. You can retry the Playwright browser now."
