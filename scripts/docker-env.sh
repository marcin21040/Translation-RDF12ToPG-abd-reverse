#!/usr/bin/env bash
# Resolve docker command (plain docker or sudo docker).
# Sets DOCKER array and exports DOCKER_CMD for Python scripts.

resolve_docker() {
  if [[ -n "${DOCKER_CMD:-}" ]]; then
    # shellcheck disable=SC2206
    DOCKER=($DOCKER_CMD)
    return 0
  fi

  if docker info >/dev/null 2>&1; then
    DOCKER=(docker)
  elif sudo docker info >/dev/null 2>&1; then
    DOCKER=(sudo docker)
    echo "Note: using 'sudo docker' (to skip sudo: sudo usermod -aG docker \$USER, then log out/in)"
  else
    echo "Error: cannot access Docker API."
    echo "Try: sudo bash scripts/run-experiments.sh"
    echo "Or add your user to the docker group: sudo usermod -aG docker \$USER"
    return 1
  fi

  export DOCKER_CMD="${DOCKER[*]}"
}
