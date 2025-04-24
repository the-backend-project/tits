#!/usr/bin/env bash


initDatabase() {
  until /opt/mssql-tools/bin/sqlcmd -b -S mssql -U sa -P "$SA_PASSWORD" -i /init.sql; do
    echo "mssql init: server not yet ready (status $?)"
    sleep 1
  done
  echo "mssql database initialized"
  touch /tmp/initialized
}

initDatabase
