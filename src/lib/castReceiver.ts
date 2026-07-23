import { NativeModules, NativeEventEmitter } from 'react-native';
import type { DeviceInfo } from './castServer';

const { CastReceiver } = NativeModules;
const emitter = CastReceiver ? new NativeEventEmitter(CastReceiver as any) : null;

export type ReceiverState = {
  ip: string;
  port: number;
  friendlyName: string;
};

let _onPlay: ((url: string, title: string) => void) | null = null;
let _onPause: (() => void) | null = null;
let _onResume: (() => void) | null = null;
let _onSeek: ((target: string) => void) | null = null;
let _onStop: (() => void) | null = null;
let _onVolume: ((v: number) => void) | null = null;
let _onNext: (() => void) | null = null;
let _onPrevious: (() => void) | null = null;
let _onStarted: ((s: ReceiverState) => void) | null = null;
let _onError: ((msg: string) => void) | null = null;

if (emitter) {
  emitter.addListener('onPlay', (e: any) => _onPlay?.(e?.url ?? '', e?.title ?? '视频'));
  emitter.addListener('onPause', () => _onPause?.());
  emitter.addListener('onResume', () => _onResume?.());
  emitter.addListener('onSeek', (e: any) => _onSeek?.(typeof e === 'string' ? e : e?.target ?? '00:00:00'));
  emitter.addListener('onStop', () => _onStop?.());
  emitter.addListener('onVolume', (e: any) => {
    const v = typeof e === 'number' ? e : parseInt(String(e?.value ?? e ?? '50'), 10);
    _onVolume?.(isNaN(v) ? 50 : v);
  });
  emitter.addListener('onNext', () => _onNext?.());
  emitter.addListener('onPrevious', () => _onPrevious?.());
  emitter.addListener('onStarted', (e: any) => _onStarted?.({ ip: e?.ip ?? '', port: e?.port ?? 0, friendlyName: e?.friendlyName ?? '' }));
  emitter.addListener('onError', (e: any) => _onError?.(e?.message ?? String(e)));
}

export const CastReceiverApi = {
  async start(config: { port?: number; ip?: string; friendlyName: string; uuid: string }): Promise<ReceiverState> {
    if (!CastReceiver) throw new Error('CastReceiver native module not available');
    const result = await (CastReceiver as any).start({
      port: config.port ?? 0,
      ip: config.ip ?? '',
      friendlyName: config.friendlyName,
      uuid: config.uuid,
    });
    return {
      ip: result?.ip ?? '',
      port: result?.port ?? 0,
      friendlyName: result?.friendlyName ?? config.friendlyName,
    };
  },
  stop() {
    (CastReceiver as any)?.stop?.();
  },
  updateState(state: { positionMs: number; durationMs: number; isPlaying: boolean; volume: number }) {
    (CastReceiver as any)?.updateState?.(state);
  },
  async discover(timeoutMs = 3000): Promise<DeviceInfo[]> {
    if (!CastReceiver) return [];
    try {
      const results = await (CastReceiver as any).discover?.(timeoutMs);
      if (!Array.isArray(results)) return [];
      return results.map((r: any) => ({
        id: r.udn ?? r.location ?? `dlna-${Math.random()}`,
        name: r.friendlyName ?? '投屏设备',
        type: 'dlna' as const,
        ip: '',
        location: r.location ?? '',
      }));
    } catch {
      return [];
    }
  },
  setListeners(listeners: {
    onPlay?: (url: string, title: string) => void;
    onPause?: () => void;
    onResume?: () => void;
    onSeek?: (target: string) => void;
    onStop?: () => void;
    onVolume?: (v: number) => void;
    onNext?: () => void;
    onPrevious?: () => void;
    onStarted?: (s: ReceiverState) => void;
    onError?: (msg: string) => void;
  }) {
    _onPlay = listeners.onPlay ?? null;
    _onPause = listeners.onPause ?? null;
    _onResume = listeners.onResume ?? null;
    _onSeek = listeners.onSeek ?? null;
    _onStop = listeners.onStop ?? null;
    _onVolume = listeners.onVolume ?? null;
    _onNext = listeners.onNext ?? null;
    _onPrevious = listeners.onPrevious ?? null;
    _onStarted = listeners.onStarted ?? null;
    _onError = listeners.onError ?? null;
  },
};
