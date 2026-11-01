#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="${JAVA21_HOME:-${JAVA_HOME:-}}"
export JAVA17_HOME="${JAVA17_HOME:-}"
if [[ ! -x "$JAVA_HOME/bin/java" || ! -x "$JAVA17_HOME/bin/javac" ]]; then
    printf '%s\n' 'Set JAVA21_HOME and JAVA17_HOME to complete JDK 21 and JDK 17 installations.' >&2
    exit 1
fi
if (( $# == 0 )); then set -- build; fi
exec "$ROOT/gradlew" -p "$ROOT" "$@"
