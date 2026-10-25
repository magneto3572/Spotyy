/**
 * Plain-data snapshot rendered by the "Listening Nearby" webview (src/peersView.ts).
 * Built in extension.ts from NetworkDiscoveryService + FocusRoomService every second
 * while the view is visible. Times are pre-formatted "MM:SS" strings.
 */
export type VibeMatchKind = 'NONE' | 'SAME_ARTIST' | 'SAME_SONG';

export interface PeerVM {
  username: string;
  track: string;
  match: VibeMatchKind;
  /** Only meaningful when the local user is host of an active room. */
  inMyRoom: boolean;
}

export interface MemberVM {
  username: string;
  isMe: boolean;
  isHost: boolean;
}

export interface CurrentRoomVM {
  hostName: string;
  remaining: string;
  isHost: boolean;
  members: MemberVM[];
}

export interface NearbyRoomVM {
  roomId: string;
  hostName: string;
  memberCount: number;
  remaining: string;
}

export interface PeersViewModel {
  localUsername: string;
  ghostMode: boolean;
  todayVibeCount: number;
  peers: PeerVM[];
  /** Non-null when the local user is in a room. */
  room: CurrentRoomVM | null;
  /** Only populated when room is null. */
  nearbyRooms: NearbyRoomVM[];
  /** Offered when not in a room. */
  durations: number[];
}

/** Messages posted from the webview back to the extension. */
export type PeersViewAction =
  | { type: 'invite'; username: string }
  | { type: 'kick'; username: string }
  | { type: 'leave' }
  | { type: 'startRoom'; minutes: number }
  | { type: 'toggleGhost' };
