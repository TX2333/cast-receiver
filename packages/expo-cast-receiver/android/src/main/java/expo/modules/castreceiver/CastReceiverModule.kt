package expo.modules.castreceiver

import android.content.Context
import android.net.wifi.WifiManager
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.concurrent.thread

class CastReceiverModule : Module() {
    // ---- configuration (set on start) ----
    private var friendlyName = "Cast Receiver"
    private var localIp = "127.0.0.1"
    private var controlPort = 49152
    private var udn = "uuid:00000000-0000-0000-0000-000000000000"

    // ---- runtime ----
    @Volatile private var running = false
    private var ssdpSocket: MulticastSocket? = null
    private var httpServer: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var notifyTimer: Timer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ---- shared playback state (updated from JS) ----
    private val stateLock = Any()
    private var curPositionMs = 0L
    private var curDurationMs = 0L
    private var curPlaying = false
    private var curVolume = 100.0
    private var curUri: String? = null
    private var curMeta: String? = null

    override fun definition() = ModuleDefinition {
        Name("CastReceiver")

        Events(
            "onSetAVTransportURI",
            "onPlay",
            "onPause",
            "onStop",
            "onSeek",
            "onSetVolume",
            "onError"
        )

        Function("start") { config: Map<String, Any?> ->
            val port = (config["port"] as? Number)?.toInt() ?: 49152
            val ip = (config["ip"] as? String) ?: "127.0.0.1"
            val name = (config["friendlyName"] as? String) ?: "Cast Receiver"
            val uuid = (config["uuid"] as? String) ?: ("uuid:" + randomUuid())
            startInternal(port, ip, name, uuid)
        }

        Function("stop") {
            stopInternal()
        }

        Function("updateState") { state: Map<String, Any?> ->
            synchronized(stateLock) {
                (state["positionMs"] as? Number)?.let { curPositionMs = it.toLong() }
                (state["durationMs"] as? Number)?.let { curDurationMs = it.toLong() }
                curPlaying = (state["isPlaying"] as? Boolean) ?: curPlaying
                (state["volume"] as? Number)?.let { curVolume = it.toDouble() }
            }
        }

        AsyncFunction("discover") { timeoutMs: Double ->
            discoverInternal(timeoutMs.toLong().coerceAtLeast(1000))
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------
    private fun startInternal(port: Int, ip: String, name: String, uuid: String) {
        if (running) stopInternal()
        // 优先使用 JS 传入的 IP，但 Android 上 expo-network 常返回 0.0.0.0 / 127.0.0.1 / IPv6，
        // 这类地址会让 LOCATION 不可达。故无效时改由原生枚举网卡挑选可用 IPv4。
        localIp = resolveLocalIp(ip)
        friendlyName = name
        udn = if (uuid.startsWith("uuid:")) uuid else "uuid:$uuid"
        running = true

        // Best-effort multicast lock (helps on Wi-Fi networks).
        try {
            val ctx: Context? = appContext.reactContext?.applicationContext
                ?: appContext.currentActivity?.applicationContext
            if (ctx != null) {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lock = wm?.createMulticastLock("CastReceiverDlna")
                lock?.setReferenceCounted(false)
                lock?.acquire()
                multicastLock = lock
            }
        } catch (e: Exception) { /* ignore */ }

        thread { runSsdp() }
        thread { runHttp(port) }

        notifyTimer = Timer()
        notifyTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try { sendNotify() } catch (e: Exception) { /* ignore */ }
            }
        }, 1500, 30000)
    }

    private fun stopInternal() {
        running = false
        try { ssdpSocket?.close() } catch (e: Exception) { /* ignore */ }
        try { httpServer?.close() } catch (e: Exception) { /* ignore */ }
        ssdpSocket = null
        httpServer = null
        notifyTimer?.cancel()
        notifyTimer = null
        try { multicastLock?.release() } catch (e: Exception) { /* ignore */ }
        multicastLock = null
    }

    // ------------------------------------------------------------------
    // SSDP responder (UDP multicast :1900)
    // ------------------------------------------------------------------
    private fun runSsdp() {
        try {
            // 必须绑定 1900 端口才能收到发往 239.255.255.250:1900 的 M-SEARCH，
            // 这是设备被发现的前提；个别系统禁止普通应用绑定特权端口时才退化为临时端口（仅能靠 NOTIFY 被发现）。
            val socket = createSsdpSocket()
            socket.reuseAddress = true
            try {
                socket.joinGroup(InetAddress.getByName("239.255.255.250"))
            } catch (e: Exception) { /* ignore */ }
            ssdpSocket = socket

            // 加入组播后立即多播几次 NOTIFY（alive），缩短控制点发现本设备的时间
            repeat(3) {
                try { sendNotify() } catch (e: Exception) { /* ignore */ }
                try { Thread.sleep(300) } catch (e: Exception) { /* ignore */ }
            }

            val buf = ByteArray(4096)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (!running) break
                    val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                        val st = findHeader(msg, "ST")
                            ?: "urn:schemas-upnp-org:device:MediaRenderer:1"
                        val response = buildSsdpResponse(st).toByteArray(StandardCharsets.UTF_8)
                        val out = DatagramPacket(
                            response,
                            response.size,
                            packet.address,
                            packet.port
                        )
                        try { socket.send(out) } catch (e: Exception) { /* ignore */ }
                    }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    // transient read error, keep looping
                }
            }
        } catch (e: Exception) {
            sendError("SSDP start failed: " + (e.message ?: ""))
        }
    }

    private fun createSsdpSocket(): MulticastSocket {
        return try {
            val s = MulticastSocket(1900)
            s.reuseAddress = true
            s
        } catch (e: Exception) {
            // 退化：无法绑定 1900 时仍可用临时端口发送 NOTIFY 公告
            val s = MulticastSocket(0)
            s.reuseAddress = true
            s
        }
    }

    private fun buildSsdpResponse(st: String): String {
        val location = "http://$localIp:$controlPort/description.xml"
        val usn = if (st.equals("ssdp:all", ignoreCase = true)) udn else "$udn::$st"
        return "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            "LOCATION: $location\r\n" +
            "SERVER: Linux/3.14 UPnP/1.0 miaodaCast/1.0\r\n" +
            "ST: $st\r\n" +
            "USN: $usn\r\n" +
            "CONTENT-LENGTH: 0\r\n\r\n"
    }

    // 选择 LOCATION 使用的本机 IPv4：优先 JS 传入的可用地址，否则枚举网卡挑选
    private fun resolveLocalIp(requested: String): String {
        if (isUsableLanIp(requested)) return requested
        val native = pickLocalIp()
        if (isUsableLanIp(native)) return native
        return if (isUsableLanIp(requested)) requested else "127.0.0.1"
    }

    private fun isUsableLanIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        if (ip == "0.0.0.0" || ip.startsWith("127.")) return false
        if (ip.contains(':')) return false // 排除 IPv6
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } ?: false }
    }

    private fun pickLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val name = nif.name ?: ""
                // 跳过 VPN / 虚拟网卡
                if (name.startsWith("tun") || name.startsWith("dummy") || name.startsWith("virbr")) continue
                val adds = nif.inetAddresses
                while (adds.hasMoreElements()) {
                    val addr = adds.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val h = addr.hostAddress
                        if (h != null && h != "0.0.0.0") return h
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return "127.0.0.1"
    }

    private fun sendNotify() {
        val socket = ssdpSocket ?: return
        val location = "http://$localIp:$controlPort/description.xml"
        val msg = "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: $location\r\n" +
            "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "NTS: ssdp:alive\r\n" +
            "SERVER: Linux/3.14 UPnP/1.0 miaodaCast/1.0\r\n" +
            "USN: $udn::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val data = msg.toByteArray(StandardCharsets.UTF_8)
        try {
            val p = DatagramPacket(
                data,
                data.size,
                InetAddress.getByName("239.255.255.250"),
                1900
            )
            socket.send(p)
        } catch (e: Exception) { /* ignore */ }
    }

    // ------------------------------------------------------------------
    // HTTP control server (description + SOAP)
    // ------------------------------------------------------------------
    private fun runHttp(desiredPort: Int) {
        var boundPort = desiredPort
        var server: ServerSocket? = null
        var tries = 0
        while (tries < 30) {
            try {
                server = ServerSocket(boundPort)
                break
            } catch (e: Exception) {
                boundPort++
                tries++
            }
        }
        if (server == null) {
            sendError("HTTP control server failed to bind port $desiredPort")
            return
        }
        controlPort = boundPort
        httpServer = server
        while (running) {
            try {
                val client = server.accept()
                executor.execute { handleClient(client) }
            } catch (e: SocketException) {
                break
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 8000
            val raw = readRequest(socket)
            val sep = raw.indexOf("\r\n\r\n")
            val headerPart = if (sep >= 0) raw.substring(0, sep) else raw
            val body = if (sep >= 0) raw.substring(sep + 4) else ""
            val firstLine = headerPart.split("\r\n").firstOrNull() ?: ""
            val parts = firstLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: ""
            when {
                method.equals("GET", ignoreCase = true) -> when {
                    path.endsWith("/description.xml") -> sendXml(socket, buildDescriptionXml())
                    path.endsWith("/dlna/AVTransport/desc.xml") -> sendXml(socket, AVTRANSPORT_SCPD)
                    path.endsWith("/dlna/RenderingControl/desc.xml") -> sendXml(socket, RENDERINGCONTROL_SCPD)
                    else -> sendStatus(socket, 404, "Not Found")
                }
                method.equals("POST", ignoreCase = true) -> handleSoap(socket, path, headerPart, body)
                else -> sendStatus(socket, 405, "Method Not Allowed")
            }
        } catch (e: Exception) {
            // ignore client errors
        } finally {
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun readRequest(socket: Socket): String {
        val input: InputStream = socket.getInputStream()
        val buffer = ByteArray(64 * 1024)
        val baos = ByteArrayOutputStream()
        val start = System.currentTimeMillis()
        var total = 0
        while (System.currentTimeMillis() - start < 2000 && total < buffer.size) {
            val available = input.available()
            if (available > 0) {
                val n = input.read(buffer, 0, minOf(available, buffer.size - total))
                if (n <= 0) break
                baos.write(buffer, 0, n)
                total += n
                if (total > 0 && input.available() == 0) break
            } else {
                if (total > 0) break
                Thread.sleep(15)
            }
        }
        return baos.toString(StandardCharsets.UTF_8)
    }

    private fun handleSoap(socket: Socket, path: String, headers: String, body: String) {
        val isRendering = path.contains("RenderingControl", ignoreCase = true)
        val serviceUrn = if (isRendering)
            "urn:schemas-upnp-org:service:RenderingControl:1"
        else
            "urn:schemas-upnp-org:service:AVTransport:1"

        val soapAction = findHeader(headers, "SOAPACTION")
        val action = if (soapAction != null) {
            val idx = soapAction.indexOf('#')
            if (idx >= 0) soapAction.substring(idx + 1) else soapAction
        } else {
            extractActionFromBody(body)
        }
        val response = dispatch(action, serviceUrn, body)
        sendSoap(socket, response)
    }

    private fun dispatch(action: String?, serviceUrn: String, body: String): String {
        val a = (action ?: "").lowercase()
        return when (a) {
            "setavtransporturi" -> {
                val uri = tag(body, "CurrentURI")
                val meta = tag(body, "CurrentURIMetaData")
                val title = extractTitle(meta) ?: uri
                synchronized(stateLock) { curUri = uri; curMeta = meta }
                sendEvent(
                    "onSetAVTransportURI",
                    mapOf(
                        "uri" to (uri ?: ""),
                        "title" to (title ?: ""),
                        "meta" to (meta ?: "")
                    )
                )
                soapEnvelope(serviceUrn, "SetAVTransportURI")
            }
            "play" -> {
                sendEvent("onPlay", mapOf<String, Any?>())
                soapEnvelope(serviceUrn, "Play")
            }
            "pause" -> {
                sendEvent("onPause", mapOf<String, Any?>())
                soapEnvelope(serviceUrn, "Pause")
            }
            "stop" -> {
                sendEvent("onStop", mapOf<String, Any?>())
                soapEnvelope(serviceUrn, "Stop")
            }
            "seek" -> {
                val target = tag(body, "Target") ?: "00:00:00"
                val sec = parseTime(target)
                sendEvent("onSeek", mapOf("position" to sec))
                soapEnvelope(serviceUrn, "Seek")
            }
            "getpositioninfo" -> {
                val (rel, dur) = currentTimes()
                soapEnvelope(
                    serviceUrn, "GetPositionInfo",
                    mapOf(
                        "Track" to "0",
                        "TrackDuration" to dur,
                        "TrackMetaData" to curMetaSafe(),
                        "TrackURI" to currentUriSafe(),
                        "RelTime" to rel,
                        "AbsTime" to rel,
                        "RelCount" to "0",
                        "AbsCount" to "0"
                    )
                )
            }
            "gettransportinfo" -> {
                val state = synchronized(stateLock) { if (curPlaying) "PLAYING" else "STOPPED" }
                soapEnvelope(
                    serviceUrn, "GetTransportInfo",
                    mapOf(
                        "CurrentTransportState" to state,
                        "CurrentTransportStatus" to "OK",
                        "CurrentSpeed" to "1"
                    )
                )
            }
            "getmediainfo" -> {
                soapEnvelope(
                    serviceUrn, "GetMediaInfo",
                    mapOf(
                        "NrTracks" to "1",
                        "MediaDuration" to currentDuration(),
                        "CurrentURI" to currentUriSafe(),
                        "CurrentURIMetaData" to curMetaSafe(),
                        "NextURI" to "",
                        "NextURIMetaData" to ""
                    )
                )
            }
            "getdevicecapabilities" ->
                soapEnvelope(serviceUrn, "GetDeviceCapabilities", mapOf("PlayMedia" to "NETWORK,LOCAL"))
            "gettransportsettings" ->
                soapEnvelope(serviceUrn, "GetTransportSettings", mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"))
            "getcurrenttransportactions" ->
                soapEnvelope(serviceUrn, "GetCurrentTransportActions", mapOf("Actions" to "Play,Pause,Stop,Seek"))
            "setvolume" -> {
                val v = tag(body, "DesiredVolume")?.toDoubleOrNull() ?: 100.0
                synchronized(stateLock) { curVolume = v }
                sendEvent("onSetVolume", mapOf("volume" to v))
                soapEnvelope(serviceUrn, "SetVolume")
            }
            "getvolume" -> {
                val v = synchronized(stateLock) { curVolume }
                soapEnvelope(serviceUrn, "GetVolume", mapOf("CurrentVolume" to v.toInt().toString()))
            }
            "setmute" -> soapEnvelope(serviceUrn, "SetMute")
            "getmute" -> soapEnvelope(serviceUrn, "GetMute", mapOf("CurrentMute" to "0"))
            else -> soapEnvelope(serviceUrn, a)
        }
    }

    // ------------------------------------------------------------------
    // SSDP discovery client (M-SEARCH)
    // ------------------------------------------------------------------
    private fun discoverInternal(timeoutMs: Long): List<Map<String, String>> {
        val found = mutableListOf<Map<String, String>>()
        val seen = mutableSetOf<String>()
        val socket = DatagramSocket(0)
        socket.soTimeout = 1000
        val msearch = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USER-AGENT: miaodaCast/1.0 UPnP/1.0\r\n\r\n"
        val data = msearch.toByteArray(StandardCharsets.UTF_8)
        val addr = InetAddress.getByName("239.255.255.250")
        repeat(2) {
            try {
                socket.send(DatagramPacket(data, data.size, addr, 1900))
            } catch (e: Exception) { /* ignore */ }
        }
        val end = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(4096)
        while (System.currentTimeMillis() < end) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                val location = findHeader(msg, "LOCATION")
                val usn = findHeader(msg, "USN") ?: ""
                if (location != null && !seen.contains(location)) {
                    seen.add(location)
                    val name = fetchFriendlyName(location) ?: "投屏设备"
                    found.add(
                        mapOf(
                            "location" to location,
                            "friendlyName" to name,
                            "udn" to usn
                        )
                    )
                }
            } catch (e: SocketTimeoutException) {
                // keep listening until timeout
            } catch (e: Exception) {
                break
            }
        }
        // 把本机正在运行的接收端也加入结果：绕过 Android 组播回环限制，
        // 使“发送到设备”在同设备上也能发现自身（便于自投/测试）。
        if (running && isUsableLanIp(localIp)) {
            val localLoc = "http://$localIp:$controlPort/description.xml"
            if (!seen.contains(localLoc)) {
                seen.add(localLoc)
                found.add(
                    mapOf(
                        "location" to localLoc,
                        "friendlyName" to friendlyName,
                        "udn" to udn
                    )
                )
            }
        }

        socket.close()
        return found
    }

    private fun fetchFriendlyName(location: String): String? {
        return try {
            val conn = URL(location).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val m = Pattern.compile(
                "<friendlyName>(.*?)</friendlyName>",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            ).matcher(text)
            if (m.find()) m.group(1)?.trim() else null
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------
    private fun sendXml(socket: Socket, xml: String) {
        val body = xml.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
            "CONTENT-LENGTH: ${body.size}\r\n" +
            "CONNECTION: close\r\n" +
            "SERVER: miaodaCast/1.0 UPnP/1.0\r\n" +
            "EXT:\r\n\r\n"
        val os = socket.getOutputStream()
        os.write(header.toByteArray(StandardCharsets.UTF_8))
        os.write(body)
        os.flush()
    }

    private fun sendSoap(socket: Socket, body: String) {
        val b = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
            "CONTENT-LENGTH: ${b.size}\r\n" +
            "CONNECTION: close\r\n" +
            "SERVER: miaodaCast/1.0 UPnP/1.0\r\n\r\n"
        val os = socket.getOutputStream()
        os.write(header.toByteArray(StandardCharsets.UTF_8))
        os.write(b)
        os.flush()
    }

    private fun sendStatus(socket: Socket, code: Int, msg: String) {
        val b = msg.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $code $msg\r\nCONTENT-LENGTH: ${b.size}\r\nCONNECTION: close\r\n\r\n"
        val os = socket.getOutputStream()
        os.write(header.toByteArray(StandardCharsets.UTF_8))
        os.write(b)
        os.flush()
    }

    // ------------------------------------------------------------------
    // XML builders / parsers
    // ------------------------------------------------------------------
    private fun buildDescriptionXml(): String {
        return "<?xml version=\"1.0\"?>\r\n" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\r\n" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>\r\n" +
            "<device>\r\n" +
            "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\r\n" +
            "<friendlyName>${escapeXml(friendlyName)}</friendlyName>\r\n" +
            "<manufacturer>miaoda</manufacturer>\r\n" +
            "<manufacturerURL>https://miaoda.com</manufacturerURL>\r\n" +
            "<modelDescription>Cast Receiver</modelDescription>\r\n" +
            "<modelName>CastReceiver</modelName>\r\n" +
            "<modelNumber>1.0</modelNumber>\r\n" +
            "<UDN>$udn</UDN>\r\n" +
            "<serviceList>\r\n" +
            "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>" +
            "<controlURL>/upnp/control/AVTransport</controlURL>" +
            "<eventSubURL>/upnp/event/AVTransport</eventSubURL>" +
            "<SCPDURL>/dlna/AVTransport/desc.xml</SCPDURL></service>\r\n" +
            "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>" +
            "<controlURL>/upnp/control/RenderingControl</controlURL>" +
            "<eventSubURL>/upnp/event/RenderingControl</eventSubURL>" +
            "<SCPDURL>/dlna/RenderingControl/desc.xml</SCPDURL></service>\r\n" +
            "</serviceList>\r\n" +
            "</device>\r\n</root>\r\n"
    }

    private fun soapEnvelope(serviceUrn: String, action: String, args: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
        sb.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n")
        sb.append("<s:Body>\r\n")
        sb.append("<u:${action}Response xmlns:u=\"$serviceUrn\">\r\n")
        for ((k, v) in args) {
            sb.append("<$k>$v</$k>\r\n")
        }
        sb.append("</u:${action}Response>\r\n")
        sb.append("</s:Body>\r\n")
        sb.append("</s:Envelope>\r\n")
        return sb.toString()
    }

    private fun tag(body: String, name: String): String? {
        val pattern = Pattern.compile("<$name>(.*?)</$name>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val m = pattern.matcher(body)
        return if (m.find()) m.group(1) else null
    }

    private fun extractActionFromBody(body: String): String {
        val pattern = Pattern.compile(
            "<([A-Za-z]+:)?(SetAVTransportURI|Play|Pause|Stop|Seek|GetPositionInfo|GetTransportInfo|GetMediaInfo|GetDeviceCapabilities|GetTransportSettings|GetCurrentTransportActions|SetVolume|GetVolume|SetMute|GetMute)>",
            Pattern.CASE_INSENSITIVE
        )
        val m = pattern.matcher(body)
        return if (m.find()) m.group(2) ?: "" else ""
    }

    private fun findHeader(headers: String, name: String): String? {
        for (line in headers.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim().replace("\"", "")
            }
        }
        return null
    }

    private fun extractTitle(meta: String?): String? {
        if (meta.isNullOrBlank()) return null
        val un = unescapeXml(meta)
        val t = tag(un, "title") ?: tag(un, "dc:title")
        return t?.trim()
    }

    private fun unescapeXml(s: String): String {
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun currentUriSafe(): String = synchronized(stateLock) { curUri ?: "" }
    private fun curMetaSafe(): String = synchronized(stateLock) { curMeta ?: "" }
    private fun currentDuration(): String = synchronized(stateLock) { formatTimeSeconds(curDurationMs / 1000) }

    private fun currentTimes(): Pair<String, String> = synchronized(stateLock) {
        Pair(formatTimeSeconds(curPositionMs / 1000), formatTimeSeconds(curDurationMs / 1000))
    }

    private fun formatTimeSeconds(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return String.format("%02d:%02d:%02d", h, m, ss)
    }

    private fun parseTime(s: String): Long {
        val parts = s.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                1 -> parts[0].toLong()
                else -> 0
            }
        } catch (e: Exception) { 0 }
    }

    private fun randomUuid(): String {
        val r = Random()
        val h = { n: Int -> (0 until n).joinToString("") { r.nextInt(16).toString(16) } }
        return "${h(8)}-${h(4)}-${h(4)}-${h(4)}-${h(12)}"
    }

    private fun sendError(message: String) {
        sendEvent("onError", mapOf("message" to message))
    }

    companion object {
        private const val AVTRANSPORT_SCPD = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>
<action><name>SetAVTransportURI</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetMediaInfo</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
<argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>
<argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
<argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
<argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
<argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetTransportInfo</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
<argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
<argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetPositionInfo</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
<argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
<argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
<argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
<argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
<argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
<argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
<argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
</argumentList></action>
<action><name>Play</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
</argumentList></action>
<action><name>Pause</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
</argumentList></action>
<action><name>Stop</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
</argumentList></action>
<action><name>Seek</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
<argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetDeviceCapabilities</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>PlayMedia</name><direction>out</direction><relatedStateVariable>PossiblePlaybackStorageMedia</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetTransportSettings</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>PlayMode</name><direction>out</direction><relatedStateVariable>CurrentPlayMode</relatedStateVariable></argument>
<argument><name>RecQualityMode</name><direction>out</direction><relatedStateVariable>CurrentRecordQualityMode</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetCurrentTransportActions</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Actions</name><direction>out</direction><relatedStateVariable>CurrentTransportActions</relatedStateVariable></argument>
</argumentList></action>
</actionList>
<serviceStateTable>
<stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
<stateVariable sendEvents="no"><name>CurrentPlayMode</name><dataType>string</dataType></stateVariable>
</serviceStateTable>
</scpd>"""

        private const val RENDERINGCONTROL_SCPD = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>
<action><name>SetVolume</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetVolume</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
</argumentList></action>
<action><name>SetMute</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
<argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
</argumentList></action>
<action><name>GetMute</name><argumentList>
<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
<argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
</argumentList></action>
</actionList>
<serviceStateTable>
<stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType></stateVariable>
<stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
<stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>
</serviceStateTable>
</scpd>"""
    }
}
