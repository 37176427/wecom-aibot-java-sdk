#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mvn -B -ntp test-compile dependency:build-classpath -Dmdep.outputFile=target/sandbox-classpath -DincludeScope=test "$@"
sandbox_runtime=$(mktemp -d "${TMPDIR:-/tmp}/wecom-sdk-sandbox.XXXXXX")
trap 'rm -rf -- "$sandbox_runtime"' EXIT
# A running test bot must not load class files from directories being recompiled.
jar --create --file "$sandbox_runtime/sdk.jar" -C target/classes .
jar --create --file "$sandbox_runtime/examples.jar" -C target/test-classes .
java -cp "$sandbox_runtime/examples.jar:$sandbox_runtime/sdk.jar:$(cat target/sandbox-classpath)" io.github.moment.wecom.aibot.examples.SandboxBot .env.local
