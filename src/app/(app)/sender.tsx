/**
 * 发送端页面（局域网 DLNA/UPnP 直连）
 * - 通过 SSDP 在局域网内发现可投屏设备
 * - 输入视频 URL 后，直接向选中设备发送 SetAVTransportURI + Play 指令
 */
import { useCallback, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  RefreshControl,
  ScrollView,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { Stack, useRouter } from 'expo-router';
import { useFocusEffect } from '@react-navigation/native';
import { usePlayer } from '@/lib/playerContext';
import { CastReceiverApi } from '@/lib/castReceiver';
import { castToDevice, type DeviceInfo } from '@/lib/castServer';
import type { RelativePathString } from 'expo-router';

function hostOf(location: string): string {
  try {
    return new URL(location).host;
  } catch {
    return location;
  }
}

export default function SenderScreen() {
  const router = useRouter();
  const { setSourceUrl, play } = usePlayer();

  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<DeviceInfo | null>(null);
  const [videoUrl, setVideoUrl] = useState('');
  const [videoTitle, setVideoTitle] = useState('');
  const [sendStatus, setSendStatus] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const loadDevices = useCallback(async () => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const list = await CastReceiverApi.discover(4000);
      setDevices(list);
      setSelected((prev) => (prev && list.some((d) => d.location === prev.location) ? prev : null));
    } catch (e) {
      setErrorMsg('发现设备失败：' + (e instanceof Error ? e.message : String(e)));
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      loadDevices();
      const t = setInterval(loadDevices, 8000);
      return () => clearInterval(t);
    }, [loadDevices])
  );

  const handleSend = useCallback(async () => {
    if (!selected) {
      Alert.alert('请先选择设备');
      return;
    }
    if (!videoUrl.trim()) {
      Alert.alert('请输入视频地址');
      return;
    }
    setSendStatus('发送中...');
    try {
      await castToDevice(selected, { url: videoUrl.trim(), title: videoTitle.trim() || '视频', type: 'video' });
      setSendStatus('已发送');
    } catch (e) {
      setSendStatus(null);
      setErrorMsg('发送失败：' + (e instanceof Error ? e.message : String(e)));
    }
  }, [selected, videoUrl, videoTitle]);

  const handleLocalPlay = useCallback(() => {
    if (!videoUrl.trim()) {
      Alert.alert('请输入视频地址');
      return;
    }
    setSourceUrl(videoUrl.trim(), videoTitle.trim() || '视频');
    play();
    setSendStatus('已在本机播放');
  }, [videoUrl, videoTitle, setSourceUrl, play]);

  return (
    <ScrollView
      className="flex-1 bg-background"
      contentContainerStyle={{ padding: 16, gap: 20 }}
      refreshControl={<RefreshControl refreshing={loading} onRefresh={loadDevices} />}
    >
      <Stack.Screen options={{ title: '发送到设备' }} />

      {/* 设备发现 */}
      <View className="gap-3">
        <View className="flex-row items-center justify-between">
          <Text className="text-foreground text-lg font-bold">局域网设备</Text>
          <TouchableOpacity onPress={loadDevices} className="flex-row items-center gap-1">
            <Text className="text-cyan-400 text-sm">{loading ? '搜索中...' : '刷新'}</Text>
            {loading && <ActivityIndicator size="small" color="#22d3ee" />}
          </TouchableOpacity>
        </View>

        {errorMsg && <Text className="text-red-400 text-xs">{errorMsg}</Text>}

        {devices.length === 0 && !loading ? (
          <View className="border border-dashed border-border rounded-lg p-6 items-center gap-2">
            <Text className="text-muted-foreground text-sm text-center">
              未发现设备。请确认：{'\n'}1. 接收端 App 已在本机/同网段运行{'\n'}2. 手机与机顶盒在同一 Wi-Fi
            </Text>
          </View>
        ) : (
          devices.map((d) => (
            <TouchableOpacity
              key={d.id}
              onPress={() => setSelected(d)}
              className={
                'flex-row items-center justify-between rounded-lg border p-4 ' +
                (selected?.id === d.id ? 'border-cyan-400 bg-cyan-400/10' : 'border-border')
              }
            >
              <View className="flex-1 mr-3">
                <Text className="text-foreground font-semibold">{d.name || '投屏设备'}</Text>
                {d.location ? (
                  <Text className="text-muted-foreground text-xs mt-1">{hostOf(d.location)}</Text>
                ) : null}
              </View>
              {selected?.id === d.id && (
                <View className="w-6 h-6 rounded-full bg-cyan-400 items-center justify-center">
                  <Text className="text-black text-xs">✓</Text>
                </View>
              )}
            </TouchableOpacity>
          ))
        )}
      </View>

      {/* 视频地址 */}
      <View className="gap-3">
        <Text className="text-foreground text-lg font-bold">视频地址</Text>
        <TextInput
          className="bg-card border border-border rounded-lg px-4 py-3 text-foreground"
          placeholder="https://example.com/video.mp4"
          placeholderTextColor="#666"
          value={videoUrl}
          onChangeText={setVideoUrl}
          autoCapitalize="none"
          autoCorrect={false}
        />
        <TextInput
          className="bg-card border border-border rounded-lg px-4 py-3 text-foreground"
          placeholder="标题（可选）"
          placeholderTextColor="#666"
          value={videoTitle}
          onChangeText={setVideoTitle}
        />
      </View>

      {/* 操作 */}
      <View className="gap-3">
        <TouchableOpacity
          onPress={handleSend}
          disabled={!selected || !videoUrl.trim()}
          className={
            'rounded-lg py-3.5 items-center ' + (!selected || !videoUrl.trim() ? 'bg-cyan-400/40' : 'bg-cyan-400')
          }
        >
          <Text className={'font-bold ' + (!selected || !videoUrl.trim() ? 'text-white/60' : 'text-black')}>
            发送到设备
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          onPress={handleLocalPlay}
          className="rounded-lg py-3.5 items-center border border-border"
        >
          <Text className="text-foreground font-bold">本机演示播放</Text>
        </TouchableOpacity>

        {sendStatus && <Text className="text-cyan-400 text-center text-sm">{sendStatus}</Text>}
      </View>

      <TouchableOpacity
        onPress={() => router.replace('/(app)/home' as RelativePathString)}
        className="items-center py-2"
      >
        <Text className="text-muted-foreground text-sm">切换到接收端</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}
