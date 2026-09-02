# Contract Rewards And Horizontal Menu

**Goal:** Make personal contracts visibly worthwhile and readable at a glance, while preserving cross-server/idempotent claim behavior.

**Design:** Every accepted contract snapshots four rewards: path progress, Vault money, RedisEconomy `tokens`, and one ARC item preset. The three weekly completions escalate from an enchant token to a potion token and then a Slimefun lootbox. Bonus components are delivered through the shared one-time-use ledger; a provider rejection stays pending, while an unknown effect enters recovery instead of being retried and duplicated.

The active-contract screen uses one horizontal row: task at slot 20, progress at slot 22, reward/claim at slot 24. Ordinary players never see admin controls. Runtime locale files remain profile-owned and must match the reviewed plugin catalogs byte-for-byte before delivery.

## Tasks

1. Add failing domain/config tests for the three snapshotted reward tiers.
2. Add failing GUI/preview tests for the horizontal active-contract row and explicit reward copy.
3. Extend contract persistence with snapshotted bonus rewards and durable delivery state.
4. Add Vault, RedisEconomy, and ARC preset delivery behind injectable provider boundaries and the arc-core one-time-use ledger.
5. Rework the contract menu and both locales around task, progress, and reward cards.
6. Build, run unit/compile gates, verify the arc-core consumer contract, and render the canonical visual dump.
7. Publish source and reviewed ops mirrors; activate only after owner approves the fresh visual dump.
