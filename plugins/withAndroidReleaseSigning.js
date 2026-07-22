const { withAppBuildGradle } = require('@expo/config-plugins');

/**
 * 确保 release 构建使用 debug keystore 签名，使 `assembleRelease` 产出可安装的 APK，
 * 无需自定义 keystore。若已配置正式签名（如 Codemagic 的 android_signing），则保持不变。
 */
module.exports = function withAndroidReleaseSigning(config) {
  return withAppBuildGradle(config, (cfg) => {
    const contents = cfg.modResults.contents;
    const marker = 'release {';
    const releaseIdx = contents.indexOf(marker);

    if (releaseIdx === -1) {
      // 找不到 release 块，兜底追加
      cfg.modResults.contents =
        contents + '\nandroid { buildTypes { release { signingConfig signingConfigs.debug } } }\n';
      return cfg;
    }

    // 仅截取 release 块内容（到第一个顶格的 '}' 为止），判断其中是否已声明 signingConfig
    const after = contents.slice(releaseIdx);
    const blockEnd = after.search(/\n\}/);
    const releaseBlock = blockEnd === -1 ? after : after.slice(0, blockEnd);
    if (releaseBlock.includes('signingConfig')) {
      return cfg; // 已签名（debug keystore 或正式 keystore），无需处理
    }

    const injectPos = releaseIdx + marker.length;
    cfg.modResults.contents =
      contents.slice(0, injectPos) +
      '\n            signingConfig signingConfigs.debug' +
      contents.slice(injectPos);
    return cfg;
  });
};
