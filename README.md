# ArcRanks

Standalone RusCrafting Paper plugin for permanent cross-server ranks and six
independent specialization paths. It uses the existing LuckPerms groups as the
permission compatibility layer, shared MySQL as the progression authority, and
`arc-core` 2.4.5 for lifecycle, configuration, localization, SQL, logging,
scheduling, health, and testing.

The product rule is deliberately permissive: interesting public mechanics are
available from the first rank. Later ranks improve capacity, convenience,
rewards, presentation, and specialization depth; they are not a wall in front
of the server.

## Player flow

- `/rank` opens a warm 45-slot rank and progression menu.
- `/rank why` shows the closest useful next action.
- `/rank benefits` explains what the next milestone improves.
- `/rank focus <path>` changes presentation only; all six paths keep counting.
- `/rank contracts` opens three deterministic weekly personal goals. Every
  card names the exact actions, target, path progress, money, tokens, item,
  and weekly stamp. The active screen reads left-to-right as task, progress,
  and reward; each choice has one free refresh and no streak penalty.
- `/rank perks` manages two freely switchable slots with 18 enhancements:
  three mastery-tier choices for each specialization path.
- `/rankup` performs a durable, retryable LuckPerms promotion when active mode
  is enabled. The bundled configuration starts in `SHADOW` mode.

Permanent ranks are `settler`, `peasant`, `citizen`, `artisan`, `knight`,
`baron`, `count`, `prince`, and `caesar`. Their compatibility LuckPerms groups
remain configurable in `ranks.yml`.

The rank menu is the hub for ranks, contracts, and perks. Product telemetry is
coalesced in bounded memory and written in one asynchronous batch per minute;
gameplay listeners never query MySQL or emit per-player metric labels. Operators
with `arcranks.admin.analytics` can inspect cached 7/14/30-day funnel reports.
Operators with `arcranks.admin.contract` see a clearly marked side control in
the active-contract menu and may run `/rank admin contract complete [player]`.
Both routes only make the contract ready; its reward is still collected through
the ordinary player chest. Operators with `arcranks.admin.grant` also see
`Admin: complete next step` in the rank passport and may run
`/rank admin advance [player]`; it adds only the exact missing permanent
progress and never promotes the player. Operators with `arcranks.admin.kit`
see the weekly-kit reset control after a confirmed claim and may run
`/rank admin kit reset [player]`. Resetting does not remove delivered items and
therefore deliberately permits another claim; an audit row records the actor.

## Runtime requirements

- Paper/Purpur 1.21.11, Java 25
- LuckPerms 5.5
- shared MySQL 8 compatible database
- Vault economy, RedisEconomy, and ARC item presets for contract rewards
- `ARC_RANKS_MYSQL_PASSWORD` in the server process environment
- optional Vault economy for the trade path
- optional zAuctionHouse 4.0.1.3 for durable buyer-and-seller trade turnover
- optional EliteMobs 10.x for completed-dungeon exploration progress
- optional PlaceholderAPI for `%arcranks_*%`

Every backend uses the same JAR and database. Each backend has its own
`server-id`. Promotion mutates only direct LuckPerms parents
from the configured progression group set; the implicit `default` start rank
has no parent to remove. Donor, staff, and temporary parents are preserved.

## Configuration and safe reload

Operators with `arcranks.admin.reload` can apply supported changes with
`/rank admin reload`. The command parses and validates a new isolated snapshot
of `config.yml`, `ranks.yml`, `perks.yml`, `contracts.yml`, `weekly-kits.yml`,
and both locale files before touching the active generation. Fingerprints taken
before and after parsing must match, so a file edited mid-read is rejected rather
than published as a mixed generation. An invalid, byte-identical, restart-only,
or concurrently superseded candidate leaves the active snapshot unchanged.
Concurrent reload attempts are rejected as busy. Arc-core-owned `logging.yml` is
fingerprinted and reloaded independently, so a logging-only edit is not mistaken
for a no-op.

A successful live reload closes only ArcRanks inventories and invalidates only
the derived caches affected by the changed areas. One gameplay heartbeat drives
sampling, progress flush, and analytics flush through three `DynamicTickCadence`
counters; the counters read the active snapshot and retain elapsed carry when
their periods change. Arc-core's standard `runtime.reportHealthEvery` owns health
reporting. Unrelated reloads leave both schedules installed. A change to
`runtime.health-report-ticks` is the only live change that transactionally
reinstalls the runtime-owned gameplay heartbeat and health task, while the
gameplay cadence counters survive and keep their carry. The following areas are
live-reloadable:

- promotion mode; live gates for contracts, perks, and weekly kits (the perk
  gate also suspends their progress bonuses); locale selection; logging; and all
  player-facing locale text;
- progress buffer/sampling/flush tuning, collection sources and amounts
  (harvests, breeding, fishing, crafting, furnaces, enchanting, smithing,
  villager and auction deals, travel, discoveries, dungeons, building,
  decorations, nearby play, shared advancements, and rate-limited meaningful
  chat messages),
  eligible game modes, world/material filters, movement policy, and community
  threshold;
- analytics enablement, limits, flush period, report windows, default window,
  and summary cache lifetime;
- GUI materials, custom-model-data, auxiliary icons, and promotion feedback time;
  `gui.items` must contain exactly the supported keys, so typos fail reload;
- celebration title, sound, particles, fireworks, tier profiles, and broadcast
  enablement (the sanitized console command itself remains fixed in code);
- shutdown flush timeout and health-report period;
- rank requirements and benefits, mastery thresholds, existing perk tuning,
  contracts, and weekly-kit contents/settings while their persistent identities
  and rank topology remain unchanged.

The following paths are restart-only. If any is changed, reload reports the
exact paths and applies none of that candidate:

- `server-id`;
- every `mysql.*` connection/pool field, including the resolved password;
- `runtime.startup-timeout-seconds`;
- rank IDs and each rank's `order` or LuckPerms `group`;
- the set of perk IDs;
- the set of rank IDs addressed by weekly kits.

Reload changes only this running plugin process and its in-memory configuration.
It does not deploy the JAR, edit runtime profiles, migrate MySQL, or synchronize
configuration to another backend.

## Build and verification

```bash
./gradlew --no-daemon test compileIntegrationTestKotlin shadowJar
python3 ../arc-core/scripts/verify_consumer_architecture.py .
./scripts/render-visual-preview --ops-root ../.deploy-ruscrafting-ops
```

The final verification counts and visual-preview coverage for each release are
reported by CI and the release handoff; this file intentionally does not pin a
stale test or surface count.

Do not run `integrationTest` locally. The disposable MySQL suite is owned by
the CI integration job.

The production artifact is `build/libs/ArcRanks-0.8.6.jar`. Deployment and the
LuckPerms permission rebalance are separate reviewed operations; this source
checkout does not mutate production.

See [ARCHITECTURE.md](ARCHITECTURE.md) for authority and failure semantics.
