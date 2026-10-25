/**
 * Wire-protocol constants shared with the JetBrains plugin
 * (NetworkDiscoveryService.kt / FocusRoomService.kt). Must stay byte-compatible.
 */
export const PORT = 57372;
export const BROADCAST_ADDR = '255.255.255.255';

/** Presence packet: `SPOTYY|<username>|<track>|<isPlaying>` */
export const PRESENCE_PREFIX = 'SPOTYY';
/** Room packet: `SPOTYY_ROOM|<ACTION>|<roomId>|<hostName>|<durationSecs>|<startTs>|<sender>|<target>` */
export const ROOM_PREFIX = 'SPOTYY_ROOM';

export const PEER_TIMEOUT_MS = 10_000;
export const MEMBER_TIMEOUT_MS = 10_000;
export const LISTENER_RETRY_MS = 3_000;

export const GHOST_MODE_KEY = 'spotyy.ghost.mode';
export const VIBE_COUNT_KEY = 'spotyy.vibe.count';
export const VIBE_DATE_KEY = 'spotyy.vibe.date';

export type RoomAction = 'INVITE' | 'PING' | 'JOIN' | 'LEAVE' | 'KICK' | 'END';
