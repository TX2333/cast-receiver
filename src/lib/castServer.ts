import { Platform } from 'react-native';
import { CastReceiverApi } from './castReceiver';
import { sendControlCommand } from './castControl';
import { getDeviceId } from './device';
import { supabase } from '../client/supabase';

export type DeviceInfo = {
  id: string;
  name: string;
  type: 'dlna' | 'miracast' | 'airplay' | 'lelink';
  ip: string;
  location?: string;
};

export type CastPayload = {
  url: string;
  title?: string;
  type?: 'video' | 'audio' | 'image';
  position?: number;
};

export type CastServerStatus = {
  running: boolean;
  ip: string;
  port: number;
  deviceId: string;
  friendlyName: string;
};

// Types used by playerContext and other modules
export type VideoItem = {
  id: string;
  url: string;
  title: string;
  type?: 'video' | 'audio' | 'image';
  subtitleUrl?: string;
};

export type SubtitleData = {
  content: string;
  language?: string;
  label?: string;
};

let _status: CastServerStatus = {
  running: false,
  ip: '',
  port: 0,
  deviceId: '',
  friendlyName: '投屏助手',
};

let _onIncomingCast: ((payload: CastPayload) => void) | null = null;
let _onControl: ((action: string, params?: any) => void) | null = null;

export function setIncomingCastHandler(handler: (payload: CastPayload) => void) {
  _onIncomingCast = handler;
}

export function setControlHandler(handler: (action: string, params?: any) => void) {
  _onControl = handler;
}

export async function startCastServer(): Promise<CastServerStatus> {
  if (_status.running) return _status;

  const deviceId = await getDeviceId();
  const friendlyName = `投屏助手-${deviceId.slice(0, 4).toUpperCase()}`;
  const uuid = `cast-receiver-${deviceId}`;

  if (Platform.OS === 'android') {
    CastReceiverApi.setListeners({
      onPlay: (url, title) => {
        _onIncomingCast?.({ url, title, type: 'video' });
      },
      onPause: () => _onControl?.('pause'),
      onResume: () => _onControl?.('play'),
      onSeek: (target) => {
        // target is "HH:MM:SS" format
        const parts = target.split(':').map(Number);
        const ms = ((parts[0] * 3600) + (parts[1] * 60) + parts[2]) * 1000;
        _onControl?.('seek', { position: ms });
      },
      onStop: () => _onControl?.('stop'),
      onVolume: (v) => _onControl?.('volume', { volume: v / 100 }),
      onNext: () => _onControl?.('next'),
      onPrevious: () => _onControl?.('previous'),
      onStarted: (s) => {
        _status.ip = s.ip;
        _status.port = s.port;
        _status.friendlyName = s.friendlyName;
        _status.running = true;
      },
      onError: (msg) => console.warn('[CastServer] DLNA error:', msg),
    });

    const state = await CastReceiverApi.start({
      friendlyName,
      uuid,
    });

    _status = {
      running: true,
      ip: state.ip,
      port: state.port,
      deviceId,
      friendlyName: state.friendlyName,
    };
  } else {
    // iOS: placeholder (would need native module too)
    _status = {
      running: true,
      ip: '',
      port: 0,
      deviceId,
      friendlyName,
    };
  }

  // Register device in Supabase for remote discovery fallback
  try {
    await supabase.from('devices').upsert({
      id: deviceId,
      name: friendlyName,
      ip: _status.ip,
      port: _status.port,
      last_seen: new Date().toISOString(),
    });
  } catch {}

  return _status;
}

export async function stopCastServer() {
  if (Platform.OS === 'android') {
    CastReceiverApi.stop();
  }
  _status.running = false;
}

export function getCastStatus(): CastServerStatus {
  return { ..._status };
}

export async function discoverDevices(timeoutMs = 3000): Promise<DeviceInfo[]> {
  if (Platform.OS === 'android') {
    return CastReceiverApi.discover(timeoutMs);
  }
  return [];
}

export async function castToDevice(device: DeviceInfo, payload: CastPayload) {
  if (device.type === 'dlna' && device.location) {
    const baseUrl = device.location.replace('/description.xml', '');
    await sendControlCommand(baseUrl, payload);
  }
}

export function updatePlaybackState(state: { positionMs: number; durationMs: number; isPlaying: boolean; volume: number }) {
  if (Platform.OS === 'android') {
    CastReceiverApi.updateState(state);
  }
}

// Build QR code payload that phone cameras can recognize.
// Returns a URL that can be scanned; the sender page can parse deviceId/token from query params.
export function buildQrPayload(deviceId: string, token?: string, ip?: string, port?: number): string {
  const params = new URLSearchParams({ d: deviceId });
  if (token) params.set('t', token);
  if (ip) params.set('ip', ip);
  if (port) params.set('p', String(port));
  return `https://cast.local/pair?${params.toString()}`;
}
