# AGENTS.md — ArcRanks

Standalone Kotlin/Paper plugin for RusCrafting permanent rank progression.

- Read `ARCHITECTURE.md` before changing rank authority, persistence,
  LuckPerms mutation, progress metrics, or promotion recovery.
- Target Paper/Purpur 1.21.11, Java 25, Kotlin 2.3.0, and arc-core 2.4.5.
- Keep the project on direct trunk `main`; do not create feature branches.
- Interesting public mechanics belong in the base permission bundle. Ranks may
  deepen or enhance them, never hide the server's identity behind grinding.
- Never reset rank or specialization progress. Seasonal state, if added later,
  must use separate tables and labels.
- A normal promotion accepts exactly one direct configured progression group.
  Never clear donor, staff, builder, moderation, or temporary parent nodes.
- Never put SQL credentials in Git, YAML, logs, diagnostics, or generated
  previews. Runtime reads the configured password environment variable.
- Player-facing text belongs in `lang/ru.yml` and `lang/en.yml` with exact key
  parity. GUI item names and lore must explicitly disable italics.
- Use `RankProgressApi` and a stable unique event ID for external awards; do
  not write progress tables from another plugin.
- Locally run only `./gradlew --no-daemon test compileIntegrationTestKotlin
  shadowJar`, the arc-core consumer verifier, and the visual preview renderer.
  Never run `integrationTest`, Testcontainers, or Docker locally.
- Deployment, production config edits, permission rebalance, server restarts,
  and remote repository creation require separate explicit authorization.
