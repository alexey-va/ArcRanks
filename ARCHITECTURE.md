# ArcRanks architecture

## Authority boundaries

- LuckPerms owns the permanent main-rank group.
- MySQL owns monotonic progress, selected focus, perk selection, contract
  cycles and claims, idempotent external events, product rollups, and the
  promotion saga.
- ArcRanks owns evaluation, commands, GUI, placeholders, and event sampling.
- Existing plugins keep ownership of their gameplay mechanics and permission
  nodes. ArcRanks describes benefits but never derives permissions from prose.

## Promotion safety

A healthy player is either on LuckPerms' implicit `default` group with no
direct progression parent, or resolves to the highest direct parent from the
configured progression set. Legacy CMI/ARC sync may leave cumulative lower-rank
parents on a player; those are accepted as one linear rank history rather than
misclassified as a conflict. Promotion first flushes local progress, reloads
authoritative values, persists `PREPARED`, replaces only configured progression
parents with the target group, verifies the resulting group, and persists
`COMPLETED`. Retrying resumes the same generation; donor, staff, temporary, and
other non-catalog groups are never cleared.

The plugin default remains `SHADOW`, which counts and renders progress without
changing LuckPerms. The production ops profile uses `ACTIVE` only for the
permission-gated preview audience after player-state diagnostics.

## Progress model

Every target rank requires active minutes plus `N` completed goals selected
from farming, industry, trade, exploration, building, and community. All paths
advance simultaneously. Missing optional providers mark only their path as
unavailable. The evaluator blocks only if fewer than `N` paths remain.

Local high-frequency counters use a bounded coalescing buffer. A successful
promotion always flushes that player's buffer before evaluation. External
plugins use `RankProgressApi`, whose durable `source + eventId` identity makes
delivery idempotent across retries and servers.

## Contracts and perks

Contract offers are deterministic from player, Monday-UTC cycle, generation,
reroll nonce, and path. Accept captures the authoritative metric baseline.
Claim locks the active contract, checks flushed progress, inserts an idempotent
reward event, adds the reward, marks the stamp, and advances the generation in
one transaction. The money, RedisEconomy token, and ARC item components are
then delivered through the shared arc-core one-time-use ledger. Each component
has a deterministic cross-server identity; rejected provider calls remain
pending, while unknown outcomes enter explicit recovery instead of risking an
automatic duplicate. Pending rewards resume when the player joins. Missing a
week has no penalty.

`arcranks.admin.contract` exposes a deliberately separate test operation. It
sets an audited `admin_completed_at/admin_completed_by` marker on the locked
active contract but never changes path progress, inserts a reward event, or
marks the contract claimed. The ordinary claim transaction remains the only
way to receive the reward, so an administrator can test the real player flow
without manufacturing target progress or creating a second reward path.

Every player has two perk slots. Mastery unlocks enhancements to existing play,
never base mechanics, combat, money, or protection bypasses. Counter bonuses
use a per-player fractional accumulator and cached perk selection; wealth keeps
its high-water sampling semantics.

## Product telemetry

Gameplay records merge only into bounded in-memory maps. One idempotent batch
per minute updates hourly event rollups and daily player funnel flags. A failed
write keeps the exact batch UUID for an isolated retry; events received while
that write is in flight remain in the next batch. UUIDs
exist only in the daily SQL cohort table and never in Prometheus labels.
Operator summaries run on demand through `SqlRuntime` and are cached for 60
seconds. Shutdown drains queued batches within the existing five-second bound.
Telemetry failure does not block ranks, contracts, perks, or menus.

## Threading and lifecycle

JDBC runs exclusively through `SqlRuntime`. Bukkit inventory, title, sound,
and command callbacks return through the arc-core lifecycle task scope. The
runtime owns SQL, repeating telemetry flushes, and health reporting. Startup
fails closed if MySQL or LuckPerms is unavailable; Vault and PlaceholderAPI
are optional.

## Localization and GUI

`lang/ru.yml` and `lang/en.yml` must keep exact leaf-key parity. Catalog keys,
command surfaces, and GUI names/lore are validated at startup and in tests.
GUI item roots explicitly disable italics. The source theme uses clean warm
amber `#f4bd6a`, cream body `#fff0d8`, neutral structure `#8c8c8c`, and
semantic green/warning/error colors; runtime may replace the portable filler
with `arc:background`.

## Daily quests

`daily-quests.yml` owns a 76-template pool, concrete objective counters, path
bonuses, coin rewards and a rank-to-count map (6/8/10/12/14/16/18/20/21 by default).
Selection is deterministic by player and UTC date and interleaves paths. At most
one quest becomes rare (25% daily chance, doubled target, 150 coins and 1 token);
ordinary quests start at 50 coins. `scaling-by-rank` independently controls target,
money and path-bonus integer percentages and rare token quantities. Defaults
scale targets from 100% to 300%, coins from 100% to 350%, path bonuses from 100%
to 200%, rare tokens from 1 to 3. Positive fractions round up; a rare goal doubles
the already scaled target. Counts and scaling are frozen in the same assignment.
All quantities and chances are configurable. Material/species-qualified actions
feed both a matching specific goal and an assigned generic goal; unrelated
variants never receive credit. Ordinary collectors emit actual Bukkit material
or entity identifiers; the bounded buffer accommodates them during an outage.
A promotion or reload changes the next assignment; today's quantities remain frozen.
A deliberate unfinished-card replacement keeps the original money and rarity. Quests assist existing permanent paths; promotion requirements and
active-time gates stay authoritative.

Ordinary gameplay counts harvest, fish, breed, craft operations, smelted items,
enchant, smith, villager trade, placed blocks, decoration, travel, advancements,
active minutes and community minutes. Existing collector eligibility and repeat
gates apply. Quest counters use concrete action quantities, independent of perk
multipliers. Builder committed operations count tool uses and accepted positions.
EliteMobs completed runs count their original participants still present at the
finish; the Mines goal matches `em_id_the_mines`, not a client-entered name.
ArcFarms `WorkShiftCompletedEvent` counts real farm/lumber/mine contributors.
ARC `ResourceContractCommittedEvent` counts committed submissions and quantity.
These optional public events are consumed reflectively without new hard plugin
dependencies; provider updates must accompany activation of their goals across
the network. An unavailable integration logs a warning; it does not fabricate
progress. Keep unavailable activities out of the configured network pool.

Migration 12 freezes assignments under a per-player board lock and retains a
separate reward outbox. Local progress, completion, permanent path bonuses and
currency obligations commit together. Rollover replaces only current goals;
pending rewards survive. External `RankQuestApi` events have source-scoped,
stable IDs and persistent deduplication; a duplicate does not count again even
on another day. Provider emission is not a transactional network outbox: a
crash before emission can lose an observation, and bounded retries cannot
promise recovery after a prolonged outage. Local buffered raw actions retain
the existing crash/ambiguous-commit limitations. Dedup and reward history are
retained; do not prune IDs without a coordinated source replay horizon.

`RankRewardDeliveryService` shares the existing one-time ledger with legacy
contracts, using distinct `daily` and `contract` identities. Confirmed failures
remain pending; uncertain provider outcomes go to recovery, never blind replay.
Delivery runs after progress and on join/startup; confirmed grants notify the
player. New weekly rank offers are retired. Previously accepted contracts and
pending grants remain available through `/rank legacy-contracts`; ARC resource
contracts are independent and remain active.

`DailyQuestMenu` displays automatic rewards and uses 3..5 chest rows for 1..21 cards, seven per
row. `/rank quests`, `/rank daily` and `/rank contracts` open the same board,
as does the existing contracts entry in the passport. The root menu does not
grow. Header status uses the configured anchor; back/refresh anchors shift with
the footer. Card slots follow `DailyQuestLayout`; the old three-card region and
extra passport daily slot are compatibility configuration, no longer rendered.
All async rendering checks holder identity and config generation. Opening and
refreshing flush buffered actions; no accept or reward-claim click is needed.

Focused verification includes quest catalog/geometry, payout identity, progress
buffer, contract compatibility and configuration tests. MySQL integration tests
cover completion, rollback, assignment freeze, rollover and event deduplication;
compile them locally and run them in CI or explicit integration validation.


## Advanced daily goals (0.11)

A card can be a counter, ordered `chain`, `all` checklist, `any` alternative, or
`distinct` collection. Plans have at most eight non-overlapping objectives.
Each step tracks its own quantity; repeated actions cannot fill another entry.
Only the current chain step receives new actions, with no retroactive credit.
Local buffered actions retain sequence across metrics and retries. A completed
card creates one outbox reward and one permanent path bonus, never one per step.

Migration 13 stores the plan, step values, challenge state, rank/scaling snapshot,
replacement allowance, 30-day assignment history and permanent once-only markers.
Existing boards and pending payouts survive; newly introduced settings apply at
the next UTC assignment. Selection prefers different families and recent gaps,
with up to three advanced cards. Higher ranks unlock composite routes in addition
to larger counters. Missing optional provider status is unavailable, never false.

Right-click replaces an unfinished card up to twice per day by default. It does
not change money, tokens or rarity and cannot select another card already issued
that day. An unavailable contextual objective may be replaced without consuming
that allowance. Operators can turn replacements, mechanic families, individual
quests and optional challenge bonuses off in `daily-quests.yml`.

A flawless dungeon card gives an optional 25% coin bonus only if every required
completion occurred without the player's death. Team shifts require at least two
real ArcFarms contributors. Town delivery cards require an open ARC resource order
with quantity, funding and a window lasting through the current UTC day.

ArcVotes 0.4.1 publishes persisted, UUID-resolved vote confirmations. Opening
`/vote` alone gives no progress; pending vote delivery can replay the original
identity on join. Social cards direct players to `/discord` or `/telegram` only
when ProxyARC authoritatively reports that platform unlinked. Both occupy normal
daily slots and reward once for that quest ID; retain their canonical IDs.
ProxyARC responds through the existing ARC Redis connection with Minecraft UUID
and linked/unlinked/unavailable states only. No external account IDs or linking
codes cross into Paper. ArcRanks checks pending cards on join, menu refresh and
periodically while online; confirmed linking completes them automatically, even
if it happened while offline. Stable platform/player event identities also stop
unlink/relink replay. Unavailable providers never fabricate completion.

The source release needs coordinated ArcRanks, ArcVotes and ProxyARC artifacts,
plus the earlier ArcFarms and ARC public event releases. Building or publishing
these sources does not activate the feature on any server.

Migration 13 checks each new column before its DDL and can resume after partial
MySQL commits. A startup compatibility gate rejects an upgrade when orphaned
v11 daily progress exists for the current UTC day. Those early source candidates
used incompatible fixed goals without currency snapshots: retain that release
until the normal UTC rollover, or migrate its assignments explicitly before
activation. The gate never deletes those rows or resets permanent progress.


## Quest guidance and focus (0.11.1)

Left click toggles one tracked unfinished daily quest; right click retains replacement.
Migration 14 stores its UTC day and quest ID in `arc_ranks_quest_tracking`.
`QuestTracker` restores it on join, displays changed persisted progress in the action
bar, and clears completed, replaced or expired selections. Composite quests show
an unfinished step and its partial progress. `tracking.enabled` and
`tracking.interval-seconds` (default 3) control this optional HUD.

`DailyQuestHints` maps objective sources to RU/EN action hints, including server
work, contracts and confirmed social actions. The menu does not add passport slots.
New boards read saved `selected_focus` inside the assignment transaction.
`selection.focus-percent` (default 35) reserves part of the eligible selection for
that path, retaining other paths and existing family/advanced limits with fallback.
Existing boards and replacements are not rerolled by changing focus. Payouts,
rank scaling, rarity chances, token limits and daily counts are unchanged.


## Compact quest HUD and diagnostics (0.12.1)

The same persisted pin drives three player-selected modes: scoreboard (default),
action bar, and off. Clicking the daily menu summary cycles the mode. Migration
15 stores this preference separately from the daily pin, so completion and UTC
rollover do not reset a player's choice. `QuestTracker` owns main-thread updates
and publishes immutable strings for asynchronous PlaceholderAPI reads. TAB
remains the scoreboard owner; ArcRanks never enables or replaces a sidebar.

`arcranks_quest_active`, `quest_context`, `quest_line_1..3`, and `quest_compact`
are empty/false when no scoreboard pin is available. Normal panels show title,
progress and next action; contextual panels may use a single matching work/run
line. Scoreboard mode suppresses ordinary action-bar progress, retaining stage
transitions and completion. Off suppresses all quest HUD notifications.

Shift-left-click expands a card's conditions and a recent confirmed collector
rejection when one exists. `QuestProgressDiagnostics` is bounded, expires after
30 seconds and is cleared on quit; it cannot award progress. Ambiguous failures
or unavailable external confirmations are not fabricated as a specific cause.
Normal cards show the current step; expanded cards retain the full plan.
Resource-contract guidance directs players to contract NPCs at the public spawn
forge, bank or guild,
never a command that bypasses visiting the NPC.

When no pin is chosen, the menu suggests one eligible unfinished goal at least
80% complete. Composite suggestions compare completion of the full plan, with
ANY/DISTINCT respecting their required alternatives; raw quantities from different
activities are never compared. A path label means the quest contributes to the
player's current selected specialization, not proof of why it was assigned.
No new quest slots, reward components, multipliers or currency quantities exist.

### Dialogue-first rank interface (0.13.0)

`/rank` and `/rank dialog` start the shared native Paper dialogue flow.
The concise overview links to daily quests, paths, benefits, perks and the weekly
kit. `/rank chest` keeps the inventory alternative. Native daily quests use
six entries per page and a separate detail screen; tracking, replacement and
reward state share the same service callbacks as the chest board. No reward
calculation or progress authority belongs to the presenter.

The new overview uses `dialogs.overview` locale keys so an older operator locale
can retain its legacy `dialogs.root` overrides without inserting the old long
composition into the new screen. The chest board centres partial rows, uses
action-specific icons for existing and new quests and the configured shared Back
item, and expands conditions without duplicating the next-action hint.

Rank and daily summaries use `RankDialogTables`, a narrow optional bridge to
ARC's pack-owned `DialogTables.render` API. Only resolved Adventure components
cross the plugin boundary; ArcRanks wraps the result in its own core body type.
EPIC frames group rank/day fields, LEGENDARY groups quest progress and rewards.
Missing ARC/table API retains every field as ordinary text. The adapter does not
copy font metrics, spacer glyphs or layout algorithms. Actions/history are unchanged.
For an actual presenter export, run the two dialog controller tests with
`-PdialogPreviewArcJar=/absolute/path/to/ARC.jar`; output is in
`build/reports/dialog-tables/export`. Run `python3 scripts/render-dialog-tables`
to render it. This opt-in test dependency is never packaged.

Daily-menu reads flush player progress, load the board/profile together and read
local provider availability. Social status reconciliation runs separately on menu
entry, join and the periodic task. Its last completed result expires after 90s;
unknown/expired status never creates an unlinked-account eligibility. Menu opens
no longer chain two 2-second social Redis requests or reload full rank/perk state.
Native screens no longer offer chest-switch buttons; explicit chest commands
remain available. Path overview/progress, perk slots and weekly-kit status use
the same shared textured tables; instructions and benefit prose remain text.
