#!/bin/sh
# Build the devnet image and start pool-a, pool-b, pool-c. Exits 0 only when
# pool-b and pool-c each have an established TCP connection on port 8001.
set -eu
cd "$(dirname "$0")"

if ! docker info >/dev/null 2>&1; then
    echo "docker daemon is not listening on ${DOCKER_HOST:-unix:///Users/slogen/.docker/run/docker.sock}" >&2
    exit 1
fi

docker compose build pool-a
docker compose up -d

ready() {
    docker compose logs "$1" 2>/dev/null | grep -q "Block Store init."
}

i=0
while ! ready pool-a || ! ready pool-b || ! ready pool-c; do
    i=$((i + 1))
    if [ "$i" -gt 150 ]; then
        echo "a pool did not reach Block Store init" >&2
        docker compose ps >&2
        docker compose logs --tail 40 >&2
        exit 1
    fi
    sleep 2
done

# /proc/net/tcp state 01 is ESTABLISHED. 1F41 is port 8001.
peered() {
    docker compose exec -T "$1" awk 'NR>1 && $4=="01" && ($2 ~ /:1F41$/ || $3 ~ /:1F41$/)' /proc/net/tcp \
        | grep -q .
}

i=0
while ! peered pool-b || ! peered pool-c; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
        echo "pool-b or pool-c has no established connection on port 8001" >&2
        docker compose exec -T pool-a cat /proc/net/tcp >&2 || true
        exit 1
    fi
    sleep 2
done

echo "three pools up, pool-b and pool-c peered on 8001"
