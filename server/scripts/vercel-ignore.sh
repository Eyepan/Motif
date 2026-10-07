#!/bin/sh
# Vercel's Ignored Build Step: exit 0 skips the deploy, anything else builds it.
# Deploys only when server/ or the API contract changed since the last successful deploy.
base="${VERCEL_GIT_PREVIOUS_SHA:-HEAD^}"
# Vercel clones shallowly, so the last deploy may be out of reach; fall back to the parent.
git cat-file -e "$base^{commit}" 2>/dev/null || base="HEAD^"
if git diff --quiet "$base" HEAD -- . ../schemas/api; then
  echo "No server changes since $base, skipping the deploy."
  exit 0
fi
exit 1
