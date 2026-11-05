#!/usr/bin/env bash
# Run the exact per-fixture CorpusGate under an experimental Groovy version.
# The test classes are compiled with the default (Groovy 4) toolchain; only main sources
# and the support Java stubs are rebuilt for the requested version.
set -euo pipefail

version="${1:?usage: tools/groovy-compat-check.sh <groovy-version>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

./gradlew :nullchecker:testClasses --console=plain >/dev/null
./gradlew :nullchecker:compileGroovy -PgroovyVersion="$version" --rerun-tasks --console=plain >/dev/null

full_classpath="$(./gradlew :nullchecker:printTestClasspath -PgroovyVersion="$version" --console=plain -q)"
classpath="$(printf '%s' "$full_classpath" | tr ':' '\n' | grep -v -E 'spock|junit' | paste -sd: -)"

groovy_jar="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.apache.groovy/groovy/$version" -name "groovy-$version.jar" | head -1)"
if [ -z "$groovy_jar" ]; then
    echo "compat: Groovy $version jar not found" >&2
    exit 1
fi

support_dir="$(mktemp -d)"
trap 'rm -rf "$support_dir"' EXIT
find nullchecker/corpus/support -name '*.java' -print0 |     xargs -0 javac -cp "$groovy_jar" -d "$support_dir"

java -cp "$support_dir:$classpath" groovy.ui.GroovyMain tools/compat-gate.groovy \
    "$root/nullchecker/corpus"
