# Repository Guidelines

## Project Structure & Module Organization
- `src/com/tjclp/scalagent/`: main Scala.js library (config, messages, macros, streaming, tools, core `ClaudeAgent`).
- `test/src/com/tjclp/scalagent/`: MUnit tests; shared helpers in `TestFixtures.scala`.
- `examples/`: runnable Scala.js examples.
- `build.mill`: Mill build definitions; `package.json`: JS deps for the SDK runtime.
- Generated output lands in `out/` (avoid manual edits).

## Build, Test, and Development Commands
- `bun install`: install JS dependencies (Bun is the preferred runtime).
- `./mill agent.compile` or `bun run build`: compile the Scala.js library.
- `./mill agent.test`: run the MUnit test suite.
- `./mill examples.run`: run the default example.
- `./mill examples.simple.run`: run a specific example by submodule, e.g. `./mill examples.hook.run`.
- `bun run run`: compile and execute the JS output for examples.
- `./mill examples.list`: list available example entry points.

## Coding Style & Naming Conventions
Use Scala 3 significant indentation with 2-space indents. Follow Scalafmt/Scalafix configs
(`.scalafmt.conf`, `.scalafix.conf`) when formatting; wildcard imports use `*` and imports are
grouped `com.tjclp`, `zio`, `scala`, `java`, then others. Packages are lowercase
(`com.tjclp.scalagent`); types are `PascalCase`, values/defs are `camelCase`. Keep files aligned
with their package paths.

## Testing Guidelines
Tests use MUnit and live under `test/src/...` with `*Spec.scala` naming. Add focused unit tests
for new APIs and config behavior; update `TestFixtures.scala` when shared setup is needed. Run
tests with `./mill agent.test`.

## Commit & Pull Request Guidelines
Commit messages are short and imperative; many use a conventional prefix like `feat:`, `fix:`,
`docs:`, or `style:`—follow that pattern when it fits. PRs should include a brief summary,
motivation or linked issue, and the commands you ran (e.g. `./mill agent.test`). Update README or
examples when public APIs or usage patterns change.

## Configuration & Runtime Notes
Runtime examples require `ANTHROPIC_API_KEY`. When running inside Claude Code, auth is typically handled automatically; setting `ANTHROPIC_API_KEY` remains an optional override. Prefer Bun for JS tasks (`bun run ...`) rather than npm/node.
