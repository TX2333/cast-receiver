const { withAndroidManifest } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Expo Config Plugin: 添加 Android 网络安全配置
 * 允许局域网明文 HTTP 流量（投屏服务器需要）
 */
function withAndroidNetworkSecurity(config) {
  return withAndroidManifest(config, async (config) => {
    const androidManifest = config.modResults;
    const { manifest } = androidManifest;

    // 确保 application 节点存在
    if (!manifest.$) manifest.$ = {};
    if (!manifest.application) manifest.application = [{}];
    if (!manifest.application[0].$) manifest.application[0].$ = {};

    // 设置 networkSecurityConfig 和允许明文流量
    manifest.application[0].$['android:networkSecurityConfig'] = '@xml/network_security_config';
    manifest.application[0].$['android:usesCleartextTraffic'] = 'true';

    // 复制 network_security_config.xml 到 res/xml/
    const projectRoot = config.modRequest.projectRoot;
    const xmlSource = path.join(projectRoot, 'plugins', 'network_security_config.xml');
    const resDir = path.join(projectRoot, 'android', 'app', 'src', 'main', 'res', 'xml');

    // 确保目录存在
    fs.mkdirSync(resDir, { recursive: true });
    fs.copyFileSync(xmlSource, path.join(resDir, 'network_security_config.xml'));

    return config;
  });
}

module.exports = withAndroidNetworkSecurity;
