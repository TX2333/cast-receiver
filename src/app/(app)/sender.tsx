/**
 * 发送端页面
 * ─────────────────────────────────────────────────
 * 功能：
 * 1. 扫描二维码 → 直接定向到指定设备投屏
 * 2. 自动发现局域网内在线的接收端设备列表
 * 3. 输入视频 URL 向选中设备发送 play 指令
 *
 * 这个页面模拟"发送端"角色，方便在同一个 App 里测试完整流程。
 */
import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  Text,
  TextInput,
  View,
  ScrollView,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { useRouter } from 'expo-router';
import { useFocusEffect } from '@react-navigation/native';
import {
  ArrowLeft,
  Cast,
  RefreshCw,
  Wifi,
  MonitorPlay,
  Send,
  CircleCheck,
  Clock,
} from 'lucide-react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withTiming,
} from 'react-native-reanimated';
import {
  fetchOnlineDevices,
  sendCastCommand,
  type OnlineDevice,
} from '@/lib/castServer';
import type { RelativePathString } from 'expo-router';

export default function SenderScreen() {
  const router = useRouter();
  const [devices, setDevices] = useState<OnlineDevice[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedDevice, setSelectedDevice] = useState<OnlineDevice | null>(null);
  const [videoUrl, setVideoUrl] = useState('');
  const [videoTitle, setVideoTitle] = useState('');
  const [sendStatus, setSendStatus] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle');
  const [errorMsg, setErrorMsg] = useState('');

  // 加载在线设备
  const loadDevices = useCallback(async () => {
    setLoading(true);
    try {
      const list = await fetchOnlineDevices();
      setDevices(list);
      // 如果有设备且没有选中，自动选第一个
      if (list.length > 0 && !selectedDevice) {
        setSelectedDevice(list[0]);
      }
    } catch {
      setDevices([]);
    } finally {
      setLoading(false);
    }
  }, [selectedDevice]);

  // 每次进入页面自动刷新
  useFocusEffect(
    useCallback(() => {
      loadDevices();
      // 每 5s 自动刷新设备列表
      const timer = setInterval(loadDevices, 5000);
      return () => clearInterval(timer);
    }, [loadDevices])
  );

  // 发送投屏指令
  const handleSend = async () => {
    if (!selectedDevice) { setErrorMsg('请先选择接收设备'); return; }
    if (!videoUrl.trim()) { setErrorMsg('请输入视频地址'); return; }
    setErrorMsg('');
    setSendStatus('sending');
    try {
      await sendCastCommand(selectedDevice.deviceId, 'play', {
        id: Date.now().toString(),
        url: videoUrl.trim(),
        title: videoTitle.trim() || '投屏视频',
      });
      setSendStatus('sent');
      setTimeout(() => setSendStatus('idle'), 3000);
    } catch {
      setSendStatus('error');
      setErrorMsg('发送失败，请检查网络');
    }
  };

  // 发送控制指令
  const handleControl = async (type: 'pause' | 'resume' | 'stop') => {
    if (!selectedDevice) return;
    await sendCastCommand(selectedDevice.deviceId, type);
  };

  // 发送按钮动画
  const sendScale = useSharedValue(1);
  const sendStyle = useAnimatedStyle(() => ({ transform: [{ scale: sendScale.value }] }));

  return (
    <View className="flex-1 bg-background">
      <StatusBar style="light" backgroundColor="#121212" />
      <ScrollView
        contentInsetAdjustmentBehavior="automatic"
        contentContainerStyle={{ flexGrow: 1 }}
        keyboardShouldPersistTaps="handled"
      >
        {/* 顶部栏 */}
        <View className="flex-row items-center gap-3 px-5 pt-14 pb-5">
          <Pressable
            className="w-9 h-9 items-center justify-center active:opacity-70"
            onPress={() => router.back()}
          >
            <ArrowLeft size={20} color="#fff" />
          </Pressable>
          <Text className="text-lg font-bold text-foreground flex-1">发送端</Text>
          <Pressable
            className="w-9 h-9 items-center justify-center active:opacity-70"
            onPress={loadDevices}
          >
            <RefreshCw size={18} color={loading ? '#00E5FF' : '#555'} />
          </Pressable>
        </View>

        <View className="px-5 gap-6 pb-10">
          {/* ── 设备发现 ── */}
          <View className="gap-3">
            <View className="flex-row items-center gap-2">
              <Wifi size={15} color="#00E5FF" />
              <Text className="text-xs text-muted-foreground tracking-widest uppercase">
                局域网设备发现
              </Text>
              {loading && <ActivityIndicator size={12} color="#00E5FF" />}
            </View>

            {devices.length === 0 && !loading ? (
              <View
                className="bg-card rounded p-5 items-center gap-3"
                style={{ borderRadius: 4 }}
              >
                <Cast size={32} color="#333" />
                <Text className="text-muted-foreground text-sm text-center">
                  未发现在线设备{'\n'}请确保接收端 App 已开启并处于同一网络
                </Text>
                <Pressable
                  className="border border-border px-4 py-2 rounded active:opacity-70"
                  onPress={loadDevices}
                >
                  <Text className="text-foreground text-xs">重新扫描</Text>
                </Pressable>
              </View>
            ) : (
              <View className="gap-2">
                {devices.map((device) => (
                  <DeviceCard
                    key={device.deviceId}
                    device={device}
                    selected={selectedDevice?.deviceId === device.deviceId}
                    onSelect={() => setSelectedDevice(device)}
                  />
                ))}
              </View>
            )}
          </View>

          {/* ── 投屏输入 ── */}
          <View className="gap-3">
            <View className="flex-row items-center gap-2">
              <MonitorPlay size={15} color="#00E5FF" />
              <Text className="text-xs text-muted-foreground tracking-widest uppercase">
                投送内容
              </Text>
            </View>

            <View
              className="bg-card rounded gap-4 p-4"
              style={{ borderRadius: 4 }}
            >
              {/* 视频标题 */}
              <View className="gap-1.5">
                <Text className="text-xs text-muted-foreground">视频标题（可选）</Text>
                <TextInput
                  value={videoTitle}
                  onChangeText={setVideoTitle}
                  placeholder="例：我的视频"
                  placeholderTextColor="#444"
                  className="bg-background text-foreground text-sm px-3 py-3 rounded"
                  style={{ borderRadius: 4, borderWidth: 1, borderColor: '#222' }}
                />
              </View>

              {/* 视频 URL */}
              <View className="gap-1.5">
                <Text className="text-xs text-muted-foreground">视频地址 *</Text>
                <TextInput
                  value={videoUrl}
                  onChangeText={setVideoUrl}
                  placeholder="https://example.com/video.mp4"
                  placeholderTextColor="#444"
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType="url"
                  multiline
                  className="bg-background text-foreground text-sm px-3 py-3 rounded"
                  style={{
                    borderRadius: 4,
                    borderWidth: 1,
                    borderColor: videoUrl ? '#00E5FF' : '#222',
                    minHeight: 72,
                    textAlignVertical: 'top',
                  }}
                />
              </View>

              {/* 错误提示 */}
              {errorMsg ? (
                <Text className="text-destructive text-xs">{errorMsg}</Text>
              ) : null}

              {/* 发送按钮 */}
              <Animated.View style={sendStyle}>
                <Pressable
                  className="rounded p-4 items-center flex-row justify-center gap-2 active:opacity-80"
                  style={{
                    borderRadius: 4,
                    backgroundColor:
                      sendStatus === 'sent' ? '#00C896' :
                      sendStatus === 'error' ? '#FF3D71' :
                      '#00E5FF',
                    opacity: sendStatus === 'sending' ? 0.7 : 1,
                  }}
                  onPress={handleSend}
                  disabled={sendStatus === 'sending'}
                >
                  {sendStatus === 'sending' ? (
                    <ActivityIndicator size={16} color="#121212" />
                  ) : sendStatus === 'sent' ? (
                    <CircleCheck size={16} color="#121212" />
                  ) : (
                    <Send size={16} color="#121212" />
                  )}
                  <Text className="font-bold text-sm" style={{ color: '#121212' }}>
                    {sendStatus === 'sending' ? '发送中...' :
                     sendStatus === 'sent' ? '已发送' :
                     sendStatus === 'error' ? '发送失败' :
                     '开始投屏'}
                  </Text>
                </Pressable>
              </Animated.View>
            </View>
          </View>

          {/* ── 远程控制 ── */}
          {selectedDevice && (
            <View className="gap-3">
              <View className="flex-row items-center gap-2">
                <Cast size={15} color="#00E5FF" />
                <Text className="text-xs text-muted-foreground tracking-widest uppercase">
                  远程控制
                </Text>
              </View>
              <View className="flex-row gap-3">
                {(['pause', 'resume', 'stop'] as const).map((cmd) => (
                  <Pressable
                    key={cmd}
                    className="flex-1 bg-card rounded py-3 items-center active:opacity-70"
                    style={{ borderRadius: 4 }}
                    onPress={() => handleControl(cmd)}
                  >
                    <Text className="text-foreground text-sm">
                      {cmd === 'pause' ? '暂停' : cmd === 'resume' ? '继续' : '停止'}
                    </Text>
                  </Pressable>
                ))}
              </View>
            </View>
          )}

          {/* ── 接收端入口 ── */}
          <Pressable
            className="flex-row items-center justify-center gap-2 py-4 border border-border rounded active:opacity-70"
            style={{ borderRadius: 4 }}
            onPress={() => router.replace('/(app)/home' as RelativePathString)}
          >
            <MonitorPlay size={16} color="#555" />
            <Text className="text-muted-foreground text-sm">切换到接收端</Text>
          </Pressable>
        </View>
      </ScrollView>
    </View>
  );
}

// ─── 设备卡片 ─────────────────────────────────────────────────────────────────
function DeviceCard({
  device,
  selected,
  onSelect,
}: {
  device: OnlineDevice;
  selected: boolean;
  onSelect: () => void;
}) {
  // 计算最后在线时间
  const secsAgo = Math.floor((Date.now() - new Date(device.lastSeen).getTime()) / 1000);
  const timeLabel = secsAgo < 5 ? '刚刚在线' : `${secsAgo}s 前`;

  return (
    <Pressable
      className="flex-row items-center bg-card rounded px-4 py-4 gap-3 active:opacity-80"
      style={{
        borderRadius: 4,
        borderWidth: 1.5,
        borderColor: selected ? '#00E5FF' : 'transparent',
      }}
      onPress={onSelect}
    >
      <View
        className="w-10 h-10 rounded items-center justify-center"
        style={{ backgroundColor: selected ? 'rgba(0,229,255,0.12)' : '#1a1a1a', borderRadius: 4 }}
      >
        <MonitorPlay size={18} color={selected ? '#00E5FF' : '#555'} />
      </View>
      <View className="flex-1 gap-0.5">
        <Text
          className="text-sm font-semibold"
          style={{ color: selected ? '#00E5FF' : '#fff' }}
        >
          {device.deviceName}
        </Text>
        <Text className="text-xs text-muted-foreground">
          {device.ip}:{device.port}
        </Text>
      </View>
      <View className="items-end gap-1">
        <View className="flex-row items-center gap-1">
          <View style={{ width: 6, height: 6, borderRadius: 3, backgroundColor: '#00E5FF' }} />
          <Text className="text-xs" style={{ color: '#00E5FF' }}>在线</Text>
        </View>
        <View className="flex-row items-center gap-1">
          <Clock size={10} color="#555" />
          <Text className="text-xs text-muted-foreground">{timeLabel}</Text>
        </View>
      </View>
    </Pressable>
  );
}
