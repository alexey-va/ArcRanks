# AGENTS.md — ArcRanks

Standalone Kotlin/Paper plugin for RusCrafting permanent rank progression.

- Read `ARCHITECTURE.md` before changing rank authority, persistence,
  LuckPerms mutation, progress metrics, or promotion recovery.
- Target Paper/Purpur 1.21.11, Java 25, Kotlin 2.3.0, and arc-core 2.7.3.
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
- For the fast developer lane run `./gradlew shadowJar`. Run a focused unit test
  with `./gradlew test --tests '<fully-qualified-test-pattern>' shadowJar` when
  the change needs it. The arc-core consumer verifier and visual preview are
  opt-in checks; full `clean check shadowJar` and the MySQL integration suite
  belong to CI or an explicitly requested validation run.
- Deployment, production config edits, permission rebalance, server restarts,
  and remote repository creation require separate explicit authorization.
