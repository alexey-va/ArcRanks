import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { test, expect, waitUntil } from '@drownek/plugwright';

const require = createRequire(import.meta.url);

function unwrap(value) {
  if (Array.isArray(value)) return value.map(unwrap);
  if (!value || typeof value !== 'object') return value;
  if (typeof value.type === 'string' && 'value' in value) {
    return unwrap(value.type === 'list' ? value.value.value : value.value);
  }
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, unwrap(child)]));
}

function plain(value) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(plain).join('');
  if (typeof value !== 'object') return '';
  return [value.text, value.contents, value.extra, value.body].map(plain).join('');
}

function varint(value) {
  const bytes = [];
  do {
    const byte = value & 127;
    value >>>= 7;
    bytes.push(byte | (value ? 128 : 0));
  } while (value);
  return Buffer.from(bytes);
}

function nbtString(value) {
  const bytes = Buffer.from(value);
  const length = Buffer.alloc(2);
  length.writeUInt16BE(bytes.length);
  return Buffer.concat([length, bytes]);
}

function sendClick(player, id, input) {
  const mappings = require('minecraft-data')(player.bot.version)
    .protocol.play.toServer.types.packet[1][0].type[1].mappings;
  const packetId = Object.keys(mappings).find(key => mappings[key] === 'custom_click_action');
  assert.ok(packetId, 'Client protocol has no native dialog action packet');
  const payload = input === undefined ? Buffer.from([10, 0]) : Buffer.concat([
    Buffer.from([10, 8]),
    nbtString('message'),
    nbtString(input),
    Buffer.from([0]),
  ]);
  const key = Buffer.from(id);
  player.bot._client.writeRaw(Buffer.concat([
    varint(Number(packetId)),
    varint(key.length),
    key,
    varint(payload.length),
    payload,
  ]));
}

function actionSuffix(action) {
  return action?.action?.id?.split('/').pop();
}

function actionFrom(dialog, suffix) {
  return [
    ...(dialog?.actions ?? []),
    dialog?.exit_action,
  ].find(action => actionSuffix(action) === suffix);
}

function nativeText(dialog) {
  return plain(dialog?.title) + plain(dialog?.body);
}

function nativeTableRow(text, label) {
  const escapedLabel = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const normalized = String(text)
    .replace(/§./g, '')
    .replace(/[\uE000-\uF8FF\uFFF0-\uFFFF]/gu, ' ');
  const match = normalized.match(new RegExp(
    `${escapedLabel}\\s*:?\\s*\\+?([\\d,]+)(?:\\s*/\\s*([\\d,]+))?`,
    'i',
  ));
  if (!match) return undefined;
  return {
    value: Number(match[1].replace(/,/g, '')),
    target: match[2] == null ? undefined : Number(match[2].replace(/,/g, '')),
  };
}

// Captured from the failing native table: labels are followed by formatting
// glyphs and values, not a literal colon.
const capturedDailyDetail = 'Progress\uFFFD\uE1950 / 6  0% Coins\uFFFD\uE195+150';
assert.deepEqual(nativeTableRow(capturedDailyDetail, 'Progress'), { value: 0, target: 6 });
assert.deepEqual(nativeTableRow(capturedDailyDetail, 'Coins'), { value: 150, target: undefined });

function nativeDialog(player, signal) {
  let dialog;
  const observe = packet => {
    dialog = unwrap(packet.dialog?.data ?? packet.dialog ?? packet);
  };
  player.bot._client.on('show_dialog', observe);

  const waitFor = (predicate, message) => waitUntil(
    () => predicate(dialog),
    { signal, timeout: 15000, message },
  );
  const waitForAction = suffix => waitFor(
    current => Boolean(actionFrom(current, suffix)),
    'Native dialog missing action ' + suffix,
  );
  const actionKeys = () => [
    ...(dialog?.actions ?? []),
    dialog?.exit_action,
  ].map(actionSuffix).filter(Boolean);
  const click = async (suffix, destination) => {
    const action = actionFrom(dialog, suffix);
    assert.ok(action, 'No observed native action ' + suffix);
    dialog = undefined;
    sendClick(player, action.action.id);
    await waitFor(
      destination ?? (current => Boolean(current)),
      'Native dialog did not advance after ' + suffix,
    );
    return dialog;
  };
  const close = () => {
    const exit = dialog?.exit_action;
    assert.ok(exit?.action?.id, 'Native dialog has no observed exit action');
    dialog = undefined;
    sendClick(player, exit.action.id);
  };

  return {
    action: suffix => actionFrom(dialog, suffix),
    actionKeys,
    click,
    close,
    current: () => dialog,
    text: () => nativeText(dialog),
    waitFor,
    waitForAction,
    dispose: () => player.bot._client.off('show_dialog', observe),
  };
}

async function grantPermission(player, permission) {
  const since = player.messageBuffer.length;
  player.chat('/lp user ' + player.username + ' permission set ' + permission + ' true');
  await expect(player).toHaveReceivedMessage(
    new RegExp('Set ' + permission.replace('.', '\\.') + ' to true', 'i'),
    { since },
  );
  await player.bot.waitForTicks(20);
}

async function readBalance(player, signal) {
  const since = player.messageBuffer.length;
  player.chat('/balance');
  let message;
  await waitUntil(() => {
    message = player.messageBuffer.slice(since).find(entry => /You have [\d.,]+/i.test(String(entry)));
    return Boolean(message);
  }, { signal, timeout: 10000, message: 'Balance command did not reply' });
  const match = String(message).replace(/§./g, '').match(/You have ([\d.,]+)/i);
  assert.ok(match, 'Unexpected balance message: ' + message);
  return Number(match[1].replace(/,/g, ''));
}

async function readDailyGoal(dialog) {
  // The E2E fixture has one enabled template. The full action id is still
  // observed from the server before the click; this checks its actual suffix.
  await dialog.waitForAction('goal_harvest_wheat');
  const goalKey = dialog.actionKeys().find(key => key.startsWith('goal_'));
  assert.equal(goalKey, 'goal_harvest_wheat');
  await dialog.click(goalKey, current => Boolean(actionFrom(current, 'daily_footer')));
  const detail = dialog.text();
  const progress = nativeTableRow(detail, 'Progress');
  const money = nativeTableRow(detail, 'Coins');
  assert.ok(progress, 'Daily detail did not expose a numeric progress target: ' + detail);
  assert.notEqual(progress.target, undefined, 'Daily detail did not expose a numeric progress target: ' + detail);
  assert.ok(money, 'Daily detail did not expose its actual coin reward: ' + detail);
  await dialog.click('daily_footer', current => [...(current?.actions ?? [])]
    .some(action => actionSuffix(action)?.startsWith('goal_')));
  return {
    id: 'harvest_wheat',
    target: progress.target,
    money: money.value,
  };
}

async function preparePhysicalGoal(player, server, goal, signal) {
  const origin = { x: 32, y: 65, z: 32 };
  const columns = 16;
  const rows = Math.ceil(goal.target / columns);
  const endZ = origin.z + rows - 1;
  const endX = origin.x + columns - 1;

  await player.makeOp();
  server.execute('minecraft:fill ' + origin.x + ' 64 ' + origin.z + ' ' + endX + ' 64 ' + endZ + ' minecraft:farmland');
  server.execute(
    'minecraft:fill ' + origin.x + ' ' + origin.y + ' ' + origin.z + ' ' + endX + ' ' + origin.y + ' ' + endZ
    + ' minecraft:wheat[age=7]',
  );
  await player.giveItem('diamond_hoe', 1);
  await waitUntil(
    () => player.bot.inventory.items().some(item => item.name === 'diamond_hoe'),
    { signal, timeout: 5000, message: 'Harvest fixture did not receive a hoe' },
  );
  await player.deOp();
  return origin;
}

async function harvestRealCrop(player, origin, index, signal) {
  const x = origin.x + (index % 16);
  const z = origin.z + Math.floor(index / 16);
  const nearby = player.bot.entity.position.clone().set(x + 0.5, origin.y + 1, z + 2.5);
  await player.teleport(nearby.x, nearby.y, nearby.z);
  await waitUntil(() => player.bot.entity.position.distanceTo(nearby) < 0.5, {
    signal, timeout: 5000, message: 'Player did not reach harvest position ' + x + ',' + z,
  });
  await player.bot.waitForChunksToLoad();
  const target = player.bot.entity.position.clone().set(x, origin.y, z);
  const block = player.bot.blockAt(target);
  assert.ok(block?.name === 'wheat', 'Missing mature wheat fixture at ' + x + ',' + z);
  await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'diamond_hoe'), 'hand');
  await player.bot.dig(block);
  await waitUntil(() => player.bot.blockAt(target)?.name === 'air', {
    signal, timeout: 5000, message: 'Crop was not harvested at ' + x + ',' + z,
  });
}

async function completePhysicalGoal(player, origin, goal, signal) {
  for (let index = 0; index < goal.target; index += 1) {
    await harvestRealCrop(player, origin, index, signal);
  }
}

test('promotes through LuckPerms from the native rank dialog', async ({ player, signal }) => {
  await player.makeOp();
  for (const permission of ['arcranks.use', 'arcranks.rankup', 'arcranks.admin.grant']) {
    await grantPermission(player, permission);
  }
  for (let step = 0; step < 4; step += 1) {
    const since = player.messageBuffer.length;
    player.chat('/rank admin advance');
    await expect(player).toHaveReceivedMessage(
      /added .* nearest requirement|next rank requirements are complete/i,
      { since },
    );
    await player.bot.waitForTicks(20);
  }
  await player.deOp();

  const dialog = nativeDialog(player, signal);
  try {
    player.chat('/rank');
    await dialog.waitForAction('paths');
    const since = player.messageBuffer.length;
    await dialog.click('promote', current => Boolean(actionFrom(current, 'paths')));
    await expect(player).toHaveReceivedMessage(/New rank/i, { since });
    dialog.close();
  } finally {
    dialog.dispose();
  }

  await player.rejoin();
  await player.makeOp();
  const since = player.messageBuffer.length;
  player.chat('/lp user ' + player.username + ' parent info');
  await expect(player).toHaveReceivedMessage(/rank_peasant/i, { since });
  await player.deOp();
});

test('completes one daily quest through real gameplay and credits its reward once', async ({ player, server, signal }) => {
  await player.makeOp();
  await grantPermission(player, 'arcranks.use');
  await player.deOp();

  let goal;
  const dialog = nativeDialog(player, signal);
  try {
    player.chat('/rank');
    await dialog.waitForAction('daily_quests');
    await dialog.click('daily_quests', current => [...(current?.actions ?? [])]
      .some(action => actionSuffix(action)?.startsWith('goal_')));
    goal = await readDailyGoal(dialog);
    await dialog.click('daily_footer', current => Boolean(actionFrom(current, 'daily_quests')));
    dialog.close();
  } finally {
    dialog.dispose();
  }

  const before = await readBalance(player, signal);
  const origin = await preparePhysicalGoal(player, server, goal, signal);
  const since = player.messageBuffer.length;
  await completePhysicalGoal(player, origin, goal, signal);
  await waitUntil(() => player.messageBuffer.slice(since).some(message => {
    const text = String(message).replace(/§./g, '');
    return /completed!/i.test(text) && /Reward:\s*\+\d/i.test(text);
  }), {
    signal, timeout: 15000, message: 'Real daily quest completion did not deliver its reward',
  });

  const after = await readBalance(player, signal);
  assert.equal(after - before, goal.money, 'Balance delta must equal the native daily reward');

  await player.rejoin();
  const afterReconnect = await readBalance(player, signal);
  assert.equal(afterReconnect, after, 'Reconnect must not deliver the daily reward twice');

  const reopened = nativeDialog(player, signal);
  try {
    player.chat('/rank');
    await reopened.waitForAction('daily_quests');
    await reopened.click('daily_quests', current => Boolean(actionFrom(
      current,
      'goal_' + goal.id,
    )));
    await reopened.click('goal_' + goal.id, current => Boolean(actionFrom(current, 'daily_footer')));
    assert.match(reopened.text(), /Completed\s*[—-]\s*all rewards credited/i);
    await reopened.click('daily_footer', current => Boolean(actionFrom(current, 'goal_' + goal.id)));
    await reopened.click('daily_footer', current => Boolean(actionFrom(current, 'daily_quests')));
    reopened.close();
  } finally {
    reopened.dispose();
  }

  const finalBalance = await readBalance(player, signal);
  assert.equal(finalBalance, after, 'Reopening the completed daily quest must not duplicate money');
});
