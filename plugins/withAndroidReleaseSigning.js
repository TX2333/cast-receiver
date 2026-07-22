const { withAppBuildGradle, AndroidConfig } = require('@expo/config-plugins');

/**
 * Ensures the release build type is signed with the debug keystore so that
 * `assembleRelease` produces an installable (signed) APK without requiring a
 * custom keystore. When a real keystore is configured (e.g. via Codemagic
 * `android_signing`), remove this plugin or adjust the signingConfig.
 */
module.exports = function withAndroidReleaseSigning(config) {
  return withAppBuildGradle(config, (cfg) => {
    const contents = cfg.modResults.contents;
    if (contents.includes('signingConfig signingConfigs.debug')) {
      // Already signed with the debug keystore.
      return cfg;
    }
    const marker = 'buildTypes {';
    const idx = contents.indexOf(marker);
    if (idx === -1) {
      cfg.modResults.contents =
        contents + '\nandroid { buildTypes { release { signingConfig signingConfigs.debug } } }\n';
    } else {
      const insertPos = idx + marker.length;
      const injection = '\n        release { signingConfig signingConfigs.debug }';
      cfg.modResults.contents =
        contents.slice(0, insertPos) + injection + contents.slice(insertPos);
    }
    return cfg;
  });
};
