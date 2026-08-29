# ArcRanks

Standalone RusCrafting Paper plugin for permanent cross-server ranks and six
independent specialization paths. It uses the existing LuckPerms groups as the
permission compatibility layer, shared MySQL as the progression authority, and
`arc-core` 2.1.3 for lifecycle, configuration, localization, SQL, logging,
metrics, scheduling, health, and testing.

The product rule is deliberately permissive: interesting public mechanics are
available from the first rank. Later ranks improve capacity, convenience,
rewards, presentation, and specialization depth; they are not a wall in front
of the server.

## Player flow

- `/rank` opens a warm 54-slot progression passport.
- `/rank why` shows the closest useful next action.
- `/rank benefits` explains what the next milestone improves.
- `/rank focus <path>` changes presentation only; all six paths keep counting.
- `/rank contracts` opens three deterministic weekly personal goals with one
  free refresh and no streak penalty.
- `/rank perks` manages two freely switchable specialization enhancements.
- `/rankup` performs a durable, retryable LuckPerms promotion when active mode
  is enabled. The bundled configuration starts in `SHADOW` mode.

Permanent ranks are `settler`, `peasant`, `citizen`, `artisan`, `knight`,
`baron`, `count`, `prince`, and `caesar`. Their compatibility LuckPerms groups
remain configurable in `ranks.yml`.

The passport is the hub for ranks, contracts, and perks. Product telemetry is
coalesced in bounded memory and written in one asynchronous batch per minute;
gameplay listeners never query MySQL or emit per-player metric labels. Operators
with `arcranks.admin.analytics` can inspect cached 7/14/30-day funnel reports.

## Runtime requirements

- Paper/Purpur 1.21.11, Java 25
- LuckPerms 5.5
- shared MySQL 8 compatible database
- `ARC_RANKS_MYSQL_PASSWORD` in the server process environment
- optional Vault economy for the trade path
- optional PlaceholderAPI for `%arcranks_*%`

Every backend uses the same JAR and database. Each backend has its own
`server-id` and metrics port. Promotion mutates only direct LuckPerms parents
from the configured progression group set; the implicit `default` start rank
has no parent to remove. Donor, staff, and temporary parents are preserved.

## Build and verification

```bash
./gradlew --no-daemon test compileIntegrationTestKotlin shadowJar
python3 ../arc-core/scripts/verify_consumer_architecture.py .
./scripts/render-visual-preview --ops-root ../.deploy-ruscrafting-ops
```

Do not run `integrationTest` locally. The disposable MySQL suite is owned by
the CI integration job.

The production artifact is `build/libs/ArcRanks-0.1.0.jar`. Deployment and the
LuckPerms permission rebalance are separate reviewed operations; this source
checkout does not mutate production.

See [ARCHITECTURE.md](ARCHITECTURE.md) for authority and failure semantics.
