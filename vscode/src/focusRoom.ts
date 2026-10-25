import type { NetworkDiscoveryService } from './network';
import { parseIntOrNull } from './network';
import { MEMBER_TIMEOUT_MS, ROOM_PREFIX, type RoomAction } from './protocol';

export interface RoomMember {
  username: string;
  lastSeen: number;
}

/** Kotlin: maxOf(0L, durationSeconds - (now - startTimestamp) / 1000) with Long (truncating) division. */
function remaining(durationSeconds: number, startTimestamp: number, now: number): number {
  return Math.max(0, durationSeconds - Math.trunc((now - startTimestamp) / 1000));
}

function format(r: number): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(Math.trunc(r / 60))}:${pad(r % 60)}`;
}

export class FocusRoom {
  readonly members = new Map<string, RoomMember>();

  constructor(
    readonly roomId: string,
    readonly hostName: string,
    readonly durationSeconds: number,
    readonly startTimestamp: number,
  ) {}

  remainingSeconds(now: number = Date.now()): number {
    return remaining(this.durationSeconds, this.startTimestamp, now);
  }

  isExpired(now: number = Date.now()): boolean {
    return this.remainingSeconds(now) <= 0;
  }

  formattedTime(now: number = Date.now()): string {
    return format(this.remainingSeconds(now));
  }
}

/** A Focus Room detected on the network that this user has NOT joined. */
export class NearbyRoom {
  readonly seenMembers = new Map<string, number>();

  constructor(
    readonly roomId: string,
    readonly hostName: string,
    readonly durationSeconds: number,
    readonly startTimestamp: number,
  ) {}

  remainingSeconds(now: number = Date.now()): number {
    return remaining(this.durationSeconds, this.startTimestamp, now);
  }

  isExpired(now: number = Date.now()): boolean {
    return this.remainingSeconds(now) <= 0;
  }

  formattedTime(now: number = Date.now()): string {
    return format(this.remainingSeconds(now));
  }
}

/** Port of com.magneto.spotyy.focus.FocusRoomService. */
export class FocusRoomService {
  onRoomInvite: ((room: FocusRoom) => void) | null = null;
  onRoomEnded: (() => void) | null = null;

  private _currentRoom: FocusRoom | null = null;
  private _isHost = false;
  private readonly nearbyRooms = new Map<string, NearbyRoom>();
  private readonly now: () => number;

  constructor(private readonly network: NetworkDiscoveryService, opts: { now?: () => number } = {}) {
    this.now = opts.now ?? Date.now;
  }

  get currentRoom(): FocusRoom | null {
    return this._currentRoom;
  }

  get isHost(): boolean {
    return this._isHost;
  }

  get isInRoom(): boolean {
    return this._currentRoom !== null;
  }

  /** Active rooms seen on the network that this user is NOT in; prunes expired rooms and stale members. */
  getNearbyRooms(): NearbyRoom[] {
    const now = this.now();
    for (const [id, r] of this.nearbyRooms) {
      if (r.isExpired(now)) this.nearbyRooms.delete(id);
    }
    for (const r of this.nearbyRooms.values()) {
      for (const [name, seen] of r.seenMembers) {
        if (now - seen > MEMBER_TIMEOUT_MS) r.seenMembers.delete(name);
      }
    }
    return [...this.nearbyRooms.values()];
  }

  // ── Actions ─────────────────────────────────────────────────────────────

  startRoom(durationMinutes: number): void {
    const me = this.network.localUsername;
    const now = this.now();
    const room = new FocusRoom(now.toString(36), me, durationMinutes * 60, now);
    room.members.set(me, { username: me, lastSeen: now });
    this._currentRoom = room;
    this._isHost = true;
    // Invite-only — no broadcast. Use invitePeer().
  }

  invitePeer(targetUsername: string): void {
    const room = this._currentRoom;
    if (!room) return;
    this.send('INVITE', room, targetUsername);
  }

  kickPeer(targetUsername: string): void {
    const room = this._currentRoom;
    if (!room) return;
    this.send('KICK', room, targetUsername);
    room.members.delete(targetUsername);
  }

  joinRoom(room: FocusRoom): void {
    const me = this.network.localUsername;
    room.members.set(me, { username: me, lastSeen: this.now() });
    this._currentRoom = room;
    this._isHost = false;
    this.nearbyRooms.delete(room.roomId);
    this.send('JOIN', room);
  }

  /**
   * Guests send LEAVE. The host sends END so the room closes for everyone — the JetBrains
   * plugin sends LEAVE here too, which leaves guests in a hostless room until it expires.
   */
  leaveRoom(): void {
    if (this._currentRoom) this.send(this._isHost ? 'END' : 'LEAVE', this._currentRoom);
    this._currentRoom = null;
    this._isHost = false;
  }

  /** Called every 3 s by the status bar poll. */
  ping(): void {
    const room = this._currentRoom;
    if (!room) return;
    const now = this.now();
    if (room.isExpired(now)) {
      if (this._isHost) this.send('END', room);
      this._currentRoom = null;
      this._isHost = false;
      this.onRoomEnded?.();
      return;
    }
    const me = this.network.localUsername;
    for (const [name, m] of room.members) {
      if (name !== me && now - m.lastSeen > MEMBER_TIMEOUT_MS) room.members.delete(name);
    }
    room.members.set(me, { username: me, lastSeen: now });
    this.send('PING', room);
  }

  // ── Incoming ────────────────────────────────────────────────────────────

  handleMessage(msg: string): void {
    const parts = msg.split('|');
    if (parts.length < 7 || parts[0] !== ROOM_PREFIX) return;

    const action = parts[1];
    const roomId = parts[2];
    const hostName = parts[3];
    const durationSecs = parseIntOrNull(parts[4]);
    if (durationSecs === null) return;
    const startTs = parseIntOrNull(parts[5]);
    if (startTs === null) return;
    const sender = parts[6].trim();
    const target = parts.length > 7 ? parts[7].trim() : '';

    const me = this.network.localUsername;
    if (sender === me) return;
    const now = this.now();

    switch (action) {
      case 'INVITE': {
        if (target === me && this._currentRoom === null) {
          const room = new FocusRoom(roomId, hostName, durationSecs, startTs);
          room.members.set(hostName, { username: hostName, lastSeen: now });
          this.onRoomInvite?.(room);
        }
        break;
      }
      case 'PING':
      case 'JOIN': {
        const room = this._currentRoom;
        if (room !== null && room.roomId === roomId) {
          room.members.set(sender, { username: sender, lastSeen: now });
        } else if (room === null) {
          let nearby = this.nearbyRooms.get(roomId);
          if (!nearby) {
            nearby = new NearbyRoom(roomId, hostName, durationSecs, startTs);
            this.nearbyRooms.set(roomId, nearby);
          }
          nearby.seenMembers.set(sender, now);
          nearby.seenMembers.set(hostName, now);
        }
        break;
      }
      case 'LEAVE': {
        if (this._currentRoom?.roomId === roomId) this._currentRoom.members.delete(sender);
        this.nearbyRooms.get(roomId)?.seenMembers.delete(sender);
        break;
      }
      case 'KICK': {
        if (target === me) {
          this._currentRoom = null;
          this._isHost = false;
          this.onRoomEnded?.();
        } else {
          if (this._currentRoom?.roomId === roomId) this._currentRoom.members.delete(target);
          this.nearbyRooms.get(roomId)?.seenMembers.delete(target);
        }
        break;
      }
      case 'END': {
        if (this._currentRoom?.roomId === roomId) {
          this._currentRoom = null;
          this._isHost = false;
          this.onRoomEnded?.();
        }
        this.nearbyRooms.delete(roomId);
        break;
      }
    }
  }

  private send(action: RoomAction, room: FocusRoom, target = ''): void {
    const me = this.network.localUsername;
    const msg = `${ROOM_PREFIX}|${action}|${room.roomId}|${room.hostName}|${room.durationSeconds}|${room.startTimestamp}|${me}|${target}`;
    this.network.sendRoomPacket(msg);
  }
}
