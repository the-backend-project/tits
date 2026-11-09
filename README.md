# The Integratable Transaction Server

Documentation is available at https://the-backend-project.github.io/tits/.

## Building

Requires Java 24 and Maven 3.9 or later.

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
