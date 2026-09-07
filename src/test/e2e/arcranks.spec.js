import assert from 'node:assert/strict';
import { test, expect } from '@drownek/plugwright';

test('rank passport loads persistent progress and navigates to paths and back', async ({ player }) => {
  await player.makeOp();
  player.chat(`/lp user ${player.username} permission set arcranks.use true`);
  await expect(player).toHaveReceivedMessage('Set arcranks.use to true');
  await player.deOp();
  player.chat('/rank');
  const gui = await player.gui({ title: 'Ranks and Progression' });
  const profile = gui.locator((item) => item.name === 'player_head');
  await expect(profile).toHaveLore('Your permanent status never resets.', { timeout: 15000 });
  assert.match(profile.displayName(), /Your rank:/);
  await gui.locator(item => item.getDisplayName() === 'Progress paths').click();
  const paths = await player.gui({ title: 'Progress Paths' });
  await paths.locator(item => item.getDisplayName() === 'Back').click();
  const returned = await player.gui({ title: 'Ranks and Progression' });
  await expect(returned.locator(item => item.name === 'player_head')).toHaveLore('Your permanent status never resets.');
});

test('promotes through LuckPerms and claims one real weekly contract reward once', async ({ player }) => {
  await player.makeOp();
  for (const permission of ['arcranks.use', 'arcranks.rankup', 'arcranks.admin.contract', 'arcranks.admin.grant']) {
    player.chat(`/lp user ${player.username} permission set ${permission} true`);
    await expect(player).toHaveReceivedMessage(`Set ${permission} to true`);
    await new Promise((resolve) => setTimeout(resolve, 1200));
  }

  // The admin command fills only the next authoritative requirement each time.
  // This keeps the test fixture deterministic without changing production rank rules.
  for (let step = 0; step < 4; step += 1) {
    player.chat('/rank admin advance');
    await expect(player).toHaveReceivedMessage(/added .* nearest requirement|next rank requirements are complete/);
    await new Promise((resolve) => setTimeout(resolve, 1200));
  }

  await player.deOp();
  player.chat('/rankup');
  await expect(player).toHaveReceivedMessage(/New rank/);

  // LuckPerms is the rank authority; verify the direct progression parent after reconnect.
  await player.rejoin();
  await player.makeOp();
  player.chat(`/lp user ${player.username} parent info`);
  await expect(player).toHaveReceivedMessage(/rank_peasant/);
  await player.deOp();

  player.chat('/rank');
  const passport = await player.gui({ title: 'Ranks and Progression' });
  await passport.locator((item) => item.name === 'writable_book').click();
  let contracts = await player.gui({ title: /Personal contracts/i });
  await contracts.locator((item) => item.getDisplayName().startsWith('Personal contract:')).click();
  await expect(player).toHaveReceivedMessage(/Contract accepted:/);

  player.chat('/rank admin contract complete');
  await expect(player).toHaveReceivedMessage(/contract completed by an administrator/);

  // Complete the active contract through the real durable progress API. The admin
  // contract marker above is audited metadata only; it must not bypass path progress.
  for (const metric of ['CROPS_HARVESTED', 'PRODUCTION_ACTIONS', 'TRADE_ACTIONS', 'TRAVEL_BLOCKS', 'BLOCKS_PLACED', 'COMMUNITY_MINUTES']) {
    player.chat(`/rank admin grant ${player.username} ${metric} 100000 e2e-contract-${metric.toLowerCase()}-${player.username}`);
    await expect(player).toHaveReceivedMessage(new RegExp(`\\+100000 to ${metric.toLowerCase()}`));
    await new Promise((resolve) => setTimeout(resolve, 1200));
  }

  // Replaying the same external event is rejected without adding progress.
  player.chat(`/rank admin grant ${player.username} CROPS_HARVESTED 100000 e2e-contract-crops_harvested-${player.username}`);
  await expect(player).toHaveReceivedMessage(/No duplicate progress was granted/);
  await new Promise((resolve) => setTimeout(resolve, 1200));
  await player.deOp();

  await new Promise((resolve) => setTimeout(resolve, 1200));
  player.chat('/rank');
  const refreshedPassport = await player.gui({ title: 'Ranks and Progression' });
  await refreshedPassport.locator((item) => item.name === 'writable_book').click();
  contracts = await player.gui({ title: /Personal contracts/i });
  await contracts.locator((item) => item.getDisplayName() === 'Collect reward').click();
  await expect(player).toHaveReceivedMessage(/Contract complete\. Reward received:/);

  // The transactional claim advances the cycle and removes the old reward card.
  contracts = await player.gui({ title: /Personal contracts/i });
  await assert.rejects(() => contracts.locator((item) => item.getDisplayName() === 'Collect reward').click({ timeout: 3000 }));
});
