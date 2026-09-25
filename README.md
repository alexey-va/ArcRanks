# ArcRanks

Standalone RusCrafting Paper plugin for permanent cross-server ranks and six
independent specialization paths. It uses the existing LuckPerms groups as the
permission compatibility layer, shared MySQL as the progression authority, and
`arc-core` 2.7.13 for lifecycle, configuration, localization, SQL, logging,
scheduling, health, and testing.

The product rule is deliberately permissive: interesting public mechanics are
available from the first rank. Later ranks improve capacity, convenience,
rewards, presentation, and specialization depth; they are not a wall in front
of the server.

## Player flow

Native path details keep the overview, complete activity list, progress, and focus
explanation in separate body blocks. The progress bar shows filled and remaining
segments alongside the current value, goal, and percentage. Button status and
name are joined with a space: line-feed separators belong only in dialog bodies.
Locale multiline values use real YAML newlines; single-quoted `\n` stays literal
through the production locale renderer.

Player chat notifications use the existing `arc:rank_chevron_large` glyph
(U+E52A, height 27, ascent 26, advance 27) beside a three-row block. There is no
added left inset and the icon-to-text gap is 3 GUI pixels. `RankLocale.chat` owns
this presentation; dialogs, action bars, console output and long command help
keep their existing layouts. Chat-specific copy lives under `chat` in both
locale files. Wrapping retains click/hover events and never truncates long
custom text. Short one-line bodies split at a word boundary into two body rows
so the heading and body fill three text rows. One-word custom bodies stay intact
without an empty filler row. Quest completion shows its title, quest name and actual rewards.

- `/rank` opens a warm 45-slot rank and progression menu.
- `/rank dialog` opens the native Paper dialog variant used by ARC's `/menu`
  help hub. It covers ranks, paths, contracts, perks, weekly kits, promotions,
  and permission-gated operator actions while retaining the inventory menus.
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

## Celebration scenes

Quest completion and rank promotion use the existing `celebration.routes` and
`celebration.scenes` settings. Version 0.15.0 adds staged assembly, reveal and
dissolve, with full-bright stained-glass display structures, moving item casts,
color-transition particles and three timed sound cues. Existing materials,
custom models, colors, durations, display TTLs and audience limits remain in
effect; no config migration or reward changes are required.

| Recipe | Structure |
| --- | --- |
| BURST | Opening floor seal and eight rising petals |
| HELIX | Two counter-wound crystal staircases |
| CROWN | Assembling crown band and rising prongs |
| STARFALL | Staggered meteor shafts joining an overhead star |
| ORBIT | Two tilted, counter-rotating armillary hoops |
| ASCENSION | Low iris feeding a twisting column around the rank badge |
| RIBBON | Two spreading fans forming feathered wings |
| FIREWORK_FINALE | Solar halo, igniting rays and timed fireworks |

`/rank admin effects list`, `/rank admin effects play <scene>` and
`/rank admin effects all` preview the configured scenes with permission
`arcranks.admin.effects`. The sequence waits for each full scene plus a short
gap. A new preview, real completion, teleport, quit or plugin shutdown cancels
pending previews; a configuration generation change stops them before playback.

Each scene uses at most 16 decorative BlockDisplays plus the existing 1–8
item/text displays; `display.type: NONE` creates neither. The existing
particle-count limit remains the total per frame. Displays are non-persistent,
visible only to the bounded nearby audience and removed on scene expiry,
replacement, teleport, quit, reload-generation change or plugin shutdown.
Follow-player scenes keep their initial facing so camera movement does not
whip the wings or halo around. Fireworks launch at the reveal and cannot damage
entities. No blocks are changed and no display represents a collectible item.

Ownership: `presentation/CelebrationCatalog.kt` parses the catalog and keeps
the base trajectories; `CelebrationChoreography.kt` computes pure timed poses;
`CelebrationDisplayRenderer.kt` applies centered transforms;
`PromotionCelebration.kt` owns scheduling, audience and cleanup. These paths
are under `src/main/kotlin/ru/ruscrafting/ranks/`.

Focused verification:

```sh
./gradlew test --tests ru.ruscrafting.ranks.presentation.CelebrationCatalogTest --tests ru.ruscrafting.ranks.presentation.CelebrationChoreographyTest --tests ru.ruscrafting.ranks.presentation.CelebrationDisplayRendererTest --tests ru.ruscrafting.ranks.presentation.PromotionCelebrationPreviewTest
./gradlew shadowJar
```

These checks cover geometry, display-adapter calls, preview cancellation and
packaging. MockBukkit supplies the registries; recording display doubles cover
its missing viewer-visibility API. The appearance, client
interpolation and resource-pack models still need an in-client preview after
an authorized restart; disk JAR delivery alone does not activate new code.

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
- typed celebration scenes, event routes, personal title/toast/sound feedback,
  bounded nearby particles, moving display-entity topologies, fireworks, and
  broadcast enablement (the sanitized console command itself remains fixed in
  code); display origins follow the player by default and reconcile nearby
  viewers as they move;
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

## Progression visibility (0.16.0)

The ordinary ARC sidebar exposes the current rank and `/rank`.
`/quests` opens the daily quest board directly; `/rank quests` remains compatible.
`quest_board_header` and `quest_board_1..3` publish up to three distinct unfinished
quests, one per row, with the pinned quest first. Each goal and chain step has
a localized `short-name` for the HUD; menus retain the full `name`. Custom quests
without a short label fall back to their full name. Labels reserve room for the
complete counter within a 27-character row, using an ellipsis only as a fallback.
Empty rows disappear; legacy
`quest_line_1..4` and `quest_compact` still describe only the saved pin. Unpinning
keeps the daily overview visible; the explicit Off mode hides quest HUD output.
The tracker refreshes a new UTC day even when every previous goal was completed.
The source owner is `quest/QuestHud.kt` and `quest/QuestTracker.kt`; ARC owns the
sidebar layouts in `modules/scoreboard.yml` and their ops runtime mirrors.

`service/RankReminderService.kt` checks flushed, authoritative progression once
per minute. The first check after `reminders.first-delay-seconds` (default 60)
silently records the session baseline: joining does not send a personal hint,
even if a rank was already available. General progress reminders are removed.
A rank that becomes available during the session gets a notification with a
link to `/rank`; repeats use `reminders.cooldown-seconds` (default 1800).
Both timings reload live; quit and reload invalidate in-flight callbacks.
Promotion stays an explicit player action. Daily quests are assigned and paid
automatically; these presentation changes do not alter quantities or rewards.
Confirmed payouts retain the display-entity celebration scenes. An uncertain
provider outcome deliberately stays in RECOVERY for operator investigation;
no notification, reconnect or HUD refresh may blindly replay that payment.

## Development build and verification

```bash
./gradlew shadowJar
```

For a focused change, run the relevant unit test explicitly, for example
`./gradlew test --tests '*PromotionServiceTest' shadowJar`. Consumer architecture verification
and visual preview are opt-in commands:
`python3 ../arc-core/scripts/verify_consumer_architecture.py .` and
`./scripts/render-visual-preview --ops-root ../.deploy-ruscrafting-ops`.
Full `clean check shadowJar` verification and the disposable MySQL suite are
owned by CI or an explicitly requested validation run. The final verification
counts and visual-preview coverage for each release are reported by CI and the
release handoff.

Do not run `integrationTest` locally. The disposable MySQL suite is owned by
the CI integration job.

The production artifact is `build/libs/ArcRanks-0.16.4.jar`. Deployment and the
LuckPerms permission rebalance are separate reviewed operations; this source
checkout does not mutate production.

See [ARCHITECTURE.md](ARCHITECTURE.md) for authority and failure semantics.
