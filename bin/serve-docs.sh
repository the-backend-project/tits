#!/bin/sh

# Builds the documentation site the same way as the GitHub Pages workflow, then
# serves it on http://localhost:8000 with live reload.
#
#   bin/serve-docs.sh                       # generate diagrams and serve
#   bin/serve-docs.sh -a localhost:8001     # arguments are passed to zensical serve, here another port
#   bin/serve-docs.sh --build               # generate diagrams and do a strict build into site/, like CI
#   SKIP_DIAGRAMS=1 bin/serve-docs.sh       # reuse the diagrams already in docs/images
#   PYTHON=python3.12 bin/serve-docs.sh     # create .venv with a given Python
#
# Requires Java, Maven, Graphviz (dot) and Python 3.10 or later. Zensical is
# installed into a virtual environment in .venv on first run. The newest
# python3.x on the PATH is used unless PYTHON is set.

set -e

cd "$(dirname "$0")/.."

# Zensical needs Python 3.10 or later. On older ones, pip silently installs a placeholder release.
supported() {
  "$1" -c 'import sys; sys.exit(sys.version_info < (3, 10))' 2>/dev/null
}

if [ -x .venv/bin/python ] && ! supported .venv/bin/python; then
  echo "Recreating .venv, which was created with Python $(.venv/bin/python -V 2>&1 | cut -d' ' -f2)" >&2
  rm -rf .venv
fi

if [ ! -x .venv/bin/zensical ]; then
  python=$PYTHON
  if [ -z "$python" ]; then
    for minor in 20 19 18 17 16 15 14 13 12 11 10; do
      if command -v "python3.$minor" > /dev/null; then
        python=python3.$minor
        break
      fi
    done
  fi
  python=${python:-python3}
  if ! supported "$python"; then
    echo "Python 3.10 or later is required, but $python is $("$python" -V 2>&1). Set PYTHON to a newer one." >&2
    exit 1
  fi
  rm -rf .venv
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
  exec .venv/bin/zensical serve "$@"
fi
