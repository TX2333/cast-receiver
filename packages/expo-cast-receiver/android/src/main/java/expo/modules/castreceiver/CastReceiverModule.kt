package expo.modules.castreceiver

import android.content.Context
import android.net.wifi.WifiManager
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.concurrent.thread

class CastReceiverModule : Module() {
    private var friendlyName = "Cast Receiver"
    private var localIp = "127.0.0.1"
    private var controlPort = 49152
    private var udn = "uuid:00000000-0000-0000-0000-000000000000"

    @Volatile private var running = false
    private var ssdpSocket: MulticastSocket? = null
    private var httpServer: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var notifyTimer: Timer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val httpReadyLatch = CountDownLatch(1)

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
            "onSetAVTransportURI", "onPlay", "onPause", "onStop",
            "onSeek", "onSetVolume", "onError"
        )
        Function("start") { config: Map<String, Any?> ->
            val port = (config["port"] as? Number)?.toInt() ?: 49152
            val ip = (config["ip"] as? String) ?: "127.0.0.1"
            val name = (config["friendlyName"] as? String) ?: "Cast Receiver"
            val uuid = (config["uuid"] as? String) ?: ("uuid:" + randomUuid())
            startInternal(port, ip, name, uuid)
        }
        Function("stop") { stopInternal() }
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

    private fun startInternal(port: Int, ip: String, name: String, uuid: String) {
        if (running) stopInternal()
        localIp = resolveLocalIp(ip)
        friendlyName = name
        udn = if (uuid.startsWith("uuid:")) uuid else "uuid:$uuid"
        controlPort = port
        running = true

        try {
            val ctx = appContext.reactContext?.applicationContext
                ?: appContext.currentActivity?.applicationContext
            if (ctx != null) {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lock = wm?.createMulticastLock("CastReceiverDlna")
                lock?.setReferenceCounted(false)
                lock?.acquire()
                multicastLock = lock
            }
        } catch (e: Exception) { /* ignore */ }

        thread { runHttp(port) }
        try { httpReadyLatch.await(3, TimeUnit.SECONDS) } catch (e: Exception) { /* ignore */ }
        thread { runSsdp() }

        notifyTimer = Timer()
        notifyTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try { sendAllNotifies() } catch (e: Exception) { /* ignore */ }
            }
        }, 500, 15000)
    }

    private fun stopInternal() {
        running = false
        try { sendByebye() } catch (e: Exception) { /* ignore */ }
        try { ssdpSocket?.close() } catch (e: Exception) { /* ignore */ }
        try { httpServer?.close() } catch (e: Exception) { /* ignore */ }
        ssdpSocket = null; httpServer = null
        notifyTimer?.cancel(); notifyTimer = null
        try { multicastLock?.release() } catch (e: Exception) { /* ignore */ }
        multicastLock = null
    }

    // ==================== SSDP ====================
    private fun runSsdp() {
        try {
            val socket = createSsdpSocket()
            socket.reuseAddress = true
            try { socket.joinGroup(InetAddress.getByName("239.255.255.250")) } catch (e: Exception) { /* ignore */ }
            try {
                val nif = NetworkInterface.getByInetAddress(InetAddress.getByName(localIp))
                if (nif != null) socket.networkInterface = nif
            } catch (e: Exception) { /* ignore */ }
            ssdpSocket = socket

            repeat(3) {
                try { sendAllNotifies() } catch (e: Exception) { /* ignore */ }
                try { Thread.sleep(200) } catch (e: Exception) { /* ignore */ }
            }

            val buf = ByteArray(8192)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (!running) break
                    val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                        handleMSearch(socket, packet, msg)
                    }
                } catch (e: SocketException) { break }
                catch (e: Exception) { /* ignore */ }
            }
        } catch (e: Exception) {
            sendError("SSDP start failed: " + (e.message ?: ""))
        }
    }

    private fun handleMSearch(socket: MulticastSocket, packet: DatagramPacket, msg: String) {
        val st = findHeader(msg, "ST") ?: return
        val responses = buildSsdpResponses(st)
        for (resp in responses) {
            try {
                val data = resp.toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(data, data.size, packet.address, packet.port))
                Thread.sleep(20)
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun createSsdpSocket(): MulticastSocket {
        return try {
            val s = MulticastSocket(1900); s.reuseAddress = true; s
        } catch (e: Exception) {
            val s = MulticastSocket(0); s.reuseAddress = true; s
        }
    }

    private val allServiceTypes = listOf(
        "upnp:rootdevice",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1"
    )

    private fun buildSsdpResponses(requestSt: String): List<String> {
        val location = "http://$localIp:$controlPort/description.xml"
        val stsToReply: List<String> = when {
            requestSt.equals("ssdp:all", ignoreCase = true) -> allServiceTypes
            requestSt.equals("upnp:rootdevice", ignoreCase = true) -> listOf("upnp:rootdevice")
            requestSt.startsWith("uuid:", ignoreCase = true) ->
                if (requestSt == udn) listOf(requestSt) else emptyList()
            else ->
                if (allServiceTypes.any { it.equals(requestSt, ignoreCase = true) }) listOf(requestSt)
                else emptyList()
        }
        val dateStr = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).format(java.util.Date())
        return stsToReply.map { st ->
            val usn = when {
                st == "upnp:rootdevice" -> "$udn::upnp:rootdevice"
                st.startsWith("uuid:") -> st
                else -> "$udn::$st"
            }
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "DATE: $dateStr\r\n" +
                "EXT:\r\n" +
                "LOCATION: $location\r\n" +
                "SERVER: Linux/3.14 UPnP/1.0 miaodaCast/1.0\r\n" +
                "ST: $st\r\n" +
                "USN: $usn\r\n" +
                "CONTENT-LENGTH: 0\r\n\r\n"
        }
    }

    private fun resolveLocalIp(requested: String): String {
        if (isUsableLanIp(requested)) return requested
        val native = pickLocalIp()
        if (isUsableLanIp(native)) return native
        return if (isUsableLanIp(requested)) requested else "127.0.0.1"
    }

    private fun isUsableLanIp(ip: String?): Boolean {
        if (ip.isNullOrBlank() || ip == "0.0.0.0" || ip.startsWith("127.") || ip.contains(':')) return false
        val parts = ip.split('.'); if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { v -> v in 0..255 } ?: false }
    }

    private fun pickLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                val name = nif.name ?: ""
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

    private fun sendAllNotifies() {
        val socket = ssdpSocket ?: return
        val location = "http://$localIp:$controlPort/description.xml"
        val mcGroup = InetAddress.getByName("239.255.255.250")
        for (nt in allServiceTypes) {
            val usn = if (nt == "upnp:rootdevice") "$udn::upnp:rootdevice" else "$udn::$nt"
            val msg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $location\r\nNT: $nt\r\nNTS: ssdp:alive\r\nSERVER: Linux/3.14 UPnP/1.0 miaodaCast/1.0\r\nUSN: $usn\r\n\r\n"
            try {
                socket.send(DatagramPacket(msg.toByteArray(StandardCharsets.UTF_8), msg.length, mcGroup, 1900))
                Thread.sleep(15)
            } catch (e: Exception) { /* ignore */ }
        }
        val uuidMsg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $location\r\nNT: $udn\r\nNTS: ssdp:alive\r\nSERVER: Linux/3.14 UPnP/1.0 miaodaCast/1.0\r\nUSN: $udn\r\n\r\n"
        try { socket.send(DatagramPacket(uuidMsg.toByteArray(StandardCharsets.UTF_8), uuidMsg.length, mcGroup, 1900)) } catch (e: Exception) { /* ignore */ }
    }

    private fun sendByebye() {
        val socket = ssdpSocket ?: return
        val mcGroup = InetAddress.getByName("239.255.255.250")
        for (nt in allServiceTypes) {
            val usn = if (nt == "upnp:rootdevice") "$udn::upnp:rootdevice" else "$udn::$nt"
            val msg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: $nt\r\nNTS: ssdp:byebye\r\nUSN: $usn\r\n\r\n"
            try { socket.send(DatagramPacket(msg.toByteArray(StandardCharsets.UTF_8), msg.length, mcGroup, 1900)) } catch (e: Exception) { /* ignore */ }
        }
    }

    // ==================== HTTP Control Server ====================
    private fun runHttp(desiredPort: Int) {
        var boundPort = desiredPort
        var server: ServerSocket? = null
        var tries = 0
        while (tries < 50) {
            try { server = ServerSocket(boundPort); break }
            catch (e: Exception) { boundPort++; tries++ }
        }
        if (server == null) {
            sendError("HTTP control server failed to bind port $desiredPort")
            httpReadyLatch.countDown()
            return
        }
        controlPort = boundPort
        httpServer = server
        httpReadyLatch.countDown()
        while (running) {
            try {
                val client = server.accept()
                executor.execute { handleClient(client) }
            } catch (e: SocketException) { break }
            catch (e: Exception) { /* ignore */ }
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
                    path.contains("/dlna/AVTransport", ignoreCase = true) -> sendXml(socket, AVTRANSPORT_SCPD)
                    path.contains("/dlna/RenderingControl", ignoreCase = true) -> sendXml(socket, RENDERINGCONTROL_SCPD)
                    path.contains("/dlna/ConnectionManager", ignoreCase = true) -> sendXml(socket, CONNECTIONMANAGER_SCPD)
                    else -> sendStatus(socket, 404, "Not Found")
                }
                method.equals("POST", ignoreCase = true) -> handleSoap(socket, path, headerPart, body)
                method.equals("SUBSCRIBE", ignoreCase = true) -> handleSubscribe(socket)
                else -> sendStatus(socket, 405, "Method Not Allowed")
            }
        } catch (e: Exception) { /* ignore */ }
        finally { try { socket.close() } catch (e: Exception) { /* ignore */ } }
    }

    private fun handleSubscribe(socket: Socket) {
        val sid = "uuid:${randomUuid()}"
        val resp = "HTTP/1.1 200 OK\r\nSID: $sid\r\nTIMEOUT: Second-1800\r\nSERVER: miaodaCast/1.0 UPnP/1.0\r\nCONTENT-LENGTH: 0\r\nCONNECTION: close\r\n\r\n"
        try {
            socket.getOutputStream().write(resp.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun readRequest(socket: Socket): String {
        val input: InputStream = socket.getInputStream()
        val buffer = ByteArray(64 * 1024)
        val baos = ByteArrayOutputStream()
        val start = System.currentTimeMillis()
        var total = 0
        while (System.currentTimeMillis() - start < 3000 && total < buffer.size) {
            val available = input.available()
            if (available > 0) {
                val n = input.read(buffer, 0, minOf(available, buffer.size - total))
                if (n <= 0) break
                baos.write(buffer, 0, n); total += n
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
        val isConnMgr = path.contains("ConnectionManager", ignoreCase = true)
        val serviceUrn = when {
            isRendering -> "urn:schemas-upnp-org:service:RenderingControl:1"
            isConnMgr -> "urn:schemas-upnp-org:service:ConnectionManager:1"
            else -> "urn:schemas-upnp-org:service:AVTransport:1"
        }
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
                emit("onSetAVTransportURI", mapOf("uri" to (uri ?: ""), "title" to (title ?: ""), "meta" to (meta ?: "")))
                soapEnvelope(serviceUrn, "SetAVTransportURI")
            }
            "play" -> { emit("onPlay", emptyMap()); soapEnvelope(serviceUrn, "Play") }
            "pause" -> { emit("onPause", emptyMap()); soapEnvelope(serviceUrn, "Pause") }
            "stop" -> { emit("onStop", emptyMap()); soapEnvelope(serviceUrn, "Stop") }
            "seek" -> {
                val target = tag(body, "Target") ?: "00:00:00"
                val sec = parseTime(target)
                emit("onSeek", mapOf("position" to sec))
                soapEnvelope(serviceUrn, "Seek")
            }
            "getpositioninfo" -> {
                val (rel, dur) = currentTimes()
                soapEnvelope(serviceUrn, "GetPositionInfo", mapOf(
                    "Track" to "0", "TrackDuration" to dur, "TrackMetaData" to curMetaSafe(),
                    "TrackURI" to currentUriSafe(), "RelTime" to rel, "AbsTime" to rel,
                    "RelCount" to "0", "AbsCount" to "0"
                ))
            }
            "gettransportinfo" -> {
                val state = synchronized(stateLock) { if (curPlaying) "PLAYING" else "STOPPED" }
                soapEnvelope(serviceUrn, "GetTransportInfo", mapOf(
                    "CurrentTransportState" to state, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1"
                ))
            }
            "getmediainfo" -> soapEnvelope(serviceUrn, "GetMediaInfo", mapOf(
                "NrTracks" to "1", "MediaDuration" to currentDuration(),
                "CurrentURI" to currentUriSafe(), "CurrentURIMetaData" to curMetaSafe(),
                "NextURI" to "", "NextURIMetaData" to ""
            ))
            "getdevicecapabilities" -> soapEnvelope(serviceUrn, "GetDeviceCapabilities", mapOf("PlayMedia" to "NETWORK,LOCAL"))
            "gettransportsettings" -> soapEnvelope(serviceUrn, "GetTransportSettings", mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"))
            "getcurrenttransportactions" -> soapEnvelope(serviceUrn, "GetCurrentTransportActions", mapOf("Actions" to "Play,Pause,Stop,Seek"))
            "setvolume" -> {
                val v = tag(body, "DesiredVolume")?.toDoubleOrNull() ?: 100.0
                synchronized(stateLock) { curVolume = v }
                emit("onSetVolume", mapOf("volume" to v))
                soapEnvelope(serviceUrn, "SetVolume")
            }
            "getvolume" -> {
                val v = synchronized(stateLock) { curVolume }
                soapEnvelope(serviceUrn, "GetVolume", mapOf("CurrentVolume" to v.toInt().toString()))
            }
            "setmute" -> soapEnvelope(serviceUrn, "SetMute")
            "getmute" -> soapEnvelope(serviceUrn, "GetMute", mapOf("CurrentMute" to "0"))
            "getprotocolinfo" -> soapEnvelope(serviceUrn, "GetProtocolInfo", mapOf(
                "Source" to "", "Sink" to "http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*"
            ))
            "getcurrentconnectionids" -> soapEnvelope(serviceUrn, "GetCurrentConnectionIDs", mapOf("ConnectionIDs" to "0"))
            "getcurrentconnectioninfo" -> soapEnvelope(serviceUrn, "GetCurrentConnectionInfo", mapOf(
                "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "http-get:*:video/*:*",
                "PeerConnectionManager" to "/", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK"
            ))
            else -> soapEnvelope(serviceUrn, a)
        }
    }

    // ==================== SSDP Discovery Client ====================
    private fun discoverInternal(timeoutMs: Long): List<Map<String, String>> {
        val found = mutableListOf<Map<String, String>>()
        val seen = mutableSetOf<String>()
        val socket = DatagramSocket(0)
        socket.soTimeout = 1000
        val searchTypes = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "ssdp:all")
        val addr = InetAddress.getByName("239.255.255.250")
        for (st in searchTypes) {
            val msearch = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: $st\r\nUSER-AGENT: miaodaCast/1.0 UPnP/1.0\r\n\r\n"
            val data = msearch.toByteArray(StandardCharsets.UTF_8)
            repeat(2) { try { socket.send(DatagramPacket(data, data.size, addr, 1900)) } catch (e: Exception) { /* ignore */ } }
            try { Thread.sleep(300) } catch (e: Exception) { /* ignore */ }
        }
        val end = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(8192)
        while (System.currentTimeMillis() < end) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                val location = findHeader(msg, "LOCATION")
                val usn = findHeader(msg, "USN") ?: ""
                val st = findHeader(msg, "ST") ?: ""
                if (location != null && !seen.contains(location) &&
                    (st.contains("MediaRenderer", ignoreCase = true) || st == "ssdp:all" || st == "upnp:rootdevice")) {
                    seen.add(location)
                    val name = fetchFriendlyName(location) ?: "投屏设备"
                    found.add(mapOf("location" to location, "friendlyName" to name, "udn" to usn))
                }
            } catch (e: SocketTimeoutException) { /* keep listening */ }
            catch (e: Exception) { break }
        }
        if (running && isUsableLanIp(localIp)) {
            val localLoc = "http://$localIp:$controlPort/description.xml"
            if (!seen.contains(localLoc)) {
                seen.add(localLoc)
                found.add(mapOf("location" to localLoc, "friendlyName" to friendlyName, "udn" to udn))
            }
        }
        socket.close()
        return found
    }

    private fun fetchFriendlyName(location: String): String? {
        return try {
            val conn = URL(location).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000; conn.readTimeout = 2000; conn.requestMethod = "GET"
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val m = Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(text)
            if (m.find()) m.group(1)?.trim() else null
        } catch (e: Exception) { null }
    }

    // ==================== HTTP Helpers ====================
    private fun sendXml(socket: Socket, xml: String) {
        val body = xml.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nCONTENT-LENGTH: ${body.size}\r\nCONNECTION: close\r\nSERVER: miaodaCast/1.0 UPnP/1.0\r\nEXT:\r\n\r\n"
        val os = socket.getOutputStream(); os.write(header.toByteArray(StandardCharsets.UTF_8)); os.write(body); os.flush()
    }

    private fun sendSoap(socket: Socket, body: String) {
        val b = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nCONTENT-LENGTH: ${b.size}\r\nCONNECTION: close\r\nSERVER: miaodaCast/1.0 UPnP/1.0\r\nEXT:\r\n\r\n"
        val os = socket.getOutputStream(); os.write(header.toByteArray(StandardCharsets.UTF_8)); os.write(b); os.flush()
    }

    private fun sendStatus(socket: Socket, code: Int, text: String) {
        val resp = "HTTP/1.1 $code $text\r\nCONTENT-LENGTH: 0\r\nCONNECTION: close\r\n\r\n"
        try { socket.getOutputStream().write(resp.toByteArray(StandardCharsets.UTF_8)); socket.getOutputStream().flush() } catch (e: Exception) { /* ignore */ }
    }

    private fun emit(name: String, body: Map<String, Any?>) {
        try { super@CastReceiverModule.sendEvent(name, body as Map<String, Any?>) } catch (e: Exception) { /* ignore */ }
    }

    private fun sendError(msg: String) {
        try { emit("onError", mapOf("message" to msg)) } catch (e: Exception) { /* ignore */ }
    }

    // ==================== XML Builders ====================
    private fun buildDescriptionXml(): String {
        val escName = escapeXml(friendlyName)
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$escName</friendlyName>
    <manufacturer>miaoda</manufacturer>
    <manufacturerURL>https://appmiaoda.com</manufacturerURL>
    <modelDescription>DLNA Media Renderer</modelDescription>
    <modelName>miaodaCast</modelName>
    <modelNumber>1.0</modelNumber>
    <UDN>$udn</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/dlna/AVTransport.xml</SCPDURL>
        <controlURL>/upnp/control/AVTransport</controlURL>
        <eventSubURL>/upnp/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/dlna/RenderingControl.xml</SCPDURL>
        <controlURL>/upnp/control/RenderingControl</controlURL>
        <eventSubURL>/upnp/event/RenderingControl</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/dlna/ConnectionManager.xml</SCPDURL>
        <controlURL>/upnp/control/ConnectionManager</controlURL>
        <eventSubURL>/upnp/event/ConnectionManager</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    private fun soapEnvelope(serviceUrn: String, action: String, args: Map<String, String> = emptyMap()): String {
        val inner = args.entries.joinToString("") { "<u:${it.key}>${escapeXml(it.value)}</u:${it.key}>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:${action}Response xmlns:u="$serviceUrn">$inner</u:${action}Response></s:Body>
</s:Envelope>"""
    }

    private fun currentTimes(): Pair<String, String> {
        val (pos, dur) = synchronized(stateLock) { Pair(curPositionMs, curDurationMs) }
        return Pair(formatTime(pos), formatTime(dur))
    }

    private fun currentDuration(): String {
        val dur = synchronized(stateLock) { curDurationMs }
        return formatTime(dur)
    }

    private fun currentUriSafe(): String = synchronized(stateLock) { curUri ?: "" }
    private fun curMetaSafe(): String = synchronized(stateLock) { curMeta ?: "" }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun parseTime(s: String): Long {
        val parts = s.split(":")
        if (parts.size != 3) return 0L
        val h = parts[0].toLongOrNull() ?: 0
        val m = parts[1].toLongOrNull() ?: 0
        val sec = parts[2].toLongOrNull() ?: 0
        return h * 3600 + m * 60 + sec
    }

    private fun findHeader(msg: String, name: String): String? {
        val m = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE).matcher(msg)
        return if (m.find()) m.group(1) else null
    }

    private fun tag(xml: String, name: String): String? {
        val m = Pattern.compile("<" + Pattern.quote(name) + ">(.*?)</" + Pattern.quote(name) + ">", Pattern.DOTALL).matcher(xml)
        return if (m.find()) m.group(1) else null
    }

    private fun extractActionFromBody(body: String): String? {
        val m = Pattern.compile("<u:(\\w+)", Pattern.CASE_INSENSITIVE).matcher(body)
        return if (m.find()) m.group(1) else null
    }

    private fun extractTitle(meta: String?): String? {
        if (meta.isNullOrBlank()) return null
        val m = Pattern.compile("<dc:title>(.*?)</dc:title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(meta)
        return if (m.find()) m.group(1) else null
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }

    private fun randomUuid(): String {
        val rnd = java.security.SecureRandom()
        val bytes = ByteArray(16); rnd.nextBytes(bytes)
        bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        val hex = bytes.joinToString("") { String.format("%02x", it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    // ==================== SCPD XML ====================
    private val AVTRANSPORT_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
      <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Play</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument></argumentList></action>
    <action><name>Pause</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
    <action><name>Stop</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
    <action><name>Seek</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument><argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument></argumentList></action>
    <action><name>GetPositionInfo</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument><argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument><argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument><argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument><argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument><argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument></argumentList></action>
    <action><name>GetTransportInfo</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument><argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument><argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument></argumentList></action>
    <action><name>GetMediaInfo</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument><argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument><argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument><argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument></argumentList></action>
    <action><name>GetDeviceCapabilities</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>PlayMedia</name><direction>out</direction><relatedStateVariable>PossiblePlaybackStorageMedia</relatedStateVariable></argument></argumentList></action>
    <action><name>GetTransportSettings</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>PlayMode</name><direction>out</direction><relatedStateVariable>CurrentPlayMode</relatedStateVariable></argument><argument><name>RecQualityMode</name><direction>out</direction><relatedStateVariable>CurrentRecordQualityMode</relatedStateVariable></argument></argumentList></action>
    <action><name>GetCurrentTransportActions</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Actions</name><direction>out</direction><relatedStateVariable>CurrentTransportActions</relatedStateVariable></argument></argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType><allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>PossiblePlaybackStorageMedia</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentPlayMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentRecordQualityMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTransportActions</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

    private val RENDERINGCONTROL_SCPD = """<?xml version="1.0" encoding="utf-8"?>
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
    <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>
    <stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType><allowedValueList><allowedValue>Master</allowedValue></allowedValueList></stateVariable>
  </serviceStateTable>
</scpd>"""

    private val CONNECTIONMANAGER_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name><argumentList>
      <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
      <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetCurrentConnectionIDs</name><argumentList>
      <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetCurrentConnectionInfo</name><argumentList>
      <argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
      <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>
      <argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>
      <argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>
      <argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>
      <argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
      <argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>
      <argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType><allowedValueList><allowedValue>OK</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType><allowedValueList><allowedValue>Input</allowedValue><allowedValue>Output</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
}
