#!/bin/sh

# Support multiple architectures using the digest for the "manifest list".
# > docker buildx imagetools inspect postgres:18.4
docker run \
  --rm \
  --detach \
  --name=postgres \
  --publish=15432:5432 \
  --env=POSTGRES_PASSWORD=A_Str0ng_Required_Password \
  postgres:18.4@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636

# Connect over TCP, as the server only listens on its Unix socket while the image initialises it
until docker exec --env=PGPASSWORD=A_Str0ng_Required_Password postgres psql -h 127.0.0.1 -U postgres -c 'SELECT 1' > /dev/null 2>&1; do
  echo "postgres init: server not yet ready"
  sleep 1
done

docker exec -i --env=PGPASSWORD=A_Str0ng_Required_Password postgres \
  psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 < test/postgres/init.sql
echo "postgres database initialized"
