#!/usr/bin/env bash
# Capture a machine-readable environment manifest for reproducibility (P2 #10).
# Records the exact toolchain, hardware, git state and dependency versions that a run
# was produced on. Usage: manifest.sh [OUTPUT.json]   (default: stdout)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
fi
JAVA="$JAVA_HOME/bin/java"

jstr() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }   # JSON-escape a string
field() { printf '  "%s": "%s"' "$1" "$(jstr "$2")"; }

java_ver="$("$JAVA" -version 2>&1 | head -1 | sed 's/"//g')"
default_gc="$("$JAVA" -XX:+PrintFlagsFinal -version 2>/dev/null \
    | awk '/true/ && /Use(G1|Z|Shenandoah|Parallel|Serial|Epsilon)GC/ {print $2}' | paste -sd, -)"
gcc_ver="$(gcc --version 2>/dev/null | head -1 || echo 'n/a')"
mvn_ver="$(cd "$ROOT" && mvn -v 2>/dev/null | head -1 || echo 'n/a')"
git_commit="$(cd "$ROOT" && git rev-parse HEAD 2>/dev/null || echo 'n/a')"
git_branch="$(cd "$ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'n/a')"
git_dirty="$(cd "$ROOT" && [ -n "$(git status --porcelain 2>/dev/null)" ] && echo true || echo false)"
cpu_model="$(awk -F: '/model name/{print $2; exit}' /proc/cpuinfo 2>/dev/null | sed 's/^ //')"
nproc_n="$(nproc 2>/dev/null || echo '?')"
mem_total="$(awk '/MemTotal/{printf "%.1f GiB", $2/1048576}' /proc/meminfo 2>/dev/null || echo '?')"
os_rel="$(. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME" || uname -s)"
kernel="$(uname -sr)"

# Dependency versions from the parent POM <properties>.
dep() { grep -oP "(?<=<$1>)[^<]+" "$ROOT/pom.xml" 2>/dev/null | head -1; }

{
  echo "{"
  field "timestamp" "$(date -Is)"; echo ","
  field "git_commit" "$git_commit"; echo ","
  field "git_branch" "$git_branch"; echo ","
  field "git_dirty" "$git_dirty"; echo ","
  field "os" "$os_rel"; echo ","
  field "kernel" "$kernel"; echo ","
  field "cpu_model" "${cpu_model:-unknown}"; echo ","
  field "cpu_count" "$nproc_n"; echo ","
  field "mem_total" "$mem_total"; echo ","
  field "java_home" "$JAVA_HOME"; echo ","
  field "java_version" "$java_ver"; echo ","
  field "default_gc" "${default_gc:-unknown}"; echo ","
  field "gcc" "$gcc_ver"; echo ","
  field "maven" "$mvn_ver"; echo ","
  field "dep_jmh" "1.37"; echo ","
  field "dep_bytebuddy" "1.15.11"; echo ","
  field "dep_slf4j" "$(dep slf4j.version)"; echo ","
  field "dep_logback" "$(dep logback.version)"; echo ","
  field "dep_reflections" "$(dep reflections.version)"; echo ","
  field "dep_snakeyaml" "$(dep snakeyaml.version)"; echo ","
  field "compiler_release" "$(dep maven.compiler.release)"; echo
  echo "}"
}
