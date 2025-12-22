# Releasing Scalagent to Maven Central

This document describes how to release new versions of Scalagent to Maven Central.

## Prerequisites

### Sonatype Central Account

The `com.tjclp` namespace is already verified on Sonatype Central (shared with the XL project).

### GitHub Secrets

The following secrets must be configured in GitHub (Settings > Secrets and variables > Actions):

| Secret | Description |
|--------|-------------|
| `SONATYPE_USERNAME` | Sonatype Central user token |
| `SONATYPE_PASSWORD` | Sonatype Central password token |
| `PGP_SECRET` | Base64-encoded GPG private key |
| `PGP_PASSPHRASE` | GPG key passphrase |

These can be reused from the XL project if publishing under the same organization.

## Release Process

### 1. Prepare the Release

Ensure all changes are merged to `main` and tests pass:

```bash
git checkout main
git pull origin main
./mill agent.test
```

### 2. Create an Annotated Tag

Create a tag following semantic versioning:

```bash
# For a release with notes
git tag -a v0.1.0 -m "Release 0.1.0

- Initial public release
- Type-safe Scala.js SDK for Claude Agent SDK
- ZIO-based streaming API"

# Or a simple release
git tag -a v0.1.0 -m "Release 0.1.0"
```

### 3. Push the Tag

```bash
git push origin v0.1.0
```

This triggers the GitHub Actions release workflow automatically.

### 4. Monitor the Release

1. Watch the workflow: https://github.com/TJC-LP/scalagent/actions
2. Check Sonatype Central portal for publication status
3. Wait 10-30 minutes for Maven Central sync

### 5. Verify Publication

Check that artifacts are available:

```bash
# Check Maven Central
curl -s "https://repo1.maven.org/maven2/com/tjclp/scalagent_sjs1_3/0.1.0/" | head -20
```

Or visit: https://repo1.maven.org/maven2/com/tjclp/scalagent_sjs1_3/

## Local Testing

Before releasing, you can test the publishing setup locally:

```bash
# Publish to local Ivy cache
./mill agent.publishLocal

# Verify artifacts
ls ~/.ivy2/local/com.tjclp/scalagent_sjs1_3/

# Check generated POM
cat ~/.ivy2/local/com.tjclp/scalagent_sjs1_3/0.1.0-SNAPSHOT/poms/scalagent_sjs1_3.pom
```

## Version Numbering

Follow [Semantic Versioning](https://semver.org/):

- **MAJOR** (1.0.0): Breaking API changes
- **MINOR** (0.2.0): New features, backward compatible
- **PATCH** (0.1.1): Bug fixes, backward compatible
- **Pre-release** (0.1.0-RC1): Release candidates

## Downstream Usage

After publishing, users can depend on Scalagent:

### Mill

```scala
def ivyDeps = Seq(
  mvn"com.tjclp::scalagent::0.1.0"
)
```

### SBT

```scala
libraryDependencies += "com.tjclp" %%% "scalagent" % "0.1.0"
```

### Maven

```xml
<dependency>
  <groupId>com.tjclp</groupId>
  <artifactId>scalagent_sjs1_3</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Troubleshooting

### "GPG signing failed"

Ensure the GPG key is properly exported and base64-encoded:

```bash
gpg --export-secret-key -a KEY_ID | base64 | pbcopy
```

### "Unauthorized" from Sonatype

Verify the Sonatype credentials are user tokens (not username/password). Generate tokens at https://central.sonatype.com/

### "Namespace not verified"

The `com.tjclp` namespace should already be verified. If not, verify ownership at Sonatype Central.

### Tests failing in CI

Run tests locally first:

```bash
bun install
./mill agent.test
```
