#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
test_output=$(mktemp -d)
javac -d "$test_output" \
  "$repo_root/app/src/main/java/com/tds/projectorlauncher/RemoteKeySequenceRouter.java" \
  "$repo_root/backend/tests/RemoteKeySequenceRouterTest.java"
java -cp "$test_output" com.tds.projectorlauncher.RemoteKeySequenceRouterTest
