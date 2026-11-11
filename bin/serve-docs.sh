#!/bin/sh

# Builds the documentation site the same way as the GitHub Pages workflow, then
# serves it on http://localhost:8000 with live reload.
#
#   bin/serve-docs.sh            # generate diagrams and serve
#   bin/serve-docs.sh --build    # generate diagrams and do a strict build into site/, like CI
#   SKIP_DIAGRAMS=1 bin/serve-docs.sh   # reuse the diagrams already in docs/images
#
# Requires Java, Maven, Graphviz (dot) and Python 3.10 or later. Zensical is
# installed into a virtual environment in .venv on first run.

set -e

cd "$(dirname "$0")/.."

python=${PYTHON:-python3}
if [ ! -x .venv/bin/zensical ]; then
  "$python" -m venv .venv
  .venv/bin/pip install --quiet --upgrade pip zensical
fi

if [ -z "$SKIP_DIAGRAMS" ]; then
  mvn -B -q package -am -pl templates -DskipTests
  java -jar templates/target/templates-*-SNAPSHOT.jar > /dev/null
fi

if [ "$1" = "--build" ]; then
  exec .venv/bin/zensical build --strict
else
  exec .venv/bin/zensical serve
fi
