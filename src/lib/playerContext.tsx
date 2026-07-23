/**
 * 视频状态管理 Context
 * 提供：播放列表、当前视频、字幕、连接状态的全局状态
 */
import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { castServer, type CastMessage, type CastServerState, type VideoItem, type SubtitleData } from '@/lib/castServer';
import * as Network from 'expo-network';
import * as CastReceiver from '@/lib/castReceiver';
import { parseSubtitle, type SubtitleCue } from '@/lib/subtitleParser';

// ─── 类型定义 ─────────────────────────────────────────────────────────────────
export interface PlayerState {
  playlist: VideoItem[];
  currentIndex: number;
  currentVideo: VideoItem | null;
  subtitleCues: SubtitleCue[];
  activeCueText: string;
  isPlaying: boolean;
  position: number;
  serverState: CastServerState;
}

interface PlayerContextValue extends PlayerState {
  // 服务器控制
  startServer: () => Promise<void>;
  stopServer: () => Promise<void>;
  // 播放控制
  playNext: () => void;
  playPrev: () => void;
  playAt: (index: number) => void;
  setPlaying: (v: boolean) => void;
  setPosition: (s: number) => void;
  setActiveCue: (text: string) => void;
  volume: number;
  setVolume: (v: number) => void;
  // 字幕加载
  loadSubtitleText: (text: string) => void;
  // 演示：手动投屏（测试用）
  demoPlay: (url: string, title: string) => void;
  // 发送端：设置视频源并播放
  setSourceUrl: (url: string, title: string) => void;
  play: () => void;
}

const PlayerContext = createContext<PlayerContextValue | null>(null);

// ─── Provider ────────────────────────────────────────────────────────────────
export function PlayerProvider({ children }: { children: React.ReactNode }) {
  const [playlist, setPlaylist] = useState<VideoItem[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [subtitleCues, setSubtitleCues] = useState<SubtitleCue[]>([]);
  const [activeCueText, setActiveCueText] = useState('');
  const [isPlaying, setIsPlayingState] = useState(false);
  const [position, setPositionState] = useState(0);
  const [volume, setVolume] = useState(1);
  const [serverState, setServerState] = useState<CastServerState>({
    status: 'idle',
    port: 7788,
    localIp: '...',
    clientCount: 0,
    deviceId: '',
    deviceName: '投屏助手',
  });

  const currentVideo = playlist[currentIndex] ?? null;

  // ─── 字幕加载 ──────────────────────────────────────────────────────────────
  const loadSubtitleText = useCallback((text: string) => {
    const cues = parseSubtitle(text);
    setSubtitleCues(cues);
    setActiveCueText('');
  }, []);

  // ─── 处理投屏消息 ──────────────────────────────────────────────────────────
  const handleMessage = useCallback((msg: CastMessage) => {
    switch (msg.type) {
      case 'play': {
        const video = msg.payload as VideoItem;
        setPlaylist([video]);
        setCurrentIndex(0);
        setSubtitleCues([]);
        setActiveCueText('');
        setIsPlayingState(true);
        break;
      }
      case 'playlist': {
        const videos = msg.payload as VideoItem[];
        if (videos.length > 0) {
          setPlaylist(videos);
          setCurrentIndex(0);
          setSubtitleCues([]);
          setActiveCueText('');
          setIsPlayingState(true);
        }
        break;
      }
      case 'subtitle': {
        const sub = msg.payload as SubtitleData;
        if (sub?.content) loadSubtitleText(sub.content);
        break;
      }
      case 'pause':
        setIsPlayingState(false);
        break;
      case 'resume':
        setIsPlayingState(true);
        break;
      case 'seek': {
        const p = (msg.payload as { position?: number })?.position;
        if (p !== undefined) setPositionState(p);
        break;
      }
      case 'stop':
        setIsPlayingState(false);
        // 不清空 playlist，保留当前视频信息避免 player 页因 currentVideo=null 白屏
        // 仅重置字幕和播放位置
        setSubtitleCues([]);
        setActiveCueText('');
        break;
      default:
        break;
    }
  }, [loadSubtitleText]);

  // ─── 启动服务器 ────────────────────────────────────────────────────────────
  const startServer = useCallback(async () => {
    try {
      castServer.setOnMessage(handleMessage);
      castServer.setOnStatus(setServerState);
      await castServer.start();
    } catch (e) {
      console.warn('[playerContext] startServer failed:', e);
    }
  }, [handleMessage]);

  const stopServer = useCallback(async () => {
    try {
      await castServer.stop();
    } catch (e) {
      console.warn('[playerContext] stopServer failed:', e);
    }
  }, []);

  // ─── 播放列表控制 ──────────────────────────────────────────────────────────
  const playNext = useCallback(() => {
    setCurrentIndex(i => {
      const next = i + 1 < playlist.length ? i + 1 : i;
      return next;
    });
    setSubtitleCues([]);
    setActiveCueText('');
  }, [playlist.length]);

  const playPrev = useCallback(() => {
    setCurrentIndex(i => (i > 0 ? i - 1 : 0));
    setSubtitleCues([]);
    setActiveCueText('');
  }, []);

  const playAt = useCallback((index: number) => {
    setCurrentIndex(index);
    setSubtitleCues([]);
    setActiveCueText('');
    setIsPlayingState(true);
  }, []);

  // ─── 演示投屏（测试用，注入一条 play 消息）──────────────────────────────────
  const demoPlay = useCallback((url: string, title: string) => {
    const msg: CastMessage = {
      type: 'play',
      payload: { id: Date.now().toString(), url, title },
      timestamp: Date.now(),
    };
    castServer.injectMessage(msg);
  }, []);

  // ─── 发送端：设置视频源 ────────────────────────────────────────────────────
  const setSourceUrl = useCallback((url: string, title: string) => {
    const video: VideoItem = { id: Date.now().toString(), url, title };
    setPlaylist([video]);
    setCurrentIndex(0);
    setSubtitleCues([]);
    setActiveCueText('');
  }, []);

  // ─── 发送端：触发播放 ──────────────────────────────────────────────────────
  const play = useCallback(() => {
    setIsPlayingState(true);
  }, []);

  const value: PlayerContextValue = {
    playlist,
    currentIndex,
    currentVideo,
    subtitleCues,
    activeCueText,
    isPlaying,
    position,
    serverState,
    startServer,
    stopServer,
    playNext,
    playPrev,
    playAt,
    setPlaying: setIsPlayingState,
    setPosition: setPositionState,
    setActiveCue: setActiveCueText,
    volume,
    setVolume,
    loadSubtitleText,
    demoPlay,
    setSourceUrl,
    play,
  };

  // ─── 启动 DLNA/UPnP 接收端，使局域网设备/手机视频软件可发现并投屏 ───
  const dlnaRef = useRef({ position: 0, isPlaying: false, volume: 1 });
  dlnaRef.current = { position, isPlaying, volume };

  useEffect(() => {
    let stopped = false;
    const subs: Array<{ remove: () => void } | null> = [];

    const genUuid = () => {
      const s = () => Math.floor((1 + Math.random()) * 0x10000).toString(16).substring(1);
      return `uuid:${s()}${s()}-${s()}-${s()}-${s()}-${s()}${s()}${s()}`;
    };

    Network.getIpAddressAsync()
      .then((ip) =>
        CastReceiver.startReceiver({
          port: 49152,
          ip: ip || '127.0.0.1',
          friendlyName: '投屏助手',
          uuid: genUuid(),
        })
      )
      .catch(() => {});

    subs.push(
      CastReceiver.addCastListener('onSetAVTransportURI', (e: any) => {
        castServer.injectMessage({
          type: 'play',
          payload: { id: Date.now().toString(), url: e?.uri ?? '', title: e?.title ?? '' },
          timestamp: Date.now(),
        });
      })
    );
    subs.push(
      CastReceiver.addCastListener('onPlay', () =>
        castServer.injectMessage({ type: 'resume', payload: null, timestamp: Date.now() })
      )
    );
    subs.push(
      CastReceiver.addCastListener('onPause', () =>
        castServer.injectMessage({ type: 'pause', payload: null, timestamp: Date.now() })
      )
    );
    subs.push(
      CastReceiver.addCastListener('onStop', () =>
        castServer.injectMessage({ type: 'stop', payload: null, timestamp: Date.now() })
      )
    );
    subs.push(
      CastReceiver.addCastListener('onSeek', (e: any) =>
        castServer.injectMessage({
          type: 'seek',
          payload: { position: Number(e?.position) || 0 },
          timestamp: Date.now(),
        })
      )
    );
    subs.push(
      CastReceiver.addCastListener('onSetVolume', (e: any) => {
        const v = (Number(e?.volume) || 0) / 100;
        setVolume(Math.max(0, Math.min(1, v)));
      })
    );

    const poll = setInterval(() => {
      if (stopped) return;
      try {
        const s = dlnaRef.current;
        CastReceiver.updateReceiverState({
          positionMs: Math.round(s.position * 1000),
          durationMs: 0,
          isPlaying: s.isPlaying,
          volume: Math.round(s.volume * 100),
        });
      } catch (e) {
        console.warn('[playerContext] updateReceiverState failed:', e);
      }
    }, 1000);

    return () => {
      stopped = true;
      clearInterval(poll);
      try {
        subs.forEach((s) => s?.remove());
        CastReceiver.stopReceiver();
      } catch (e) {
        console.warn('[playerContext] stopReceiver failed:', e);
      }
    };
  }, []);

  return <PlayerContext.Provider value={value}>{children}</PlayerContext.Provider>;
}

// ─── Hook ────────────────────────────────────────────────────────────────────
export function usePlayer() {
  const ctx = useContext(PlayerContext);
  if (!ctx) throw new Error('usePlayer must be used inside PlayerProvider');
  return ctx;
}
