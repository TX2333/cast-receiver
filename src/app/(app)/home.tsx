/**
 * 待连接主页（接收端）
 * - 展示二维码和局域网连接信息
 * - 自动启动投屏服务器，向 Supabase 广播设备在线
 * - 连接建立后自动跳转播放页
 */
import { useCallback, useEffect, useRef } from 'react';
import { ActivityIndicator, Pressable, ScrollView, Text, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { useRouter } from 'expo-router';
import { useFocusEffect } from '@react-navigation/native';
import QRCode from 'react-native-qrcode-svg';
import { Wifi, Cast, MonitorPlay, Info, Send } from 'lucide-react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  Easing,
} from 'react-native-reanimated';
import { buildQrPayload } from '@/lib/castServer';
import { usePlayer } from '@/lib/playerContext';
import type { RelativePathString } from 'expo-router';

// ─── 扫描框角标组件 ───────────────────────────────────────────────────────────
function ScanCorner({ position }: { position: 'tl' | 'tr' | 'bl' | 'br' }) {
  const isTop = position === 'tl' || position === 'tr';
  const isLeft = position === 'tl' || position === 'bl';
  return (
    <View
      style={{
        position: 'absolute',
        top: isTop ? -2 : undefined,
        bottom: !isTop ? -2 : undefined,
        left: isLeft ? -2 : undefined,
        right: !isLeft ? -2 : undefined,
        width: 20,
        height: 20,
        borderTopWidth: isTop ? 2 : 0,
        borderBottomWidth: !isTop ? 2 : 0,
        borderLeftWidth: isLeft ? 2 : 0,
        borderRightWidth: !isLeft ? 2 : 0,
        borderColor: '#00E5FF',
      }}
    />
  );
}

export default function HomeScreen() {
  const router = useRouter();
  const { serverState, startServer, currentVideo, demoPlay } = usePlayer();
  const prevVideoRef = useRef<string | null>(null);

  // 服务器启动
  useFocusEffect(
    useCallback(() => {
      startServer();
    }, [startServer])
  );

  // 连接成功且收到视频时自动跳转
  useEffect(() => {
    if (currentVideo && currentVideo.id !== prevVideoRef.current) {
      prevVideoRef.current = currentVideo.id;
      router.push('/(app)/player');
    }
  }, [currentVideo, router]);

  // 二维码内容（用 deviceId 标识，发送端扫码后到 Supabase 找到此设备）
  const qrValue = buildQrPayload(serverState.deviceId || 'loading');
  const isRunning = serverState.status === 'running';

  // 呼吸动画（等待连接时）
  const opacity = useSharedValue(1);
  useEffect(() => {
    opacity.value = withRepeat(
      withTiming(0.4, { duration: 1400, easing: Easing.inOut(Easing.ease) }),
      -1,
      true
    );
  }, [opacity]);
  const breathStyle = useAnimatedStyle(() => ({ opacity: opacity.value }));

  return (
    <View className="flex-1 bg-background">
      <StatusBar style="light" backgroundColor="#121212" />
      <ScrollView
        contentInsetAdjustmentBehavior="automatic"
        contentContainerStyle={{ flexGrow: 1 }}
        className="flex-1"
      >
        {/* 顶部标题栏 */}
        <View className="flex-row items-center justify-between px-6 pt-14 pb-4">
          <View className="flex-row items-center gap-2">
            <MonitorPlay size={22} color="#00E5FF" />
            <Text className="text-xl font-bold text-foreground tracking-widest">投屏助手</Text>
          </View>
          <View className="flex-row items-center gap-1.5">
            <View
              style={{
                width: 8,
                height: 8,
                borderRadius: 4,
                backgroundColor: isRunning ? '#00E5FF' : '#555',
              }}
            />
            <Text className="text-xs text-muted-foreground">
              {isRunning ? '接收就绪' : '启动中...'}
            </Text>
          </View>
        </View>

        {/* 主内容区 */}
        <View className="flex-1 items-center justify-center px-6 py-8 gap-10">
          {/* 二维码区域 */}
          <View className="items-center gap-6">
            <Text className="text-sm text-muted-foreground tracking-widest uppercase">
              扫码连接投屏
            </Text>

            <View className="relative p-5 bg-white rounded" style={{ borderRadius: 4 }}>
              <ScanCorner position="tl" />
              <ScanCorner position="tr" />
              <ScanCorner position="bl" />
              <ScanCorner position="br" />
              {isRunning ? (
                <QRCode
                  value={qrValue}
                  size={200}
                  backgroundColor="white"
                  color="#121212"
                />
              ) : (
                <View style={{ width: 200, height: 200 }} className="items-center justify-center">
                  <ActivityIndicator size="large" color="#00E5FF" />
                </View>
              )}
            </View>
          </View>

          {/* 分隔线 */}
          <View className="flex-row items-center gap-3 w-full">
            <View className="flex-1 h-px bg-border" />
            <Text className="text-xs text-muted-foreground">或</Text>
            <View className="flex-1 h-px bg-border" />
          </View>

          {/* 局域网连接信息 */}
          <View className="w-full gap-4">
            <Text className="text-sm text-muted-foreground tracking-widest uppercase text-center">
              局域网直连
            </Text>
            <View className="bg-card rounded p-5 gap-4" style={{ borderRadius: 4 }}>
              {/* 设备名 */}
              <InfoRow
                icon={<MonitorPlay size={16} color="#00E5FF" />}
                label="设备名称"
                value={serverState.deviceName || '投屏助手'}
                highlight
              />
              <View className="h-px bg-border" />
              {/* 设备地址 */}
              <InfoRow
                icon={<Wifi size={16} color="#00E5FF" />}
                label="设备地址"
                value={isRunning ? serverState.localIp : '获取中...'}
                highlight
              />
              <View className="h-px bg-border" />
              {/* 端口 */}
              <InfoRow
                icon={<Cast size={16} color="#555" />}
                label="服务端口"
                value={isRunning ? String(serverState.port) : '—'}
              />
            </View>

            {/* 提示 */}
            <View className="flex-row items-start gap-2 px-1">
              <Info size={13} color="#555" style={{ marginTop: 2 }} />
              <Text className="flex-1 text-xs text-muted-foreground leading-5">
                确保发送端与本设备处于同一局域网，扫描二维码或在投屏软件中输入上方地址即可连接
              </Text>
            </View>
          </View>
        </View>

        {/* 演示按钮 + 发送端入口 */}
        <View className="px-6 pb-12 gap-3">
          <Pressable
            className="bg-primary rounded p-4 items-center active:opacity-70"
            style={{ borderRadius: 4 }}
            onPress={() =>
              demoPlay(
                'https://miaoda-zhibo.bj.bcebos.com/AppVideo/%E7%A7%92%E5%93%92%E4%BA%A7%E5%93%81%E5%8A%9F%E8%83%BD%E4%BB%8B%E7%BB%8D_%E4%B8%AD%E6%96%87%E7%89%88.mp4',
                '投屏助手演示视频'
              )
            }
          >
            <Text className="text-primary-foreground font-bold tracking-widest text-sm">
              演示投屏
            </Text>
          </Pressable>

          {/* 发送端入口 */}
          <Pressable
            className="flex-row items-center justify-center gap-2 border border-border rounded py-3 active:opacity-70"
            style={{ borderRadius: 4 }}
            onPress={() => router.push('/(app)/sender' as RelativePathString)}
          >
            <Send size={14} color="#555" />
            <Text className="text-muted-foreground text-xs">切换到发送端 / 向其他设备投屏</Text>
          </Pressable>

          <Animated.View style={breathStyle}>
            <Text className="text-center text-xs text-muted-foreground">
              {isRunning ? `设备已广播在线 · ${serverState.localIp}` : '等待服务启动...'}
            </Text>
          </Animated.View>
        </View>
      </ScrollView>
    </View>
  );
}

// ─── 信息行组件 ───────────────────────────────────────────────────────────────
function InfoRow({
  icon,
  label,
  value,
  highlight = false,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  highlight?: boolean;
}) {
  return (
    <View className="flex-row items-center justify-between">
      <View className="flex-row items-center gap-2">
        {icon}
        <Text className="text-sm text-muted-foreground">{label}</Text>
      </View>
      <Text
        className="text-sm font-bold tracking-widest"
        style={{ color: highlight ? '#00E5FF' : '#fff', letterSpacing: 2 }}
      >
        {value}
      </Text>
    </View>
  );
}
