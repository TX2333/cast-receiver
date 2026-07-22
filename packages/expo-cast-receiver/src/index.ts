import { requireNativeModule, EventEmitter, type EventSubscription } from 'expo';

type NativeModule = {
  start: (config: DlnaConfig) => void;
  stop: () => void;
  updateState: (state: PlaybackState) => void;
  discover: (timeoutMs: number) => Promise<DiscoveredDevice[]>;
};

export interface DlnaConfig {
  port: number;
  ip: string;
  friendlyName: string;
  uuid: string;
}

export interface PlaybackState {
  positionMs: number;
  durationMs: number;
  isPlaying: boolean;
  volume: number;
}

export interface DiscoveredDevice {
  location: string;
  friendlyName: string;
  udn: string;
}

let nativeModule: NativeModule | null = null;
try {
  nativeModule = requireNativeModule('CastReceiver') as unknown as NativeModule;
} catch {
  nativeModule = null;
}

const emitter = nativeModule ? new EventEmitter(nativeModule as any) : null;

export function startReceiver(cfg: DlnaConfig): void {
  if (!nativeModule) return;
  try {
    nativeModule.start(cfg);
  } catch {
    /* ignore */
  }
}

export function stopReceiver(): void {
  if (!nativeModule) return;
  try {
    nativeModule.stop();
  } catch {
    /* ignore */
  }
}

export function updateReceiverState(state: PlaybackState): void {
  if (!nativeModule) return;
  try {
    nativeModule.updateState(state);
  } catch {
    /* ignore */
  }
}

export async function discoverReceivers(timeoutMs = 4000): Promise<DiscoveredDevice[]> {
  if (!nativeModule) return [];
  try {
    return await nativeModule.discover(timeoutMs);
  } catch {
    return [];
  }
}

export function addCastListener(
  event: string,
  listener: (...args: any[]) => void
): EventSubscription | null {
  if (!emitter) return null;
  return emitter.addListener(event, listener);
}
