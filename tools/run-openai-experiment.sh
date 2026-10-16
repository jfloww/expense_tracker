#!/bin/sh
set -eu
experiment_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
  experiment_java="$JAVA_HOME/bin/java"
else
  experiment_java=java
fi
(cd "$experiment_root/backend" && sh ./mvnw --batch-mode -DskipTests package)
exec "$experiment_java" \
  -Dloader.main=com.expensetracker.experiments.extraction.openai.OpenAiExtractionExperiment \
  -cp "$experiment_root/backend/target/backend-0.1.0-SNAPSHOT.jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
