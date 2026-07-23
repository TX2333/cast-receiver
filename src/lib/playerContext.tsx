/**
 * 视频状态管理 Context
 * 提供：播放列表、当前视频、字幕、连接状态的全局状态
 */
import React, { createContext, useContext, useCallback, useEffect, useRef, useState } from 'react';
import {
  startCastServer,
  stopCastServer,
  setIncomingCastHandler,
  setControlHandler,
  updatePlaybackState,
  type CastPayload,
  type CastServerStatus,
} from '@/lib/castServer';
import type { VideoItem, SubtitleData } from '@/lib/castServer';
import { parseSubtitle, type SubtitleCue } from '@/lib/subtitleParser';

// ─── 类型定义 ─────────────────────────────────────────────────────────────────
export interface CastServerState {
  status: 'idle' | 'starting' | 'running' | 'error';
  port: number;
  localIp: string;
  clientCount: number;
  deviceId: string;
  deviceName: string;
}

export interface CastMessage {
  type: 'play' | 'playlist' | 'subtitle' | 'pause' | 'resume' | 'seek' | 'stop' | 'volume';
  payload: any;
  timestamp: number;
}

interface PlayerContextValue {
  playlist: VideoItem[];
  currentIndex: number;
  currentVideo: VideoItem | null;
  subtitleCues: SubtitleCue[];
  activeCueText: string;
  isPlaying: boolean;
  position: number;
  serverState: CastServerState;
  startServer: () => Promise<void>;
  stopServer: () => Promise<void>;
  playNext: () => void;
  playPrev: () => void;
  playAt: (index: number) => void;
  setPlaying: (v: boolean) => void;
  setPosition: (s: number) => void;
  setActiveCue: (text: string) => void;
  volume: number;
  setVolume: (v: number) => void;
  loadSubtitleText: (text: string) => void;
  demoPlay: (url: string, title: string) => void;
  setSourceUrl: (url: string, title: string) => void;
  play: () => void;
  // For castServer integration
  injectMessage?: (msg: CastMessage) => void;
  setOnStatus?: (fn: (s: CastServerState) => void) => void;
  setOnMessage?: (fn: (msg: CastMessage) => void) => void;
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
    port: 0,
    localIp: '...',
    clientCount: 0,
    deviceId: '',
    deviceName: '投屏助手',
  });

  const currentVideo = playlist[currentIndex] ?? null;

  // Refs for playback state used by interval
  const dlnaRef = useRef({ position: 0, isPlaying: false, volume: 1, durationMs: 0 });
  dlnaRef.current = { position, isPlaying, volume, durationMs: dlnaRef.current.durationMs };

  // Handle incoming cast from DLNA
  const handleIncomingCast = useCallback((payload: CastPayload) => {
    const video: VideoItem = {
      id: Date.now().toString(),
      url: payload.url,
      title: payload.title || '投屏视频',
      type: payload.type || 'video',
    };
    setPlaylist([video]);
    setCurrentIndex(0);
    setSubtitleCues([]);
    setActiveCueText('');
    setIsPlayingState(true);
    setPositionState(payload.position || 0);
  }, []);

  const handleControl = useCallback((action: string, params?: any) => {
    switch (action) {
      case 'play':
      case 'resume':
        setIsPlayingState(true);
        break;
      case 'pause':
        setIsPlayingState(false);
        break;
      case 'stop':
        setIsPlayingState(false);
        setSubtitleCues([]);
        setActiveCueText('');
        break;
      case 'seek':
        setPositionState(params?.position || 0);
        break;
      case 'volume':
        setVolume(params?.volume ?? 1);
        break;
      case 'next':
        setCurrentIndex((i) => Math.min(i + 1, playlist.length - 1));
        setSubtitleCues([]);
        setActiveCueText('');
        break;
      case 'previous':
        setCurrentIndex((i) => Math.max(i - 1, 0));
        setSubtitleCues([]);
        setActiveCueText('');
        break;
    }
  }, [playlist.length]);

  // ─── 字幕加载 ──────────────────────────────────────────────────────────────
  const loadSubtitleText = useCallback((text: string) => {
    const cues = parseSubtitle(text);
    setSubtitleCues(cues);
    setActiveCueText('');
  }, []);

  // ─── 启动服务器 ────────────────────────────────────────────────────────────
  const startServer = useCallback(async () => {
    setServerState((s) => ({ ...s, status: 'starting' }));
    try {
      setIncomingCastHandler(handleIncomingCast);
      setControlHandler(handleControl);
      const status: CastServerStatus = await startCastServer();
      setServerState({
        status: status.running ? 'running' : 'error',
        port: status.port,
        localIp: status.ip || '...',
        clientCount: 0,
        deviceId: status.deviceId,
        deviceName: status.friendlyName,
      });
    } catch (e) {
      console.warn('[playerContext] startServer failed:', e);
      setServerState((s) => ({ ...s, status: 'error' }));
    }
  }, [handleIncomingCast, handleControl]);

  const stopServer = useCallback(async () => {
    try {
      await stopCastServer();
      setServerState((s) => ({ ...s, status: 'idle' }));
    } catch (e) {
      console.warn('[playerContext] stopServer failed:', e);
    }
  }, []);

  // ─── 播放列表控制 ──────────────────────────────────────────────────────────
  const playNext = useCallback(() => {
    setCurrentIndex((i) => (i + 1 < playlist.length ? i + 1 : i));
    setSubtitleCues([]);
    setActiveCueText('');
  }, [playlist.length]);

  const playPrev = useCallback(() => {
    setCurrentIndex((i) => (i > 0 ? i - 1 : 0));
    setSubtitleCues([]);
    setActiveCueText('');
  }, []);

  const playAt = useCallback((index: number) => {
    setCurrentIndex(index);
    setSubtitleCues([]);
    setActiveCueText('');
    setIsPlayingState(true);
  }, []);

  const demoPlay = useCallback((url: string, title: string) => {
    const video: VideoItem = { id: Date.now().toString(), url, title };
    setPlaylist([video]);
    setCurrentIndex(0);
    setSubtitleCues([]);
    setActiveCueText('');
    setIsPlayingState(true);
  }, []);

  const setSourceUrl = useCallback((url: string, title: string) => {
    const video: VideoItem = { id: Date.now().toString(), url, title };
    setPlaylist([video]);
    setCurrentIndex(0);
    setSubtitleCues([]);
    setActiveCueText('');
  }, []);

  const play = useCallback(() => {
    setIsPlayingState(true);
  }, []);

  // Periodically report playback state to native DLNA module
  useEffect(() => {
    const poll = setInterval(() => {
      try {
        const s = dlnaRef.current;
        updatePlaybackState({
          positionMs: Math.round(s.position * 1000),
          durationMs: s.durationMs,
          isPlaying: s.isPlaying,
          volume: Math.round(s.volume * 100),
        });
      } catch (e) {
        // ignore
      }
    }, 1000);
    return () => clearInterval(poll);
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      stopCastServer().catch(() => {});
    };
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

  return <PlayerContext.Provider value={value}>{children}</PlayerContext.Provider>;
}

// ─── Hook ────────────────────────────────────────────────────────────────────
export function usePlayer() {
  const ctx = useContext(PlayerContext);
  if (!ctx) throw new Error('usePlayer must be used inside PlayerProvider');
  return ctx;
}
