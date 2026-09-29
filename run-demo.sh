#!/usr/bin/env bash
set -euo pipefail

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "Kotlin compiler (kotlinc) is required. Install Kotlin, then run this script again." >&2
  exit 1
fi

mkdir -p build
kotlinc PaymentReconciliationDemo.kt -include-runtime -d build/payment-reconciliation-lab.jar
java -ea -jar build/payment-reconciliation-lab.jar
