# Real Paper rank menu tests

The GitHub E2E job starts a disposable MySQL service, supplies its synthetic
password through `ARC_RANKS_MYSQL_PASSWORD`, and runs `./gradlew plugwrightTest`.
Paper 26.1.2, Plugwright 2.0.4, Node 22.14.0, LuckPerms 5.5.17, Vault 1.7.3,
Redis 7.4 and RedisEconomy 4.5.12 are pinned in the build. CI builds the real
ARC provider from the pinned source revision before starting Paper. Paper binds
to 127.0.0.1:25565; all runtime files are recreated under `build/plugwright`
and logs are retained as CI artifacts.

The test waits for the database-backed rank profile to load, checks its lore,
navigates into progression paths and returns to the passport. Promotion remains
in SHADOW mode and contracts are disabled in the E2E fixture. The real Vault
and RedisEconomy providers are loaded to satisfy ARC's bootstrap contract; this
journey does not mutate balances or exercise reward delivery.
Existing unit and MySQL integration suites remain separate. Follow AGENTS.md:
storage integration tests run in CI, not in local Docker.
