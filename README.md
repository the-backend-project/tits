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

IntelliJ IDEA does not expand `${maven.multiModuleProjectDirectory}` when it reads `.mvn/maven.config`, so its Maven
sync cannot find the checksums file and reports every dependency as unresolved. Untick *Settings → Build, Execution,
Deployment → Build Tools → Maven → Use settings from .mvn/maven.config* to let IntelliJ sync without checksum
verification. Builds from the command line and in CI still verify the checksums.
