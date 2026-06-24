#!/usr/bin/env bash
# Fix ownership of results/ after sudo/docker experiments (files often end up as nobody:root).
set -euo pipefail
cd "$(dirname "$0")/.."
OWNER="${RESULTS_OWNER:-$(whoami)}"
GROUP="${RESULTS_GROUP:-$(id -gn)}"
if [[ -d results ]]; then
  if chown -R "$OWNER:$GROUP" results 2>/dev/null; then
    echo "results/ -> $OWNER:$GROUP"
  else
    echo "Need sudo to fix results/ ownership ..."
    sudo chown -R "$OWNER:$GROUP" results
    echo "results/ -> $OWNER:$GROUP"
  fi
fi
