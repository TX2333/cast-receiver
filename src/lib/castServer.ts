/**
 * 投屏服务器模块 v2
 * ─────────────────────────────────────────────────
 * 设备发现：App 启动后定期向 Supabase cast_sessions 表 upsert 自身在线状态，
 *           发送端（网页/其他 App）查询该表即可发现局域网内所有接收端。
 *
 * 投屏指令：发送端向 cast_commands 表 insert 指令，接收端每 1.5s 轮询读取。
 *
 * Web 预览：BroadcastChannel 模拟（同浏览器多标签互通，供演示用）。
 */

import * as Network from 'expo-network';
import { supabase } from '@/client/supabase';
import * as Crypto from 'expo-crypto';

// ─── 消息类型定义 ─────────────────────────────────────────────────────────────
export type CastMessageType =
  | 'play'
  | 'playlist'
  | 'subtitle'
  | 'pause'
  | 'resume'
  | 'seek'
  | 'stop'
  | 'ping'
  | 'pong';

export interface VideoItem {
  id: string;
  url: string;
  title: string;
  duration?: number;
  subtitleUrl?: string;
}

export interface SubtitleData {
  videoId: string;
  format: 'srt' | 'ass' | 'vtt';
  content: string;
}

export interface CastMessage {
  type: CastMessageType;
  payload?: VideoItem | VideoItem[] | SubtitleData | { position?: number } | null;
  timestamp: number;
}

export type ServerStatus = 'idle' | 'starting' | 'running' | 'stopped';

export interface CastServerState {
  status: ServerStatus;
  port: number;
  localIp: string;
  clientCount: number;
  deviceId: string;
  deviceName: string;
}

export type OnMessageCallback = (msg: CastMessage) => void;
export type OnStatusCallback = (state: CastServerState) => void;

const DEFAULT_PORT = 7788;
const HEARTBEAT_INTERVAL = 8000;   // 每 8s 刷新 last_seen
const POLL_INTERVAL = 1500;        // 每 1.5s 拉取新指令
const SESSION_EXPIRE_SEC = 20;     // 超过 20s 未心跳视为离线

// ─── 获取本机局域网 IP（IPv4） ────────────────────────────────────────────────
function isIPv4(ip: string): boolean {
  return /^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(ip);
}

export async function getLocalIp(): Promise<string> {
  try {
    const state = await Network.getNetworkStateAsync();
    // 优先使用真实网络连接（非 VPN / 回环）
    if (state.type === Network.NetworkStateType.WIFI ||
        state.type === Network.NetworkStateType.CELLULAR ||
        state.type === Network.NetworkStateType.OTHER) {
      const ip = await Network.getIpAddressAsync();
      if (ip && isIPv4(ip) && ip !== '0.0.0.0' && ip !== '127.0.0.1') return ip;
    }
    // 回退：直接取 IP
    const ip = await Network.getIpAddressAsync();
    if (ip && isIPv4(ip)) return ip;
    return '192.168.x.x';
  } catch {
    return '192.168.x.x';
  }
}

// ─── 生成二维码内容 ───────────────────────────────────────────────────────────
export function buildQrPayload(deviceId: string): string {
  return JSON.stringify({
    service: 'screencast-receiver',
    version: '2',
    deviceId,
  });
}

// ─── CastServer ──────────────────────────────────────────────────────────────
class CastServer {
  private port = DEFAULT_PORT;
  private localIp = '127.0.0.1';
  private status: ServerStatus = 'idle';
  private clientCount = 0;
  private deviceId = '';
  private deviceName = '投屏助手';

  private onMessage: OnMessageCallback | null = null;
  private onStatus: OnStatusCallback | null = null;

  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private lastCommandId = 0;

  // Web 模拟
  private broadcastChannel: BroadcastChannel | null = null;

  setOnMessage(cb: OnMessageCallback) { this.onMessage = cb; }
  setOnStatus(cb: OnStatusCallback) { this.onStatus = cb; }

  getState(): CastServerState {
    return {
      status: this.status,
      port: this.port,
      localIp: this.localIp,
      clientCount: this.clientCount,
      deviceId: this.deviceId,
      deviceName: this.deviceName,
    };
  }

  // ─── 启动 ─────────────────────────────────────────────────────────────────
  async start(): Promise<void> {
    if (this.status === 'running') return;
    this.status = 'starting';
    this.localIp = await getLocalIp();

    // 生成/恢复设备 ID（每次启动固定，不持久化以防 Web 串号）
    this.deviceId = await Crypto.digestStringAsync(
      Crypto.CryptoDigestAlgorithm.SHA256,
      `screencast-${this.localIp}-${this.port}`
    );
    this.deviceId = this.deviceId.slice(0, 16); // 取前 16 字符

    this.emit();

    if (process.env.EXPO_OS === 'web') {
      this.startWebMode();
    } else {
      await this.startNativeMode();
    }
  }

  // ─── Web 模式（BroadcastChannel 模拟）───────────────────────────────────
  private startWebMode() {
    this.broadcastChannel = new BroadcastChannel('cast-channel');
    this.broadcastChannel.onmessage = (e: MessageEvent) => {
      this.handleMessage(e.data as CastMessage);
    };
    this.status = 'running';
    this.clientCount = 1;
    this.emit();
  }

  // ─── 原生模式（Supabase 心跳 + 轮询）───────────────────────────────────
  private async startNativeMode() {
    // 首次上线
    await this.upsertSession();
    this.status = 'running';
    this.emit();

    // 心跳：定期刷新 last_seen
    this.heartbeatTimer = setInterval(() => {
      this.upsertSession();
    }, HEARTBEAT_INTERVAL);

    // 指令轮询
    this.pollTimer = setInterval(() => {
      this.pollCommands();
    }, POLL_INTERVAL);
  }

  // ─── Upsert 在线会话 ──────────────────────────────────────────────────
  private async upsertSession() {
    await supabase.from('cast_sessions').upsert(
      {
        device_id: this.deviceId,
        device_name: this.deviceName,
        ip: this.localIp,
        port: this.port,
        last_seen: new Date().toISOString(),
      },
      { onConflict: 'device_id' }
    );
  }

  // ─── 轮询指令 ─────────────────────────────────────────────────────────
  private async pollCommands() {
    const { data } = await supabase
      .from('cast_commands')
      .select('id, type, payload')
      .eq('device_id', this.deviceId)
      .gt('id', this.lastCommandId)
      .order('id', { ascending: true })
      .limit(20);

    if (!data || data.length === 0) return;

    // 记录最新 id，防止重复处理
    this.lastCommandId = data[data.length - 1].id;

    // 删除已处理指令（保持表干净）
    const ids = data.map((r) => r.id);
    await supabase.from('cast_commands').delete().in('id', ids);

    // 处理指令
    for (const row of data) {
      const msg: CastMessage = {
        type: row.type as CastMessageType,
        payload: row.payload,
        timestamp: Date.now(),
      };
      this.handleMessage(msg);
    }
  }

  // ─── 停止 ─────────────────────────────────────────────────────────────
  async stop() {
    // 先清定时器，防止 upsertSession / pollCommands 在 await 删除期间再次触发
    if (this.heartbeatTimer) { clearInterval(this.heartbeatTimer); this.heartbeatTimer = null; }
    if (this.pollTimer) { clearInterval(this.pollTimer); this.pollTimer = null; }
    if (this.broadcastChannel) { this.broadcastChannel.close(); this.broadcastChannel = null; }

    // 从数据库删除在线记录（带超时保护，避免网络慢时卡住 App 退出）
    if (this.deviceId) {
      const deletePromise = supabase
        .from('cast_sessions')
        .delete()
        .eq('device_id', this.deviceId);
      const timeoutPromise = new Promise<void>((resolve) => setTimeout(resolve, 3000));
      await Promise.race([deletePromise, timeoutPromise]);
    }

    this.status = 'stopped';
    this.clientCount = 0;
    this.emit();
  }

  // ─── 注入消息（演示/测试）────────────────────────────────────────────
  injectMessage(msg: CastMessage) {
    if (process.env.EXPO_OS === 'web') {
      this.broadcastChannel?.postMessage(msg);
    } else {
      this.handleMessage(msg);
    }
  }

  private handleMessage(msg: CastMessage) {
    if (msg.type === 'ping') return;
    this.onMessage?.(msg);
  }

  private emit() {
    this.onStatus?.(this.getState());
  }
}

export const castServer = new CastServer();

// ─── 查询在线设备列表（供发送端使用）────────────────────────────────────────
export interface OnlineDevice {
  deviceId: string;
  deviceName: string;
  ip: string;
  port: number;
  lastSeen: string;
}

export async function fetchOnlineDevices(): Promise<OnlineDevice[]> {
  const since = new Date(Date.now() - SESSION_EXPIRE_SEC * 1000).toISOString();
  const { data } = await supabase
    .from('cast_sessions')
    .select('device_id, device_name, ip, port, last_seen')
    .gte('last_seen', since)
    .order('last_seen', { ascending: false })
    .limit(50);

  return (data ?? []).map((r) => ({
    deviceId: r.device_id,
    deviceName: r.device_name,
    ip: r.ip,
    port: r.port,
    lastSeen: r.last_seen,
  }));
}

// ─── 发送投屏指令（发送端调用）───────────────────────────────────────────────
export async function sendCastCommand(
  deviceId: string,
  type: CastMessageType,
  payload?: CastMessage['payload']
): Promise<void> {
  await supabase.from('cast_commands').insert({
    device_id: deviceId,
    type,
    payload: payload ?? null,
  });
}

