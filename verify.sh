#!/bin/bash
# Harness verification for APMKit Android SDK. Run from repo root.
# Usage: ./verify.sh [build|test|lint|budget|publish|all]   (default: build)
# Always ends with a machine-parseable line: HARNESS_VERIFY: PASS|FAIL
set -eo pipefail

MODE="${1:-build}"

fail() { echo "HARNESS_VERIFY: FAIL ($1)"; exit 1; }

# Bootstrap check — gradlew exists but :sdk module must also exist.
check_gradlew() {
  if [ ! -f "./gradlew" ]; then
    echo "ERROR: gradlew not found."
    fail "$1 — gradlew missing"
  fi
}

run_build() {
  check_gradlew "build"
  ./gradlew :sdk:assembleDebug || fail "build"
  # A17: the verification app is the only way the device-only checklist can ever be run, and it
  # depends on :sdk — so a change that breaks integration breaks the build here rather than being
  # discovered on a device later. It sat broken and unnoticed until A17 precisely because nothing
  # built it.
  ./gradlew :app:assembleDebug || fail "build — verification app (:app)"
}

run_test() {
  check_gradlew "test"
  # `test` runs unit tests only. Instrumented tests are connectedAndroidTest
  # and need a device or emulator — do not silently conflate the two.
  ./gradlew :sdk:test || fail "test"
}

run_lint() {
  check_gradlew "lint"
  ./gradlew :sdk:lint || fail "lint"
}

# A12 (`02` §5): the size budget. Only the lines a CI runner can measure without a device are
# enforced here — cold start p95, CPU and resident memory need a device benchmark the project
# does not have yet, and are tracked as open gaps on A12 rather than faked with a green check.
run_budget() {
  check_gradlew "budget"
  ./gradlew :sdk:checkPerformanceBudget || fail "budget"
}

# A13 (MOB-23, SEC-19): builds the publication into build/local-repo and validates it. Needs no
# credentials, so it runs anywhere — the real internal repository is only a destination.
run_publish() {
  check_gradlew "publish"
  ./gradlew :sdk:verifyPublication || fail "publish"
}

case "$MODE" in
  build) run_build ;;
  test)  run_test ;;
  lint)   run_lint ;;
  budget)  run_budget ;;
  publish) run_publish ;;
  all)     run_build && run_test && run_lint && run_budget && run_publish ;;
  *)       echo "Unknown mode: $MODE (use build|test|lint|budget|publish|all)"; exit 2 ;;
esac

echo "HARNESS_VERIFY: PASS ($MODE)"
