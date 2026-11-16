---
name: serve-docs
description: Serve the documentation site locally with live reload on http://localhost:8000. Use when the user says "serve docs", "preview the docs", or invokes /serve-docs. Pass --build for a strict build into site/ like CI instead.
argument-hint: "[--build] [--skip-diagrams]"
---

Serve the documentation with `bin/serve-docs.sh`, which builds it the same way as the GitHub Pages workflow. On its
first run, the script installs Zensical into `.venv`. It also generates the diagrams from the templates unless
`SKIP_DIAGRAMS` is set.

1. **Check the tools.** The script needs `mvn`, `dot` (Graphviz) and Python 3.10 or later on the PATH. If one is
   missing, tell the user instead of guessing where it is installed, unless the user's own instructions say how to
   load it.

2. **Pick the mode from the arguments.**
   - No arguments: serve with live reload.
   - `--skip-diagrams`: set `SKIP_DIAGRAMS=1` to reuse the diagrams already in `docs/images`. This makes startup much
     faster when only Markdown has changed.
   - `--build`: run `bin/serve-docs.sh --build` in the foreground. It does a strict build into `site/` and exits.
     Report any warnings or errors. This is the check CI runs.

3. **Check whether a server is already running.** Run `lsof -nP -iTCP:8000 -sTCP:LISTEN`. If something is listening
   on port 8000, find out what it is (`ps -o command -p <pid>`, and `lsof -p <pid> -a -d cwd` for its directory).
   Often it is the docs server of another worktree. Tell the user, and offer to serve on the next free port instead.
   Don't stop the other server without asking.

4. **Serve.** Arguments after the script's name are passed to `zensical serve`, so `-a localhost:<port>` picks
   another port. `zensical serve` doesn't exit, so run the script as a background command. If the agent can't run
   commands in the background, or stops commands after a timeout (Mistral Vibe does both), don't start the server.
   Tell the user to run the command below in a terminal of their own instead, and stop here:

   ```sh
   bin/serve-docs.sh -a localhost:8000
   ```

   Then wait for the server in a separate command that exits once the server answers or the output shows an error,
   e.g. a loop around `curl -sf http://localhost:<port>/`. Generating the diagrams can take a minute the first time.
   When it is serving, tell the user the URL. Pages reload when files in `docs/` change.

5. **On failure**, show the error. The usual causes are a missing `dot` (Graphviz: `brew install graphviz`), no
   Python 3.10 or later on the PATH (the script picks the newest `python3.x`, or set `PYTHON`), or a templates build
   failure. A `.venv` made with an older Python is recreated automatically.

To stop the server, stop the background command.
