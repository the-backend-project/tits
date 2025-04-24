#!/bin/sh

# Support multiple architectures using the digest for the "manifest list".
# > docker buildx imagetools inspect mcr.microsoft.com/azure-sql-edge:1.0.7
docker run \
  --rm \
  --detach \
  --name=mssql \
  --publish=11433:1433 \
  --env=ACCEPT_EULA=Y \
  --env=SA_PASSWORD=A_Str0ng_Required_Password \
  mcr.microsoft.com/azure-sql-edge:1.0.7@sha256:1dcc88d2d9e555d0addb0636648d0da033206978d7c5c4da044c904a0f06f58b

docker run \
  --pull=never \
  --rm \
  --link mssql \
  -it \
  --env=SA_PASSWORD=A_Str0ng_Required_Password \
  "$(docker build -q test/mssql)"
