# Crafted quantity correction — 2026-10-17

The bread objective is 4 actual items. Its target, payouts, daily assignment cap,
rarity selection and durable reward identity are unchanged. Progress now follows
successful native production instead of an attempted inventory click. The same
quantity rule applies to generic and material-specific crafting objectives.

## Reward and balance boundary

Rank order: settler, peasant, citizen, artisan, knight, baron, count, prince, caesar.
Normal quest rewards remain 50/60/70/80/95/110/130/150/175 vault; rare rewards remain
150/180/210/240/285/330/390/450/525 vault and 1/1/1/1/1/2/2/2/3 tokens.
Craft path bonus remains 10 before its separate rank scaling; it is not money.
Items, XP, prices, fees, cooldowns and progression prices are unchanged.

The source and freshly read Survival daily-quests.yml have 6–21 daily assignments
and rare-per-day=2. Existing frozen assignments retain their original terms.
For new boards with two eligible rare cards, the base ceiling excluding challenge
bonuses is 500–4375 vault plus 2–6 tokens per day. This fix does not increase it.

[The assessment](craft-quantity-balance-20261017.json) models an intentionally
conservative completion sensitivity: one assigned rare Caesar bread quest,
with 1 bread / 4 bread through Shift / 4 bread through separate clicks. Before:
0/0/1 completions; after: 0/1/1. These are scenarios, not measured income. The
middle scenario enables the already promised 525 vault and 3 tokens once; at
five assumed participants on every day of a week this is +18375 vault and +105
tokens. Selection is not guaranteed, and normal/rare variants must not be added.
The calculator passed. Actual additional completions cannot be inferred from
the aggregate ranks ledger; no change in the cost or income of other mechanics
is assumed. Time to save for purchases is therefore not recomputed.

Multicrafting cannot replay a completed assignment: the existing per-day quest
and payout identities remain the authority. Multiaccount assignment limits,
SELL-to-contract transition, stacking and native item production are unchanged.
The correction is accepted because the stated objective becomes achievable by
making the requested quantity through either normal or Shift crafting.

## Evidence

Read-only ARC ledger, one Spawn replica, 168-hour exact window:
1791637661343..1792242461344; 12470 records, 66635 operations, 27 players.
Ranks minted 30681 vault across 210 operations and 47 tokens across 32 operations.
Neither currency reported unknown net; the source spans rank-related payments
and is not craft-specific. Top recipient shares were 19.49% vault and 21.28%
tokens. Activity buckets are not player-session duration. No player identities
or mixed-currency totals are recorded here.

Focused event tests and packaging verify the source correction. Disk delivery
and the loaded plugin version must be reported separately; ordinary-player
native crafting acceptance requires activation and remains unobserved here.
