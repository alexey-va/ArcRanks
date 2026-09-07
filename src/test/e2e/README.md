# Real Paper rank menu tests

The GitHub E2E job starts a disposable MySQL service, supplies its synthetic
password through `ARC_RANKS_MYSQL_PASSWORD`, and runs `./gradlew plugwrightTest`.
Paper 1.21.11, Plugwright 2.0.4, Node 22.14.0, LuckPerms 5.5.17 and Vault 1.7.3
are pinned in the build. Paper binds to 127.0.0.1:25565; all runtime files are
recreated under `build/plugwright` and logs are retained as CI artifacts.

The test waits for the database-backed rank profile to load, checks its lore,
navigates into progression paths and returns to the passport. Promotion remains
in SHADOW mode. No economy provider is installed: promotions, rewards, native
dialogs and cross-server persistence are outside this scenario's coverage.
Existing unit and MySQL integration suites remain separate. Follow AGENTS.md:
storage integration tests run in CI, not in local Docker.
