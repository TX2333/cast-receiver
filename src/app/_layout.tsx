import * as Sentry from '@sentry/react-native';
import { Stack } from 'expo-router';
import { PortalHost } from '@rn-primitives/portal';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { PlayerProvider } from '@/lib/playerContext';
import { LogBox } from 'react-native';
import "../global.css";

// 全局未捕获 Promise rejection 兜底，防止闪退
if (typeof global !== 'undefined') {
  const _onUnhandled = (global as any).onunhandledrejection;
  (global as any).onunhandledrejection = (event: any) => {
    console.warn('[unhandledRejection]', event?.reason ?? event);
    if (_onUnhandled) _onUnhandled(event);
  };
  // React Native 特定的错误处理
  const ErrorUtils = (global as any).ErrorUtils;
  if (ErrorUtils) {
    const _globalHandler = ErrorUtils.getGlobalHandler?.();
    ErrorUtils.setGlobalHandler((error: any, isFatal?: boolean) => {
      console.warn('[globalError]', isFatal ? 'FATAL' : 'non-fatal', error);
      if (_globalHandler) _globalHandler(error, isFatal);
    });
  }
}

LogBox.ignoreLogs(['Require cycle', 'VirtualizedLists']);

const SENTRY_DSN = process.env.EXPO_PUBLIC_SENTRY_DSN;
if (SENTRY_DSN) {
  Sentry.init({ dsn: SENTRY_DSN });
}

export default Sentry.wrap(function RootLayout() {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <PlayerProvider>
        <Stack screenOptions={{ headerShown: false }} />
        <PortalHost />
      </PlayerProvider>
    </GestureHandlerRootView>
  );
});


