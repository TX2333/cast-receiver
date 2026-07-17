/**
 * 视频状态管理 Context
 * 提供：播放列表、当前视频、字幕、连接状态的全局状态
 */
import React, { createContext, useCallback, useContext, useRef, useState } from 'react';
import { castServer, type CastMessage, type CastServerState, type VideoItem, type SubtitleData } from '@/lib/castServer';
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
  stopServer: () => void;
  // 播放控制
  playNext: () => void;
  playPrev: () => void;
  playAt: (index: number) => void;
  setPlaying: (v: boolean) => void;
  setPosition: (s: number) => void;
  setActiveCue: (text: string) => void;
  // 字幕加载
  loadSubtitleText: (text: string) => void;
  // 演示：手动投屏（测试用）
  demoPlay: (url: string, title: string) => void;
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
  const [serverState, setServerState] = useState<CastServerState>({
    status: 'idle',
    port: 7788,
    localIp: '...',
    clientCount: 0,
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
        setPlaylist([]);
        setCurrentIndex(0);
        setSubtitleCues([]);
        setActiveCueText('');
        break;
      default:
        break;
    }
  }, [loadSubtitleText]);

  // ─── 启动服务器 ────────────────────────────────────────────────────────────
  const startServer = useCallback(async () => {
    castServer.setOnMessage(handleMessage);
    castServer.setOnStatus(setServerState);
    await castServer.start();
  }, [handleMessage]);

  const stopServer = useCallback(() => {
    castServer.stop();
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
    loadSubtitleText,
    demoPlay,
  };

  return <PlayerContext.Provider value={value}>{children}</PlayerContext.Provider>;
}

// ─── Hook ────────────────────────────────────────────────────────────────────
export function usePlayer() {
  const ctx = useContext(PlayerContext);
  if (!ctx) throw new Error('usePlayer must be used inside PlayerProvider');
  return ctx;
}
