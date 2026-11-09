#!/bin/sh
set -eu
cd /data
mkdir -p /data/logs

# node.whiteIPs is resolved once at startup. Wait until every pool name exists.
i=0
while ! getent hosts pool-a >/dev/null || ! getent hosts pool-b >/dev/null || ! getent hosts pool-c >/dev/null; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
        echo "pool DNS did not come up" >&2
        exit 1
    fi
    sleep 1
done

java -cp /opt/xdag:/opt/xdag/xdagj.jar WalletInit /data
exec java \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    -Xms512m -Xmx1536m \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+UseZGC \
    -cp /opt/xdag:/opt/xdag/xdagj.jar \
    io.xdag.Bootstrap -d -f /data
