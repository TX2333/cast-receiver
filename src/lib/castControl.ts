import type { CastPayload } from './castServer';

function soapEnvelope(action: string, serviceType: string, body: string) {
  return `<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:${action} xmlns:u="${serviceType}">${body}</u:${action}></s:Body>
</s:Envelope>`;
}

async function soapRequest(baseUrl: string, path: string, action: string, serviceType: string, body: string) {
  const xml = soapEnvelope(action, serviceType, body);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 4000);
  try {
    const res = await fetch(`${baseUrl}${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'text/xml; charset="utf-8"',
        SOAPAction: `"${serviceType}#${action}"`,
      },
      body: xml,
      signal: controller.signal,
    });
    return res.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

export async function sendControlCommand(baseUrl: string, payload: CastPayload): Promise<boolean> {
  // 1. SetAVTransportURI
  const meta = `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>${payload.title || '视频'}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/*:">${payload.url}</res></item></DIDL-Lite>`;
  const metaEscaped = meta.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

  const ok1 = await soapRequest(
    baseUrl,
    '/upnp/control/AVTransport',
    'SetAVTransportURI',
    'urn:schemas-upnp-org:service:AVTransport:1',
    `<InstanceID>0</InstanceID><CurrentURI>${payload.url}</CurrentURI><CurrentURIMetaData>${metaEscaped}</CurrentURIMetaData>`
  );
  if (!ok1) return false;

  // 2. Play
  return soapRequest(
    baseUrl,
    '/upnp/control/AVTransport',
    'Play',
    'urn:schemas-upnp-org:service:AVTransport:1',
    `<InstanceID>0</InstanceID><Speed>1</Speed>`
  );
}

export async function sendPause(baseUrl: string) {
  return soapRequest(baseUrl, '/upnp/control/AVTransport', 'Pause', 'urn:schemas-upnp-org:service:AVTransport:1', `<InstanceID>0</InstanceID>`);
}

export async function sendStop(baseUrl: string) {
  return soapRequest(baseUrl, '/upnp/control/AVTransport', 'Stop', 'urn:schemas-upnp-org:service:AVTransport:1', `<InstanceID>0</InstanceID>`);
}

export async function sendSeek(baseUrl: string, target: string) {
  return soapRequest(baseUrl, '/upnp/control/AVTransport', 'Seek', 'urn:schemas-upnp-org:service:AVTransport:1',
    `<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${target}</Target>`);
}

export async function sendSetVolume(baseUrl: string, volPct: number) {
  return soapRequest(baseUrl, '/upnp/control/RenderingControl', 'SetVolume', 'urn:schemas-upnp-org:service:RenderingControl:1',
    `<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${Math.round(volPct * 100)}</DesiredVolume>`);
}
