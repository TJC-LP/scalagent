# Release Preparation

Prepare the codebase for release version: $ARGUMENTS

## Instructions

Update all version references from current SNAPSHOT/version to the new release version.

### Files to Update

1. **`build.mill`** (~line 34)
   - Find: `Task.env.get("PUBLISH_VERSION").getOrElse("...")`
   - Update the fallback version string (remove -SNAPSHOT suffix)

2. **`package.json`** (line 3)
   - Update the `"version"` field (remove -SNAPSHOT suffix)

3. **`README.md`** (if not already updated)
   - Update Mill ivyDeps (~line 23)
   - Update sbt libraryDependencies (~line 30)
   - Update Maven version (~line 39)

### Verification Steps

After updating all files:

1. Run `./mill agent.compile` to verify compilation
2. Run `./mill agent.test` to verify tests pass
3. Verify no SNAPSHOT refs remain (except in docs/RELEASING.md examples):
   ```bash
   grep -r "SNAPSHOT" --include="*.scala" --include="*.mill" --include="*.json" . | grep -v RELEASING.md | grep -v "out/" | grep -v node_modules
   ```

### Commit

When complete, stage and commit with message:
```
chore(release): Bump version to $ARGUMENTS
```

### Tagging

After committing, create an **annotated tag** with release notes:

```bash
# Create annotated tag with release notes
git tag -a "v$ARGUMENTS" -m "$(cat <<'EOF'
Release $ARGUMENTS

- <summarize key changes>
EOF
)"

# Verify it's annotated (should print "tag", not "commit")
git cat-file -t "v$ARGUMENTS"
```

**Important**: Do NOT use `git tag v$ARGUMENTS` (without `-a`) - this creates a lightweight tag with no release notes.

### Push

Push the commit and tag to trigger the release workflow:

```bash
git push origin main
git push origin "v$ARGUMENTS"
```

The release workflow will:
1. Run tests
2. Publish to Maven Central
3. Create GitHub Release with the tag message as release notes

### Post-Release

After the release is published, bump to next SNAPSHOT version:

```bash
# Update build.mill and package.json to next version (e.g., 0.2.3-SNAPSHOT)
# Commit: chore: Bump to X.Y.Z-SNAPSHOT for development
```
