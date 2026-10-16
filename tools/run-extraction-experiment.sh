#!/bin/sh
set -eu

experiment_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
  experiment_javac="$JAVA_HOME/bin/javac"
  experiment_java="$JAVA_HOME/bin/java"
else
  experiment_javac=javac
  experiment_java=java
fi
case "$("$experiment_javac" -version 2>&1)" in
  'javac 21.'*) ;;
  *) echo 'Java 21 JDK required; set JAVA_HOME.' >&2; exit 1 ;;
esac
mkdir -p "$experiment_root/.tools/extraction-classes"
experiment_classes=$(mktemp -d "$experiment_root/.tools/extraction-classes/run.XXXXXXXX")
experiment_output=${1:-"$experiment_root/.tools/extraction-runs/$(basename "$experiment_classes")"}
"$experiment_javac" --release 21 -encoding UTF-8 -d "$experiment_classes" \
  "$experiment_root"/backend/src/main/java/com/expensetracker/experiments/extraction/*.java
"$experiment_java" -cp "$experiment_classes" \
  com.expensetracker.experiments.extraction.ExtractionExperiment --output "$experiment_output"
