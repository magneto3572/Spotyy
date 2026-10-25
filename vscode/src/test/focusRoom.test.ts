import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { NetworkDiscoveryService, Transport } from '../network';
import { FocusRoom, FocusRoomService, NearbyRoom } from '../focusRoom';
import { MemoryStore } from '../store';

class FakeTransport implements Transport {
  sent: string[] = [];
  send(msg: string): void {
    this.sent.push(msg);
  }
}

const T0 = 1_761_400_000_000;
const REMOTE = '203.0.113.7';

function setup(username = 'alice') {
  const clock = { t: T0 };
  const transport = new FakeTransport();
  const net = new NetworkDiscoveryService(new MemoryStore(), { username, now: () => clock.t, transport });
  const svc = new FocusRoomService(net, { now: () => clock.t });
  net.roomMessageHandler = (m) => svc.handleMessage(m);
  let ended = 0;
  const invites: FocusRoom[] = [];
  svc.onRoomEnded = () => ended++;
  svc.onRoomInvite = (r) => invites.push(r);
  return { clock, transport, net, svc, invites, endedCount: () => ended };
}

/** Builds a packet exactly like Kotlin FocusRoomService.send(). */
function pkt(action: string, roomId: string, host: string, dur: number, start: number, sender: string, target = '') {
  return `SPOTYY_ROOM|${action}|${roomId}|${host}|${dur}|${start}|${sender}|${target}`;
}

// ── Room model ──────────────────────────────────────────────────────────────

test('remainingSeconds / formattedTime / isExpired', () => {
  const r = new FocusRoom('id', 'h', 1500, T0);
  assert.equal(r.remainingSeconds(T0), 1500);
  assert.equal(r.formattedTime(T0), '25:00');
  assert.equal(r.remainingSeconds(T0 + 999), 1500, 'integer division');
  assert.equal(r.remainingSeconds(T0 + 1000), 1499);
  assert.equal(r.formattedTime(T0 + 1000), '24:59');
  assert.equal(r.formattedTime(T0 + 1_441_000), '00:59');
  assert.equal(r.isExpired(T0 + 1_499_999), false);
  assert.equal(r.isExpired(T0 + 1_500_000), true);
  assert.equal(r.remainingSeconds(T0 + 9_999_999), 0);
  assert.equal(new FocusRoom('id', 'h', 3600 * 2, T0).formattedTime(T0), '120:00');
  const n = new NearbyRoom('id', 'h', 2700, T0);
  assert.equal(n.formattedTime(T0 + 61_500), '43:59');
  assert.equal(n.isExpired(T0 + 2_700_000), true);
});

// ── Outgoing packets ────────────────────────────────────────────────────────

test('startRoom is invite-only and sets host state', () => {
  const { svc, transport } = setup();
  svc.startRoom(25);
  const r = svc.currentRoom!;
  assert.equal(r.roomId, T0.toString(36));
  assert.equal(r.roomId, 'mh6c2y9s'); // Long.toString(36) of 1761400000000
  assert.equal(r.hostName, 'alice');
  assert.equal(r.durationSeconds, 1500);
  assert.equal(r.startTimestamp, T0);
  assert.deepEqual([...r.members.keys()], ['alice']);
  assert.equal(svc.isHost, true);
  assert.equal(svc.isInRoom, true);
  assert.deepEqual(transport.sent, []);
});

test('exact room packet strings for every outgoing action', () => {
  const { svc, transport, clock } = setup();
  svc.invitePeer('bob'); // no room: nothing
  svc.kickPeer('bob');
  svc.leaveRoom();
  svc.ping();
  assert.deepEqual(transport.sent, []);

  svc.startRoom(45);
  const id = T0.toString(36);
  svc.invitePeer('bob');
  svc.handleMessage(pkt('JOIN', id, 'alice', 2700, T0, 'bob'));
  svc.kickPeer('bob');
  clock.t += 3000;
  svc.ping();
  svc.leaveRoom();
  assert.deepEqual(transport.sent, [
    `SPOTYY_ROOM|INVITE|${id}|alice|2700|${T0}|alice|bob`,
    `SPOTYY_ROOM|KICK|${id}|alice|2700|${T0}|alice|bob`,
    `SPOTYY_ROOM|PING|${id}|alice|2700|${T0}|alice|`,
    `SPOTYY_ROOM|END|${id}|alice|2700|${T0}|alice|`,
  ]);
  assert.equal(svc.currentRoom, null);
  assert.equal(svc.isHost, false);
});

test('a guest leaving sends LEAVE (only the host ends the room)', () => {
  const { svc, transport } = setup();
  const room = new FocusRoom('r9', 'bob', 1500, T0);
  svc.joinRoom(room);
  svc.leaveRoom();
  assert.deepEqual(transport.sent, [
    `SPOTYY_ROOM|JOIN|r9|bob|1500|${T0}|alice|`,
    `SPOTYY_ROOM|LEAVE|r9|bob|1500|${T0}|alice|`,
  ]);
  assert.equal(svc.currentRoom, null);
});

test('joinRoom sends JOIN, adds self, removes from nearby', () => {
  const { svc, transport } = setup();
  svc.handleMessage(pkt('PING', 'r1', 'bob', 1500, T0, 'bob'));
  assert.equal(svc.getNearbyRooms().length, 1);
  const room = new FocusRoom('r1', 'bob', 1500, T0);
  svc.joinRoom(room);
  assert.equal(svc.currentRoom, room);
  assert.equal(svc.isHost, false);
  assert.ok(room.members.has('alice'));
  assert.equal(svc.getNearbyRooms().length, 0);
  assert.deepEqual(transport.sent, [`SPOTYY_ROOM|JOIN|r1|bob|1500|${T0}|alice|`]);
});

// ── Incoming: INVITE ────────────────────────────────────────────────────────

test('INVITE fires only for the target and only when not in a room', () => {
  const { svc, invites } = setup();
  svc.handleMessage(pkt('INVITE', 'r1', 'bob', 1500, T0, 'bob', 'carl'));
  assert.equal(invites.length, 0);
  svc.handleMessage(pkt('INVITE', 'r1', 'bob', 1500, T0, 'bob', ' alice '));
  assert.equal(invites.length, 1);
  const r = invites[0];
  assert.equal(r.roomId, 'r1');
  assert.equal(r.hostName, 'bob');
  assert.equal(r.durationSeconds, 1500);
  assert.equal(r.startTimestamp, T0);
  assert.deepEqual([...r.members.keys()], ['bob']);
  assert.equal(svc.currentRoom, null, 'invite does not auto-join');

  svc.startRoom(25);
  svc.handleMessage(pkt('INVITE', 'r2', 'dave', 1500, T0, 'dave', 'alice'));
  assert.equal(invites.length, 1, 'ignored while in a room');
});

test('INVITE without target field (7 parts) is not for anyone', () => {
  const { svc, invites } = setup();
  svc.handleMessage(`SPOTYY_ROOM|INVITE|r1|bob|1500|${T0}|bob`);
  assert.equal(invites.length, 0);
});

// ── Incoming: PING / JOIN ───────────────────────────────────────────────────

test('PING/JOIN update members of current room', () => {
  const { svc } = setup();
  svc.startRoom(25);
  const id = svc.currentRoom!.roomId;
  svc.handleMessage(pkt('JOIN', id, 'alice', 1500, T0, 'bob'));
  svc.handleMessage(pkt('PING', id, 'alice', 1500, T0, 'carl'));
  assert.deepEqual([...svc.currentRoom!.members.keys()].sort(), ['alice', 'bob', 'carl']);
  // Other room while in one: ignored entirely
  svc.handleMessage(pkt('PING', 'other', 'dave', 1500, T0, 'dave'));
  assert.equal(svc.getNearbyRooms().length, 0);
});

test('PING/JOIN populate nearbyRooms when not in a room', () => {
  const { svc, clock } = setup();
  svc.handleMessage(pkt('PING', 'r1', 'bob', 1500, T0, 'bob'));
  svc.handleMessage(pkt('JOIN', 'r1', 'bob', 1500, T0, 'carl'));
  let rooms = svc.getNearbyRooms();
  assert.equal(rooms.length, 1);
  assert.equal(rooms[0].hostName, 'bob');
  assert.deepEqual([...rooms[0].seenMembers.keys()].sort(), ['bob', 'carl']);

  clock.t += 6000;
  svc.handleMessage(pkt('PING', 'r1', 'bob', 1500, T0, 'bob'));
  clock.t += 5000; // carl last seen 11 s ago, bob 5 s ago
  rooms = svc.getNearbyRooms();
  assert.deepEqual([...rooms[0].seenMembers.keys()], ['bob']);

  clock.t = T0 + 1_500_000;
  assert.equal(svc.getNearbyRooms().length, 0, 'expired nearby room pruned');
});

// ── Incoming: LEAVE / KICK / END ───────────────────────────────────────────

test('LEAVE removes sender from current room and nearby room', () => {
  const { svc } = setup();
  svc.handleMessage(pkt('PING', 'r1', 'bob', 1500, T0, 'carl'));
  svc.handleMessage(pkt('LEAVE', 'r1', 'bob', 1500, T0, 'carl'));
  assert.deepEqual([...svc.getNearbyRooms()[0].seenMembers.keys()], ['bob']);

  svc.startRoom(25);
  const id = svc.currentRoom!.roomId;
  svc.handleMessage(pkt('JOIN', id, 'alice', 1500, T0, 'bob'));
  svc.handleMessage(pkt('LEAVE', id, 'alice', 1500, T0, 'bob'));
  assert.deepEqual([...svc.currentRoom!.members.keys()], ['alice']);
});

test('KICK of self clears room and fires onRoomEnded', () => {
  const { svc, endedCount } = setup();
  svc.joinRoom(new FocusRoom('r1', 'bob', 1500, T0));
  svc.handleMessage(pkt('KICK', 'r1', 'bob', 1500, T0, 'bob', 'alice'));
  assert.equal(svc.currentRoom, null);
  assert.equal(svc.isHost, false);
  assert.equal(endedCount(), 1);
});

test('KICK of other removes them from members and nearby', () => {
  const { svc, endedCount } = setup();
  const room = new FocusRoom('r1', 'bob', 1500, T0);
  svc.joinRoom(room);
  svc.handleMessage(pkt('PING', 'r1', 'bob', 1500, T0, 'carl'));
  svc.handleMessage(pkt('KICK', 'r1', 'bob', 1500, T0, 'bob', 'carl'));
  assert.ok(!room.members.has('carl'));
  assert.ok(svc.isInRoom);
  assert.equal(endedCount(), 0);

  const o = setup('olivia');
  o.svc.handleMessage(pkt('PING', 'r1', 'bob', 1500, T0, 'carl'));
  o.svc.handleMessage(pkt('KICK', 'r1', 'bob', 1500, T0, 'bob', 'carl'));
  assert.deepEqual([...o.svc.getNearbyRooms()[0].seenMembers.keys()], ['bob']);
});

test('END clears matching room and nearby entry', () => {
  const { svc, endedCount } = setup();
  svc.handleMessage(pkt('PING', 'r2', 'dave', 1500, T0, 'dave'));
  svc.joinRoom(new FocusRoom('r1', 'bob', 1500, T0));
  svc.handleMessage(pkt('END', 'r9', 'zed', 1500, T0, 'zed'));
  assert.ok(svc.isInRoom, 'END for other room ignored');
  assert.equal(endedCount(), 0);
  svc.handleMessage(pkt('END', 'r1', 'bob', 1500, T0, 'bob'));
  assert.equal(svc.currentRoom, null);
  assert.equal(endedCount(), 1);
  assert.equal(svc.getNearbyRooms().length, 1);
  svc.handleMessage(pkt('END', 'r2', 'dave', 1500, T0, 'dave'));
  assert.equal(svc.getNearbyRooms().length, 0);
});

test('packets from own sender name are ignored', () => {
  const { svc, endedCount } = setup();
  svc.startRoom(25);
  const id = svc.currentRoom!.roomId;
  svc.handleMessage(pkt('END', id, 'alice', 1500, T0, 'alice'));
  svc.handleMessage(pkt('KICK', id, 'alice', 1500, T0, ' alice ', 'alice'));
  assert.ok(svc.isInRoom);
  assert.equal(endedCount(), 0);
});

test('malformed packets are ignored', () => {
  const { svc } = setup();
  svc.handleMessage('SPOTYY_ROOM|PING|r1|bob|1500|' + T0);
  svc.handleMessage(`SPOTYY_ROOM|PING|r1|bob|abc|${T0}|bob|`);
  svc.handleMessage(`SPOTYY_ROOM|PING|r1|bob|1500|xyz|bob|`);
  svc.handleMessage(`SPOTYY|PING|r1|bob|1500|${T0}|bob|`);
  assert.equal(svc.getNearbyRooms().length, 0);
});

// ── ping() ─────────────────────────────────────────────────────────────────

test('ping prunes stale members and refreshes self', () => {
  const { svc, clock } = setup();
  svc.startRoom(25);
  const room = svc.currentRoom!;
  svc.handleMessage(pkt('JOIN', room.roomId, 'alice', 1500, T0, 'bob'));
  clock.t += 6000;
  svc.handleMessage(pkt('PING', room.roomId, 'alice', 1500, T0, 'carl'));
  clock.t += 5000;
  svc.ping();
  assert.deepEqual([...room.members.keys()].sort(), ['alice', 'carl']);
  assert.equal(room.members.get('alice')!.lastSeen, clock.t);
});

test('expiry in ping(): host sends END and fires onRoomEnded', () => {
  const { svc, clock, transport, endedCount } = setup();
  svc.startRoom(25);
  const id = svc.currentRoom!.roomId;
  clock.t = T0 + 1_500_000;
  svc.ping();
  assert.equal(svc.currentRoom, null);
  assert.equal(svc.isHost, false);
  assert.equal(endedCount(), 1);
  assert.deepEqual(transport.sent, [`SPOTYY_ROOM|END|${id}|alice|1500|${T0}|alice|`]);
});

test('expiry in ping(): non-host does not send END', () => {
  const { svc, clock, transport, endedCount } = setup();
  svc.joinRoom(new FocusRoom('r1', 'bob', 1500, T0));
  transport.sent = [];
  clock.t = T0 + 1_500_000;
  svc.ping();
  assert.equal(svc.currentRoom, null);
  assert.equal(endedCount(), 1);
  assert.deepEqual(transport.sent, []);
});

// ── End-to-end through NetworkDiscoveryService routing ─────────────────────

test('Kotlin-emitted packet parses via network routing', () => {
  const { net, svc, invites } = setup('alice');
  // Exactly what Kotlin's send("INVITE", room, target = "alice") emits for host "bob"
  const kotlinStart = 1_761_400_000_000;
  const kotlinId = kotlinStart.toString(36);
  net.handlePacket(`SPOTYY_ROOM|INVITE|${kotlinId}|bob|2700|${kotlinStart}|bob|alice`, REMOTE);
  assert.equal(invites.length, 1);
  svc.joinRoom(invites[0]);
  net.handlePacket(`SPOTYY_ROOM|PING|${kotlinId}|bob|2700|${kotlinStart}|bob|`, REMOTE);
  assert.deepEqual([...svc.currentRoom!.members.keys()].sort(), ['alice', 'bob']);
  assert.equal(svc.currentRoom!.formattedTime(kotlinStart + 60_000), '44:00');
});
