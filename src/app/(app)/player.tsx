/**
 * 视频播放页
 * - 全屏沉浸式播放器
 * - 自动选择音轨、挂载字幕
 * - 播放列表抽屉
 * - 悬浮控制栏（3s后自动隐藏）
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { useRouter } from 'expo-router';
import { useFocusEffect } from '@react-navigation/native';
import { useVideoPlayer, VideoView } from 'expo-video';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
  runOnJS,
} from 'react-native-reanimated';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system';
import {
  ArrowLeft,
  Play,
  Pause,
  SkipForward,
  SkipBack,
  List,
  Subtitles,
  X,
  FileText,
  CircleCheck,
  Cast,
  ChevronRight,
} from 'lucide-react-native';
import { usePlayer } from '@/lib/playerContext';
import { findActiveCue } from '@/lib/subtitleParser';
import type { RelativePathString } from 'expo-router';

// ─── 格式化时间 ────────────────────────────────────────────────────────────────
function formatTime(s: number): string {
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = Math.floor(s % 60);
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  return `${m}:${String(sec).padStart(2, '0')}`;
}

export default function PlayerScreen() {
  const router = useRouter();
  const {
    playlist,
    currentIndex,
    currentVideo,
    subtitleCues,
    activeCueText,
    isPlaying,
    position,
    setPlaying,
    setPosition,
    setActiveCue,
    playNext,
    playPrev,
    playAt,
    loadSubtitleText,
  } = usePlayer();

  // ─── 控制栏可见性 ────────────────────────────────────────────────────────
  const [controlsVisible, setControlsVisible] = useState(true);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const controlOpacity = useSharedValue(1);
  const controlStyle = useAnimatedStyle(() => ({ opacity: controlOpacity.value }));

  const showControls = useCallback(() => {
    controlOpacity.value = withTiming(1, { duration: 200 });
    setControlsVisible(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => {
      controlOpacity.value = withTiming(0, { duration: 400 });
      runOnJS(setControlsVisible)(false);
    }, 3000);
  }, [controlOpacity]);

  // ─── 播放列表抽屉 ────────────────────────────────────────────────────────
  const [drawerOpen, setDrawerOpen] = useState(false);
  const drawerX = useSharedValue(320);
  const drawerStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: drawerX.value }],
  }));

  const openDrawer = () => {
    drawerX.value = withTiming(0, { duration: 280 });
    setDrawerOpen(true);
  };
  const closeDrawer = () => {
    drawerX.value = withTiming(320, { duration: 280 });
    setDrawerOpen(false);
  };

  // ─── expo-video 播放器 ───────────────────────────────────────────────────
  const hasInteracted = useRef(process.env.EXPO_OS !== 'web');
  const [showOverlay, setShowOverlay] = useState(process.env.EXPO_OS === 'web');
  const [duration, setDuration] = useState(0);
  const [loadingVideo, setLoadingVideo] = useState(false);
  const positionTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  const player = useVideoPlayer(null, (p) => {
    p.loop = false;
  });

  // 加载视频
  useEffect(() => {
    if (!currentVideo?.url) return;
    setLoadingVideo(true);
    setActiveCue('');
    (async () => {
      try {
        await player.replaceAsync({ uri: currentVideo.url });
        if (hasInteracted.current) {
          player.play();
          setPlaying(true);
        }
      } catch {
        setLoadingVideo(false);
      }
      setLoadingVideo(false);
    })();
  }, [currentVideo?.url]);

  // 同步 isPlaying 状态（补全 player 依赖，防止 stale closure）
  useEffect(() => {
    if (isPlaying) {
      player.play();
    } else {
      player.pause();
    }
  }, [isPlaying, player]);

  // 轮询播放进度并更新字幕
  useEffect(() => {
    positionTimer.current = setInterval(() => {
      const pos = player.currentTime ?? 0;
      setPosition(pos);
      if (subtitleCues.length > 0) {
        const cue = findActiveCue(subtitleCues, pos);
        setActiveCue(cue?.text ?? '');
      }
      const dur = player.duration ?? 0;
      if (dur > 0) setDuration(dur);
    }, 300);
    return () => {
      if (positionTimer.current) clearInterval(positionTimer.current);
    };
  }, [player, subtitleCues, setPosition, setActiveCue]);

  // 播放结束时自动下一曲（补全 player/setPlaying 依赖）
  useEffect(() => {
    const sub = player.addListener('playToEnd', () => {
      if (currentIndex < playlist.length - 1) {
        playNext();
      } else {
        setPlaying(false);
      }
    });
    return () => sub.remove();
  }, [player, currentIndex, playlist.length, playNext, setPlaying]);

  // 初始化时展示控制栏
  useFocusEffect(
    useCallback(() => {
      showControls();
      return () => {
        if (hideTimer.current) clearTimeout(hideTimer.current);
      };
    }, [showControls])
  );

  // ─── 外挂字幕加载 ────────────────────────────────────────────────────────
  const loadExternalSubtitle = async () => {
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ['text/*', 'application/octet-stream'],
        copyToCacheDirectory: true,
      });
      if (result.canceled) return;
      const file = result.assets?.[0];
      if (!file?.uri) return;

      let content: string;
      if (process.env.EXPO_OS === 'web') {
        const resp = await fetch(file.uri);
        content = await resp.text();
      } else {
        content = await FileSystem.readAsStringAsync(file.uri);
      }
      loadSubtitleText(content);
    } catch {
      // 忽略取消操作
    }
  };

  // ─── 无视频时返回首页 ────────────────────────────────────────────────────
  if (!currentVideo) {
    return (
      <View className="flex-1 bg-background items-center justify-center gap-4">
        <StatusBar style="light" backgroundColor="#121212" />
        <Cast size={48} color="#555" />
        <Text className="text-muted-foreground text-base">等待投屏...</Text>
        <Pressable
          className="border border-border px-6 py-3 rounded active:opacity-70"
          onPress={() => router.replace('/(app)/home' as RelativePathString)}
        >
          <Text className="text-foreground text-sm">返回连接页</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View className="flex-1 bg-black">
      <StatusBar style="light" backgroundColor="#000" />

      {/* 视频区域 */}
      <Pressable
        style={StyleSheet.absoluteFill}
        onPress={showControls}
      >
        {process.env.EXPO_OS === 'web' ? (
          /* Web: 使用标准 video 标签 */
          <View style={StyleSheet.absoluteFill} className="items-center justify-center">
            {/* eslint-disable-next-line @typescript-eslint/ban-ts-comment */}
            {/* @ts-ignore */}
            <video
              src={currentVideo.url}
              style={{ width: '100%', height: '100%', objectFit: 'contain' }}
              controls={false}
              autoPlay={false}
            />
          </View>
        ) : (
        <VideoView
            style={StyleSheet.absoluteFill}
            player={player}
            contentFit="contain"
            nativeControls={false}
          />
        )}

        {/* Web tap-to-play 遮罩 */}
        {showOverlay && (
          <Pressable
            style={[StyleSheet.absoluteFill, { backgroundColor: 'rgba(0,0,0,0.5)' }]}
            className="items-center justify-center"
            onPress={() => {
              hasInteracted.current = true;
              setShowOverlay(false);
              player.replaceAsync({ uri: currentVideo.url }).then(() => player.play());
              setPlaying(true);
            }}
          >
            <View
              className="w-16 h-16 rounded-full items-center justify-center"
              style={{ backgroundColor: 'rgba(0,229,255,0.2)', borderWidth: 2, borderColor: '#00E5FF' }}
            >
              <Play size={28} color="#00E5FF" fill="#00E5FF" />
            </View>
            <Text className="text-white text-sm mt-3 opacity-70">点击播放</Text>
          </Pressable>
        )}

        {/* 加载指示 */}
        {loadingVideo && (
          <View
            style={[StyleSheet.absoluteFill, { backgroundColor: 'rgba(0,0,0,0.6)' }]}
            className="items-center justify-center"
          >
            <ActivityIndicator size="large" color="#00E5FF" />
            <Text className="text-white text-xs mt-3 opacity-70">处理中...</Text>
          </View>
        )}
      </Pressable>

      {/* 字幕显示 */}
      {activeCueText ? (
        <View
          className="absolute bottom-24 left-4 right-4 items-center"
          pointerEvents="none"
        >
          <View
            className="px-4 py-2 rounded"
            style={{ backgroundColor: 'rgba(0,0,0,0.75)', borderRadius: 2 }}
          >
            <Text
              className="text-white text-base text-center leading-6"
              style={{ textShadowColor: 'rgba(0,0,0,0.9)', textShadowRadius: 4 }}
            >
              {activeCueText}
            </Text>
          </View>
        </View>
      ) : null}

      {/* 悬浮控制栏 */}
      <Animated.View
        style={[StyleSheet.absoluteFill, controlStyle]}
        pointerEvents={controlsVisible ? 'box-none' : 'none'}
      >
        {/* 顶部栏 */}
        <View
          className="flex-row items-center justify-between px-4 pt-12 pb-4"
        >
          <Pressable
            className="w-10 h-10 items-center justify-center active:opacity-70"
            onPress={() => router.back()}
          >
            <ArrowLeft size={22} color="#fff" />
          </Pressable>

          <Text
            className="text-white text-sm font-semibold flex-1 mx-3"
            numberOfLines={1}
          >
            {currentVideo.title}
          </Text>

          <View className="flex-row gap-3">
            <Pressable
              className="w-9 h-9 items-center justify-center active:opacity-70"
              onPress={loadExternalSubtitle}
            >
              <Subtitles size={20} color={subtitleCues.length > 0 ? '#00E5FF' : '#fff'} />
            </Pressable>
            <Pressable
              className="w-9 h-9 items-center justify-center active:opacity-70"
              onPress={openDrawer}
            >
              <List size={20} color="#fff" />
            </Pressable>
          </View>
        </View>

        {/* 底部控制栏 */}
        <View
          className="absolute bottom-0 left-0 right-0 px-5 pb-10"
          style={{ paddingTop: 40 }}
        >
          {/* 进度条 */}
          <ProgressBar
            position={position}
            duration={duration}
            onSeek={(t) => {
              player.seekBy(t - position);
              setPosition(t);
              showControls();
            }}
          />

          {/* 时间 */}
          <View className="flex-row justify-between mt-1.5 mb-4">
            <Text className="text-xs text-white opacity-60">{formatTime(position)}</Text>
            <Text className="text-xs text-white opacity-60">{formatTime(duration)}</Text>
          </View>

          {/* 播放按钮组 */}
          <View className="flex-row items-center justify-center gap-8">
            <Pressable
              className="active:opacity-70"
              onPress={() => { playPrev(); showControls(); }}
            >
              <SkipBack size={28} color="#fff" />
            </Pressable>

            <Pressable
              className="w-14 h-14 rounded-full items-center justify-center active:opacity-70"
              style={{ backgroundColor: 'rgba(0,229,255,0.15)', borderWidth: 1.5, borderColor: '#00E5FF' }}
              onPress={() => { setPlaying(!isPlaying); showControls(); }}
            >
              {isPlaying
                ? <Pause size={26} color="#00E5FF" fill="#00E5FF" />
                : <Play size={26} color="#00E5FF" fill="#00E5FF" />
              }
            </Pressable>

            <Pressable
              className="active:opacity-70"
              onPress={() => { playNext(); showControls(); }}
            >
              <SkipForward size={28} color="#fff" />
            </Pressable>
          </View>

          {/* 字幕状态提示 */}
          {subtitleCues.length > 0 && (
            <View className="flex-row items-center justify-center gap-1 mt-4">
              <CircleCheck size={12} color="#00E5FF" />
              <Text className="text-xs" style={{ color: '#00E5FF' }}>
                字幕已加载 ({subtitleCues.length} 条)
              </Text>
            </View>
          )}
        </View>
      </Animated.View>

      {/* 播放列表抽屉 */}
      {drawerOpen && (
        <Pressable
          style={StyleSheet.absoluteFill}
          onPress={closeDrawer}
        />
      )}
      <Animated.View
        style={[
          {
            position: 'absolute',
            top: 0,
            bottom: 0,
            right: 0,
            width: 300,
            backgroundColor: 'rgba(15,15,15,0.97)',
            borderLeftWidth: 1,
            borderLeftColor: '#222',
          },
          drawerStyle,
        ]}
      >
        <View className="flex-row items-center justify-between px-4 pt-12 pb-4 border-b border-border">
          <Text className="text-foreground text-base font-bold">播放列表</Text>
          <Pressable onPress={closeDrawer} className="active:opacity-70">
            <X size={20} color="#fff" />
          </Pressable>
        </View>

        <FlatList
          data={playlist}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ paddingVertical: 8 }}
          renderItem={({ item, index }) => {
            const isCurrent = index === currentIndex;
            return (
              <Pressable
                className="flex-row items-center px-4 py-3 gap-3 active:opacity-70"
                style={{ backgroundColor: isCurrent ? 'rgba(0,229,255,0.08)' : 'transparent' }}
                onPress={() => { playAt(index); closeDrawer(); }}
              >
                <View className="w-8 h-8 items-center justify-center">
                  {isCurrent
                    ? <Play size={16} color="#00E5FF" fill="#00E5FF" />
                    : <Text className="text-muted-foreground text-sm">{index + 1}</Text>
                  }
                </View>
                <View className="flex-1">
                  <Text
                    className="text-sm font-medium"
                    style={{ color: isCurrent ? '#00E5FF' : '#fff' }}
                    numberOfLines={1}
                  >
                    {item.title}
                  </Text>
                  {item.duration ? (
                    <Text className="text-xs text-muted-foreground mt-0.5">
                      {formatTime(item.duration)}
                    </Text>
                  ) : null}
                </View>
                {isCurrent && <ChevronRight size={14} color="#00E5FF" />}
              </Pressable>
            );
          }}
          ListEmptyComponent={
            <View className="items-center justify-center py-16 gap-3">
              <FileText size={36} color="#333" />
              <Text className="text-muted-foreground text-sm">暂无播放列表</Text>
            </View>
          }
        />
      </Animated.View>
    </View>
  );
}

// ─── 进度条组件 ────────────────────────────────────────────────────────────────
function ProgressBar({
  position,
  duration,
  onSeek,
}: {
  position: number;
  duration: number;
  onSeek: (t: number) => void;
}) {
  const ratio = duration > 0 ? Math.min(position / duration, 1) : 0;

  return (
    <Pressable
      style={{ height: 20, justifyContent: 'center' }}
      onPress={(e) => {
        // Web/Native: 通过 nativeEvent.locationX 计算点击位置
        // 简化版：nativeEvent.locationX / width * duration
        const x = (e.nativeEvent as { locationX?: number }).locationX ?? 0;
        // 宽度通过 onLayout 更准确，这里用固定估值
        const width = 340;
        const t = (x / width) * duration;
        onSeek(Math.max(0, Math.min(t, duration)));
      }}
    >
      {/* 轨道 */}
      <View style={{ height: 2, backgroundColor: 'rgba(255,255,255,0.2)', borderRadius: 1 }}>
        {/* 进度 */}
        <View
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: `${ratio * 100}%`,
            backgroundColor: '#00E5FF',
            borderRadius: 1,
          }}
        />
        {/* 滑块 */}
        <View
          style={{
            position: 'absolute',
            top: -4,
            left: `${ratio * 100}%`,
            width: 10,
            height: 10,
            borderRadius: 5,
            backgroundColor: '#00E5FF',
            marginLeft: -5,
          }}
        />
      </View>
    </Pressable>
  );
}
