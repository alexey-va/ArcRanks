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
