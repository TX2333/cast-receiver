import { Paths, File } from 'expo-file-system';
import { randomUUID } from 'expo-crypto';

let cachedId: string | null = null;

/**
 * Returns a stable per-install device identifier.
 * The ID is generated once and persisted to the app's document directory,
 * so it stays the same across app restarts but resets on reinstall.
 */
export async function getDeviceId(): Promise<string> {
  if (cachedId) return cachedId;

  try {
    const idFile = new File(Paths.document, '.device_id');
    if (idFile.exists) {
      const existing = await idFile.text();
      if (existing && existing.trim()) {
        cachedId = existing.trim();
        return cachedId;
      }
    }

    const id = randomUUID();
    cachedId = id;
    try {
      idFile.write(id);
    } catch {
      // ignore persistence errors; the in-memory id is still usable for this session
    }
    return id;
  } catch {
    // If the file API fails entirely, fall back to an in-memory UUID
    if (!cachedId) {
      cachedId = randomUUID();
    }
    return cachedId;
  }
}
