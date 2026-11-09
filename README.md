# The Integratable Transaction Server

Documentation is available at https://the-backend-project.github.io/tits/.

The [demo](demo) module is a hello world web application following the principles of the twelve-factor app, compiled
to a native executable. See [Cloud native demo](docs/cloud-native.md).

## Building

Requires Java 27 and Maven 3.9 or later.

### Dependency checksums

Every artifact Maven resolves (dependencies and plugins) is verified against the SHA-256 checksums pinned in
`.mvn/checksums/checksums.sha256`, and the build fails on a mismatch. CI also fails on an artifact without a pinned
checksum; locally such artifacts are allowed, so IntelliJ IDEA can sync the project (it does not expand
`${maven.multiModuleProjectDirectory}` in `.mvn/maven.config` and therefore never finds the checksums file). To check
for unpinned artifacts locally like CI does, add `-Daether.artifactResolver.postProcessor.trustedChecksums.failIfMissing=true`.

After adding or upgrading a dependency or plugin, record the new checksums and commit the updated file:

```shell
mvn verify -Daether.artifactResolver.postProcessor.trustedChecksums.record=true
```

The set of resolved artifacts can differ between Maven versions, so record with the Maven version used in CI as well
as your local one if they differ.

## Documentation

The documentation site is built with [Zensical](https://zensical.org) from the Markdown in [docs](docs), with state
diagrams generated from the [templates](templates) module. To preview it locally with live reload on
http://localhost:8000:

```shell
bin/serve-docs.sh
```

This requires Graphviz (`dot`) and Python 3.10 or later in addition to the build requirements above. Zensical is
installed into a `.venv` on first run. Set `PYTHON` to choose another interpreter, set `SKIP_DIAGRAMS=1` to reuse the
diagrams already generated in `docs/images`, or pass `--build` to do a strict build into `site` like CI does.
