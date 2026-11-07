# The Integratable Transaction Server

Documentation coming soon.

## Building

Requires Java 24 and Maven 3.9 or later.

### Dependency checksums

Every artifact Maven resolves (dependencies and plugins) is verified against the SHA-256 checksums pinned in
`.mvn/checksums/checksums.sha256`, and the build fails on a mismatch or on an artifact without a pinned checksum.
After adding or upgrading a dependency or plugin, record the new checksums and commit the updated file:

```shell
mvn verify -Daether.artifactResolver.postProcessor.trustedChecksums.record=true
```

The set of resolved artifacts can differ between Maven versions, so record with the Maven version used in CI as well
as your local one if they differ.

IntelliJ IDEA syncs the project with its bundled Maven and also resolves the `site` lifecycle plugin and the sources of
dependencies. To keep the sync working, also record with IntelliJ's bundled Maven (in
`<IntelliJ>/Contents/plugins/maven-plugin/lib/maven3/bin/mvn` on macOS):

```shell
mvn verify -DskipTests site:help dependency:sources -Daether.artifactResolver.postProcessor.trustedChecksums.record=true
```
