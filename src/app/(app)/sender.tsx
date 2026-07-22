/**
 * 发送端页面（局域网 DLNA/UPnP 直连）
 * - 通过 SSDP 在局域网内发现可投屏设备（含本 App 接收端、以及手机视频软件可识别的 DMR）
 * - 输入视频 URL 后，直接向选中设备发送 SetAVTransportURI + Play 指令
 */
import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  RefreshControl,
  ScrollView,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { Stack, useRouter } from 'expo-router';
import { useFocusEffect } from '@react-navigation/native';
import { usePlayer } from '@/lib/playerContext';
import { discoverReceivers, type DiscoveredDevice } from '@/lib/castReceiver';
import type { RelativePathString } from 'expo-router';

const SOAP_NS = 'urn:schemas-upnp-org:service:AVTransport:1';

function escapeXml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function buildSetAVTransportURI(uri: string, title: string): string {
  const meta =
    '<?xml version="1.0" encoding="utf-8"?>' +
    '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">' +
    '<item id="1" parentID="0" restricted="0">' +
    '<dc:title>' + escapeXml(title || '视频') + '</dc:title>' +
    '<upnp:class>object.item.videoItem</upnp:class>' +
    '<res protocolInfo="http-get:*:video/mp4:*">' + escapeXml(uri) + '</res>' +
    '</item></DIDL-Lite>';
  return (
    '<?xml version="1.0" encoding="utf-8"?>' +
    '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">' +
    '<s:Body><u:SetAVTransportURI xmlns:u="' + SOAP_NS + '">' +
    '<InstanceID>0</InstanceID>' +
    '<CurrentURI>' + escapeXml(uri) + '</CurrentURI>' +
    '<CurrentURIMetaData>' + escapeXml(meta) + '</CurrentURIMetaData>' +
    '</u:SetAVTransportURI></s:Body></s:Envelope>'
  );
}

function buildAction(action: string): string {
  const extra = action === 'Play' ? '<Speed>1</Speed>' : '';
  return (
    '<?xml version="1.0" encoding="utf-8"?>' +
    '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">' +
    '<s:Body><u:' + action + ' xmlns:u="' + SOAP_NS + '"><InstanceID>0</InstanceID>' + extra + '</u:' + action + '></s:Body></s:Envelope>'
  );
}

async function postSoap(url: string, action: string, body: string): Promise<void> {
  await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'text/xml; charset="utf-8"',
      SOAPACTION: '"' + SOAP_NS + '#' + action + '"',
    },
    body,
  });
}

function controlUrlFor(device: DiscoveredDevice): string {
  const base = device.location.replace(/\/description\.xml$/i, '');
  return base + '/upnp/control/AVTransport';
}

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

  const [devices, setDevices] = useState<DiscoveredDevice[]>([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<DiscoveredDevice | null>(null);
  const [videoUrl, setVideoUrl] = useState('');
  const [videoTitle, setVideoTitle] = useState('');
  const [sendStatus, setSendStatus] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const loadDevices = useCallback(async () => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const list = await discoverReceivers(4000);
      setDevices(list);
      setSelected((prev) => prev && list.some((d) => d.location === prev.location) ? prev : null);
    } catch (e) {
      setErrorMsg('发现设备失败：' + (e instanceof Error ? e.message : String(e)));
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      loadDevices();
      const t = setInterval(loadDevices, 5000);
      return () => clearInterval(t);
    }, [loadDevices])
  );

  // ─── 发送到选中设备 ──────────────────────────────────────────────
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
      const url = controlUrlFor(selected);
      await postSoap(url, 'SetAVTransportURI', buildSetAVTransportURI(videoUrl.trim(), videoTitle.trim()));
      await postSoap(url, 'Play', buildAction('Play'));
      setSendStatus('已发送');
    } catch (e) {
      setSendStatus(null);
      setErrorMsg('发送失败：' + (e instanceof Error ? e.message : String(e)));
    }
  }, [selected, videoUrl, videoTitle]);

  const handleControl = useCallback(
    async (cmd: 'Play' | 'Pause' | 'Stop') => {
      if (!selected) return;
      try {
        await postSoap(controlUrlFor(selected), cmd, buildAction(cmd));
      } catch (e) {
        setErrorMsg('控制失败：' + (e instanceof Error ? e.message : String(e)));
      }
    },
    [selected]
  );

  // ─── 本机演示（直接在当前设备播放）────────────────────────────────
  const handleLocalPlay = useCallback(() => {
    if (!videoUrl.trim()) {
      Alert.alert('请输入视频地址');
      return;
    }
    setSourceUrl(videoUrl.trim(), videoTitle.trim());
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
              key={d.location}
              onPress={() => setSelected(d)}
              className={
                'flex-row items-center justify-between rounded-lg border p-4 ' +
                (selected?.location === d.location ? 'border-cyan-400 bg-cyan-400/10' : 'border-border')
              }
            >
              <View className="flex-1 mr-3">
                <Text className="text-foreground font-semibold">{d.friendlyName || '投屏设备'}</Text>
                <Text className="text-muted-foreground text-xs mt-1">{hostOf(d.location)}</Text>
              </View>
              {selected?.location === d.location && (
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

        {selected && (
          <View className="flex-row gap-3 justify-center">
            <TouchableOpacity onPress={() => handleControl('Play')} className="px-5 py-2 rounded-lg border border-border">
              <Text className="text-foreground">继续</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => handleControl('Pause')} className="px-5 py-2 rounded-lg border border-border">
              <Text className="text-foreground">暂停</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => handleControl('Stop')} className="px-5 py-2 rounded-lg border border-border">
              <Text className="text-foreground">停止</Text>
            </TouchableOpacity>
          </View>
        )}

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
