#!/usr/bin/env bash
# Start a local XDAGJ devnet node from the repository root.
# Fat JAR excludes network conf files (see src/assembly/executable-jar.xml),
# so we put target/classes (or src/main/resources) on the classpath first.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  elif [[ -x /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
    export JAVA_HOME="/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  fi
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v java >/dev/null 2>&1 && [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  echo "ERROR: Java 21 not found. Set JAVA_HOME to openjdk@21." >&2
  exit 1
fi

JAR="$(ls -1 "$ROOT_DIR"/target/xdagj-*-executable.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "$JAR" ]]; then
  echo "ERROR: executable jar not found. Run: mvn clean package -DskipTests" >&2
  exit 1
fi

CONF_DIR="$ROOT_DIR/target/classes"
if [[ ! -f "$CONF_DIR/xdag-devnet.conf" ]]; then
  CONF_DIR="$ROOT_DIR/src/main/resources"
fi
if [[ ! -f "$CONF_DIR/xdag-devnet.conf" ]]; then
  echo "ERROR: xdag-devnet.conf not found under target/classes or src/main/resources" >&2
  exit 1
fi

JAVA_OPTS=(
  --add-opens java.base/java.nio=ALL-UNNAMED
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED
  -Xms2g
  -Xmx2g
)

ARGS=(-d)
if [[ -n "${XDAGJ_WALLET_PASSWORD:-}" ]]; then
  ARGS+=(--password "$XDAGJ_WALLET_PASSWORD")
elif [[ $# -eq 0 ]]; then
  echo "TIP: set XDAGJ_WALLET_PASSWORD or pass --password <pwd>"
fi
ARGS+=("$@")

echo "Using JAR:  $JAR"
echo "Config CP:  $CONF_DIR"
echo "RPC:        http://127.0.0.1:10001"
echo "Telnet:     127.0.0.1:6001 (password from xdag-devnet.conf)"
echo "Data dir:   $ROOT_DIR/devnet (override with -f <path>)"
# target/classes first so network conf + freshly compiled classes win over the fat JAR.
exec java "${JAVA_OPTS[@]}" -cp "$CONF_DIR:$JAR" io.xdag.Bootstrap "${ARGS[@]}"
