package expo.modules.castreceiver

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.UUID
import org.json.JSONObject

data class DlnaConfig(
  var port: Int = 0,
  var ip: String = "",
  var friendlyName: String = "投屏助手",
  var uuid: String = ""
)

data class PlaybackStateNative(
  var positionMs: Int = 0,
  var durationMs: Int = 0,
  var isPlaying: Boolean = false,
  var volume: Float = 0.5f
)

class CastReceiverModule : Module() {
  private val reactContext: Context get() = requireNotNull(appContext.reactContext) { "No react context" }

  @Volatile private var running = false
  private var multicastLock: WifiManager.MulticastLock? = null

  private val config = DlnaConfig()
  private val playbackState = PlaybackStateNative()

  private var ssdpSocket: MulticastSocket? = null
  private var ssdpNotifySocket: DatagramSocket? = null
  private var httpServerSocket: ServerSocket? = null
  private var httpPort: Int = 0
  private var localIp: String = ""
  private var activeIface: NetworkInterface? = null

  private val ssdpThreads = mutableListOf<Thread>()
  private val httpThreads = mutableListOf<Thread>()

  // ──────────────────────────────────────────────
  //  Expo Module Definition
  // ──────────────────────────────────────────────
  override fun definition() = ModuleDefinition {
    Name("CastReceiver")

    AsyncFunction("start") { cfg: Map<String, Any?> ->
      config.port = (cfg["port"] as? Number)?.toInt() ?: 0
      config.ip = cfg["ip"] as? String ?: ""
      config.friendlyName = cfg["friendlyName"] as? String ?: "投屏助手"
      val uid = cfg["uuid"] as? String
      config.uuid = if (uid.isNullOrBlank()) UUID.randomUUID().toString() else uid
      startInternal()
      // Return the server info directly so JS doesn't need to wait for event
      mapOf(
        "ip" to localIp,
        "port" to httpPort,
        "friendlyName" to config.friendlyName,
        "uuid" to config.uuid
      )
    }

    Function("stop") {
      stopInternal()
    }

    Function("updateState") { state: Map<String, Any?> ->
      playbackState.positionMs = (state["positionMs"] as? Number)?.toInt() ?: 0
      playbackState.durationMs = (state["durationMs"] as? Number)?.toInt() ?: 0
      playbackState.isPlaying = state["isPlaying"] as? Boolean ?: false
      // JS sends volume as 0-100 integer, convert to 0-1 float
      val vol = (state["volume"] as? Number)?.toFloat() ?: 50f
      playbackState.volume = (vol / 100f).coerceIn(0f, 1f)
    }

    AsyncFunction("discover") { timeoutMs: Int ->
      discoverInternal(timeoutMs)
    }

    OnDestroy {
      stopInternal()
    }
  }

  // ──────────────────────────────────────────────
  //  Event dispatch helper (avoid name clash with Module.sendEvent)
  // ──────────────────────────────────────────────
  private fun dispatchEvent(name: String, body: String?) {
    try {
      if (body == null) {
        sendEvent(name, emptyMap<String, Any?>())
        return
      }
      val args = try {
        val obj = JSONObject(body)
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          val v = obj.get(k)
          map[k] = when {
            v === JSONObject.NULL -> null
            v is JSONObject -> {
              val inner = mutableMapOf<String, Any?>()
              val ik = v.keys()
              while (ik.hasNext()) {
                val k2 = ik.next()
                val v2 = v.get(k2)
                inner[k2] = if (v2 === JSONObject.NULL) null else v2
              }
              inner
            }
            else -> v
          }
        }
        map
      } catch (_: Exception) {
        mapOf("value" to body)
      }
      sendEvent(name, args)
    } catch (_: Exception) {}
  }

  // ──────────────────────────────────────────────
  //  SSDP DISCOVER (client mode - find other renderers)
  // ──────────────────────────────────────────────
  private fun discoverInternal(timeoutMs: Int): List<Map<String, String>> {
    val results = mutableListOf<Map<String, String>>()
    val seenLocations = Collections.synchronizedSet(mutableSetOf<String>())

    try {
      val socket = DatagramSocket()
      socket.broadcast = true
      socket.soTimeout = timeoutMs

      val localAddr = detectLocalAddress()
      if (localAddr != null) {
        val nic = NetworkInterface.getByInetAddress(localAddr)
        if (nic != null) {
          try { socket.networkInterface = nic } catch (_: Exception) {}
        }
      }

      val searchTarget = "urn:schemas-upnp-org:device:MediaRenderer:1"
      val mx = (timeoutMs / 1000).coerceAtLeast(1).coerceAtMost(5)
      val msearch = buildMSearchRequest(mx, searchTarget)

      val sendData = msearch.toByteArray(Charsets.UTF_8)
      val multiGroup = InetAddress.getByName(SSDP_ADDR)
      val pkt = DatagramPacket(sendData, sendData.size, InetSocketAddress(multiGroup, SSDP_PORT))
      socket.send(pkt)
      try {
        val bcast = DatagramPacket(sendData, sendData.size, InetSocketAddress("255.255.255.255", SSDP_PORT))
        socket.send(bcast)
      } catch (_: Exception) {}

      val buf = ByteArray(2048)
      val deadline = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < deadline) {
        try {
          val recvPkt = DatagramPacket(buf, buf.size)
          socket.receive(recvPkt)
          val response = String(recvPkt.data, 0, recvPkt.length, Charsets.UTF_8)
          if (response.startsWith("HTTP/1.1 200 OK", ignoreCase = true)) {
            val location = extractHeader(response, "LOCATION") ?: extractHeader(response, "Location") ?: continue
            if (!seenLocations.add(location)) continue
            val st = extractHeader(response, "ST") ?: extractHeader(response, "st") ?: ""
            val usn = extractHeader(response, "USN") ?: extractHeader(response, "usn") ?: ""
            if (st.contains("MediaRenderer") || st == "ssdp:all" || st == "upnp:rootdevice") {
              val friendlyName = fetchFriendlyName(location)
              results.add(mapOf(
                "location" to location,
                "friendlyName" to friendlyName,
                "udn" to usn
              ))
            }
          }
        } catch (_: SocketTimeoutException) {
          break
        } catch (_: Exception) {
          // continue
        }
      }
      socket.close()
    } catch (_: Exception) {
    }

    return results
  }

  private fun buildMSearchRequest(mx: Int, st: String): String {
    return "M-SEARCH * HTTP/1.1\r\n" +
      "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
      "MAN: \"ssdp:discover\"\r\n" +
      "MX: $mx\r\n" +
      "ST: $st\r\n" +
      "\r\n"
  }

  private fun extractHeader(response: String, name: String): String? {
    val lines = response.split("\r\n", "\n")
    for (line in lines) {
      val idx = line.indexOf(':')
      if (idx > 0) {
        val key = line.substring(0, idx).trim()
        if (key.equals(name, ignoreCase = true)) {
          return line.substring(idx + 1).trim()
        }
      }
    }
    return null
  }

  private fun fetchFriendlyName(location: String): String {
    return try {
      val url = URL(location)
      val conn = url.openConnection() as HttpURLConnection
      conn.connectTimeout = 3000
      conn.readTimeout = 3000
      conn.requestMethod = "GET"
      val body = conn.inputStream.bufferedReader().use { it.readText() }
      val startTag = "<friendlyName>"
      val endTag = "</friendlyName>"
      val s = body.indexOf(startTag)
      val e = body.indexOf(endTag)
      if (s >= 0 && e > s) body.substring(s + startTag.length, e).trim() else "投屏设备"
    } catch (_: Exception) {
      "投屏设备"
    }
  }

  // ──────────────────────────────────────────────
  //  START / STOP
  // ──────────────────────────────────────────────
  private fun startInternal() {
    if (running) stopInternal()
    running = true

    acquireMulticastLock()

    // Start foreground service to keep process alive on Android 12+
    try {
      val ctx = reactContext
      val intent = Intent(ctx, CastReceiverService::class.java)
      intent.putExtra("friendlyName", config.friendlyName)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(intent)
      } else {
        ctx.startService(intent)
      }
    } catch (_: Exception) {}

    val addr = detectLocalAddress()
    if (addr != null) {
      localIp = addr.hostAddress ?: ""
      try {
        activeIface = NetworkInterface.getByInetAddress(addr)
      } catch (_: Exception) {
        activeIface = null
      }
    }

    httpPort = if (config.port > 0) config.port else 0
    httpServerSocket = if (httpPort > 0) {
      try {
        ServerSocket(httpPort).apply { reuseAddress = true }
      } catch (_: Exception) {
        ServerSocket(0).apply { reuseAddress = true }
      }
    } else {
      ServerSocket(0).apply { reuseAddress = true }
    }
    httpPort = httpServerSocket!!.localPort

    dispatchEvent("onStarted", JSONObject(mapOf(
      "ip" to localIp,
      "port" to httpPort,
      "friendlyName" to config.friendlyName
    )).toString())

    val t1 = Thread { runSsdpListener() }
    t1.name = "DLNA-SSDP-Listener"
    t1.isDaemon = true
    t1.start()
    ssdpThreads.add(t1)

    val t2 = Thread { runSsdpNotify() }
    t2.name = "DLNA-SSDP-Notify"
    t2.isDaemon = true
    t2.start()
    ssdpThreads.add(t2)

    val t3 = Thread { runHttpServer() }
    t3.name = "DLNA-HTTP"
    t3.isDaemon = true
    t3.start()
    httpThreads.add(t3)
  }

  private fun stopInternal() {
    running = false
    // Stop foreground service
    try {
      reactContext.stopService(Intent(reactContext, CastReceiverService::class.java))
    } catch (_: Exception) {}
    try { ssdpSocket?.leaveGroup(InetAddress.getByName(SSDP_ADDR)) } catch (_: Exception) {}
    try { ssdpSocket?.close() } catch (_: Exception) {}
    try { ssdpNotifySocket?.close() } catch (_: Exception) {}
    try { httpServerSocket?.close() } catch (_: Exception) {}
    ssdpSocket = null
    ssdpNotifySocket = null
    httpServerSocket = null
    httpThreads.forEach { it.interrupt() }
    ssdpThreads.forEach { it.interrupt() }
    httpThreads.clear()
    ssdpThreads.clear()
    releaseMulticastLock()
  }

  // ──────────────────────────────────────────────
  //  NETWORK DETECTION (WiFi + Ethernet)
  // ──────────────────────────────────────────────
  private fun detectLocalAddress(): InetAddress? {
    if (config.ip.isNotBlank()) {
      try {
        val a = InetAddress.getByName(config.ip)
        if (!a.isLoopbackAddress && a is Inet4Address) {
          return a
        }
      } catch (_: Exception) {}
    }

    try {
      val cm = reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      if (cm != null) {
        val net = cm.activeNetwork
        if (net != null) {
          val lp = cm.getLinkProperties(net)
          if (lp != null) {
            for (addr in lp.linkAddresses) {
              val a = addr.address
              if (a is Inet4Address && !a.isLoopbackAddress) {
                return a
              }
            }
          }
        }
      }
    } catch (_: Exception) {}

    try {
      val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
      for (nic in ifaces) {
        if (!nic.isUp || nic.isLoopback) continue
        val addrs = Collections.list(nic.inetAddresses)
        for (a in addrs) {
          if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
            try { activeIface = nic } catch (_: Exception) {}
            return a
          }
        }
      }
    } catch (_: Exception) {}

    return null
  }

  private fun acquireMulticastLock() {
    try {
      val wm = reactContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      if (wm != null) {
        multicastLock = wm.createMulticastLock("CastReceiverDLNA").also {
          it.setReferenceCounted(false)
          it.acquire()
        }
      }
    } catch (_: Exception) {}
  }

  private fun releaseMulticastLock() {
    try {
      multicastLock?.release()
    } catch (_: Exception) {}
    multicastLock = null
  }

  // ──────────────────────────────────────────────
  //  SSDP LISTENER (M-SEARCH responder)
  // ──────────────────────────────────────────────
  private fun runSsdpListener() {
    try {
      val sock = MulticastSocket(SSDP_PORT)
      sock.reuseAddress = true
      sock.soTimeout = 30000
      ssdpSocket = sock

      val group = InetAddress.getByName(SSDP_ADDR)
      val iface = activeIface
      if (iface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        sock.joinGroup(InetSocketAddress(group, SSDP_PORT), iface)
      } else {
        sock.joinGroup(group)
      }

      val buf = ByteArray(2048)
      while (running) {
        try {
          val pkt = DatagramPacket(buf, buf.size)
          sock.receive(pkt)
          val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
          if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
            handleMSearch(msg, pkt.address, pkt.port)
          }
        } catch (_: SocketTimeoutException) {
          // loop
        } catch (_: Exception) {
          if (running) Thread.sleep(500)
        }
      }
    } catch (_: Exception) {
      dispatchEvent("onError", JSONObject(mapOf("message" to "SSDP监听启动失败(端口1900被占用?)")).toString())
    }
  }

  private fun handleMSearch(msg: String, senderAddr: InetAddress, senderPort: Int) {
    val mx = extractHeaderValue(msg, "MX")?.toIntOrNull() ?: 1
    val delay = (Math.random() * mx.coerceAtMost(5) * 1000).toLong()
    Thread {
      try {
        Thread.sleep(delay)
        val st = extractHeaderValue(msg, "ST") ?: ""
        val responds = st == "ssdp:all" ||
          st == "upnp:rootdevice" ||
          st.contains("MediaRenderer") ||
          st.contains("AVTransport") ||
          st.contains("RenderingControl") ||
          st.contains("ConnectionManager")
        if (!responds) return@Thread

        val usn = "uuid:${config.uuid}"
        val location = "http://$localIp:$httpPort/description.xml"

        val resp = buildMSearchResponse(usn, location, st)
        val data = resp.toByteArray(Charsets.UTF_8)
        // CRITICAL: unicast response directly to the sender
        val p = DatagramPacket(data, data.size, InetSocketAddress(senderAddr, senderPort))
        ssdpSocket?.send(p)
      } catch (_: Exception) {}
    }.apply {
      name = "DLNA-MS-Resp"
      isDaemon = true
      start()
    }
  }

  private fun buildMSearchResponse(usn: String, location: String, st: String): String {
    val sb = StringBuilder()
    sb.append("HTTP/1.1 200 OK\r\n")
    sb.append("CACHE-CONTROL: max-age=1800\r\n")
    sb.append("DATE: ${java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).format(java.util.Date())}\r\n")
    sb.append("EXT:\r\n")
    sb.append("LOCATION: $location\r\n")
    sb.append("SERVER: Linux/3.0 UPnP/1.0 CastReceiver/1.0\r\n")
    sb.append("ST: $st\r\n")
    sb.append("USN: $usn::$st\r\n")
    sb.append("\r\n")
    return sb.toString()
  }

  private fun extractHeaderValue(msg: String, key: String): String? {
    val lines = msg.split("\r\n", "\n")
    for (l in lines) {
      val i = l.indexOf(':')
      if (i > 0 && l.substring(0, i).trim().equals(key, ignoreCase = true)) {
        return l.substring(i + 1).trim()
      }
    }
    return null
  }

  // ──────────────────────────────────────────────
  //  SSDP NOTIFY (alive/byebye)
  // ──────────────────────────────────────────────
  private fun runSsdpNotify() {
    try {
      val sock = DatagramSocket()
      sock.broadcast = true
      ssdpNotifySocket = sock

      val group = InetAddress.getByName(SSDP_ADDR)
      val location = "http://$localIp:$httpPort/description.xml"
      val usn = "uuid:${config.uuid}"

      for (r in 0..2) {
        for (nt in listOf("upnp:rootdevice", "urn:schemas-upnp-org:device:MediaRenderer:1")) {
          sendNotify(sock, group, usn, location, nt, "ssdp:alive")
        }
        Thread.sleep(100)
      }

      while (running) {
        Thread.sleep(30000)
        if (!running) break
        for (nt in listOf("upnp:rootdevice", "urn:schemas-upnp-org:device:MediaRenderer:1")) {
          sendNotify(sock, group, usn, location, nt, "ssdp:alive")
        }
      }

      for (nt in listOf("upnp:rootdevice", "urn:schemas-upnp-org:device:MediaRenderer:1")) {
        sendNotify(sock, group, usn, location, nt, "ssdp:byebye")
      }
    } catch (_: Exception) {}
  }

  private fun sendNotify(sock: DatagramSocket, group: InetAddress, usn: String, location: String, nt: String, nts: String) {
    try {
      val sb = StringBuilder()
      sb.append("NOTIFY * HTTP/1.1\r\n")
      sb.append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
      sb.append("CACHE-CONTROL: max-age=1800\r\n")
      if (nts == "ssdp:alive") sb.append("LOCATION: $location\r\n")
      sb.append("NT: $nt\r\n")
      sb.append("NTS: $nts\r\n")
      sb.append("SERVER: Linux/3.0 UPnP/1.0 CastReceiver/1.0\r\n")
      sb.append("USN: $usn::$nt\r\n")
      sb.append("\r\n")
      val data = sb.toString().toByteArray(Charsets.UTF_8)
      val pkt = DatagramPacket(data, data.size, InetSocketAddress(group, SSDP_PORT))
      sock.send(pkt)
    } catch (_: Exception) {}
  }

  // ──────────────────────────────────────────────
  //  HTTP SERVER (description.xml + SOAP control + eventing)
  // ──────────────────────────────────────────────
  private fun runHttpServer() {
    val serverSock = httpServerSocket ?: return
    while (running) {
      try {
        val client = serverSock.accept()
        client.soTimeout = 10000
        Thread {
          try {
            handleHttpRequest(client)
          } catch (_: Exception) {}
          finally {
            try { client.close() } catch (_: Exception) {}
          }
        }.apply {
          name = "DLNA-HTTP-Client-${System.nanoTime()}"
          isDaemon = true
          start()
          httpThreads.add(this)
        }
      } catch (_: Exception) {
        if (running) Thread.sleep(500)
      }
    }
  }

  private fun handleHttpRequest(client: java.net.Socket) {
    val reader = client.getInputStream().bufferedReader()
    val writer = client.getOutputStream()

    var firstLine = ""
    var contentLength = 0
    var path = "/"

    while (true) {
      val l = reader.readLine() ?: return
      if (l.isEmpty()) break
      if (firstLine.isEmpty()) {
        firstLine = l
        val parts = l.split(" ")
        if (parts.size >= 2) path = parts[1]
      } else {
        if (l.startsWith("Content-Length:", ignoreCase = true)) {
          contentLength = l.substringAfter(':').trim().toIntOrNull() ?: 0
        }
      }
    }

    var body = ""
    if (contentLength > 0) {
      val buf = CharArray(contentLength)
      var read = 0
      while (read < contentLength) {
        val r = reader.read(buf, read, contentLength - read)
        if (r < 0) break
        read += r
      }
      body = String(buf)
    }

    when {
      firstLine.startsWith("GET", ignoreCase = true) -> {
        when {
          path.startsWith("/description.xml") || path == "/" || path.startsWith("/index") ->
            writeXml(writer, buildDescriptionXml())
          else -> writeNotFound(writer)
        }
      }
      firstLine.startsWith("POST", ignoreCase = true) -> handleSoap(writer, path, body)
      firstLine.startsWith("SUBSCRIBE", ignoreCase = true) -> handleSubscribe(writer)
      else -> writeNotFound(writer)
    }
  }

  private fun buildDescriptionXml(): String {
    val usn = "uuid:${config.uuid}"
    return """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>${config.friendlyName}</friendlyName>
    <manufacturer>CastReceiver</manufacturer>
    <manufacturerURL>http://$localIp:$httpPort</manufacturerURL>
    <modelName>CastReceiver</modelName>
    <modelNumber>1.0</modelNumber>
    <UDN>$usn</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/upnp/control/AVTransport</controlURL>
        <eventSubURL>/upnp/event/AVTransport</eventSubURL>
        <SCPDURL>/upnp/AVTransport.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>/upnp/control/RenderingControl</controlURL>
        <eventSubURL>/upnp/event/RenderingControl</eventSubURL>
        <SCPDURL>/upnp/RenderingControl.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>/upnp/control/ConnectionManager</controlURL>
        <eventSubURL>/upnp/event/ConnectionManager</eventSubURL>
        <SCPDURL>/upnp/ConnectionManager.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""
  }

  private fun handleSoap(writer: java.io.OutputStream, path: String, body: String) {
    val actionMatch = Regex("""<u:(\w+)\s""").find(body)
    val action = actionMatch?.groupValues?.getOrNull(1) ?: ""

    when {
      action == "SetAVTransportURI" -> {
        val uriMatch = Regex("""<CurrentURI>([^<]*)</CurrentURI>""").find(body)
        val uri = uriMatch?.groupValues?.getOrNull(1)?.let { decodeXml(it) } ?: ""
        if (uri.isNotEmpty()) {
          val metaMatch = Regex("""<CurrentURIMetaData>([\s\S]*?)</CurrentURIMetaData>""").find(body)
          val metaRaw = metaMatch?.groupValues?.getOrNull(1)?.let { decodeXml(it) } ?: ""
          var title = "视频"
          val titleMatch = Regex("""<dc:title>([^<]*)</dc:title>""").find(metaRaw)
          if (titleMatch != null) title = decodeXml(titleMatch.groupValues[1])
          dispatchEvent("onPlay", JSONObject(mapOf(
            "url" to uri,
            "title" to title
          )).toString())
        }
        writeSoapResponse(writer, action)
      }
      action in setOf("Play", "Pause", "Stop", "Next", "Previous") -> {
        val event = when (action) {
          "Play" -> "onResume"
          "Pause" -> "onPause"
          "Stop" -> "onStop"
          "Next" -> "onNext"
          "Previous" -> "onPrevious"
          else -> ""
        }
        if (event.isNotEmpty()) dispatchEvent(event, null)
        writeSoapResponse(writer, action)
      }
      action == "Seek" -> {
        val targetMatch = Regex("""<Target>([^<]*)</Target>""").find(body)
        val target = targetMatch?.groupValues?.getOrNull(1) ?: "00:00:00"
        dispatchEvent("onSeek", JSONObject(mapOf("target" to target)).toString())
        writeSoapResponse(writer, action)
      }
      action == "SetVolume" -> {
        val volMatch = Regex("""<DesiredVolume>([^<]*)</DesiredVolume>""").find(body)
        val vol = volMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (vol != null) {
          playbackState.volume = vol / 100f
          dispatchEvent("onVolume", vol.toString())
        }
        writeSoapResponse(writer, action)
      }
      action == "SetMute" -> {
        writeSoapResponse(writer, action)
      }
      action == "GetPositionInfo" -> {
        writeSoapResult(writer, action, """
          <Track>0</Track>
          <TrackDuration>${formatTime(playbackState.durationMs)}</TrackDuration>
          <RelTime>${formatTime(playbackState.positionMs)}</RelTime>
          <AbsTime>${formatTime(playbackState.positionMs)}</AbsTime>
        """.trimIndent())
      }
      action == "GetTransportInfo" -> {
        val state = if (playbackState.isPlaying) "PLAYING" else "PAUSED_PLAYBACK"
        writeSoapResult(writer, action, """
          <CurrentTransportState>$state</CurrentTransportState>
          <CurrentTransportStatus>OK</CurrentTransportStatus>
          <CurrentSpeed>1</CurrentSpeed>
        """.trimIndent())
      }
      action == "GetVolume" -> {
        writeSoapResult(writer, action, "<CurrentVolume>${(playbackState.volume * 100).toInt()}</CurrentVolume>")
      }
      action == "GetProtocolInfo" -> {
        writeSoapResult(writer, action, "<Source>http-get:*:video/*:*</Source><Sink>http-get:*:video/*:*</Sink>")
      }
      else -> writeSoapResponse(writer, action)
    }
  }

  private fun writeSoapResponse(writer: java.io.OutputStream, action: String) {
    val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"></u:${action}Response></s:Body>
</s:Envelope>"""
    writeHttp(writer, "HTTP/1.1 200 OK", "text/xml; charset=\"utf-8\"", body)
  }

  private fun writeSoapResult(writer: java.io.OutputStream, action: String, result: String) {
    val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$result</u:${action}Response></s:Body>
</s:Envelope>"""
    writeHttp(writer, "HTTP/1.1 200 OK", "text/xml; charset=\"utf-8\"", body)
  }

  private fun handleSubscribe(writer: java.io.OutputStream) {
    val resp = "HTTP/1.1 200 OK\r\n" +
      "SID:uuid:${config.uuid}\r\n" +
      "TIMEOUT:Second-1800\r\n\r\n"
    writer.write(resp.toByteArray(Charsets.UTF_8))
    writer.flush()
  }

  private fun writeXml(writer: java.io.OutputStream, xml: String) {
    writeHttp(writer, "HTTP/1.1 200 OK", "text/xml; charset=\"utf-8\"", xml)
  }

  private fun writeNotFound(writer: java.io.OutputStream) {
    writeHttp(writer, "HTTP/1.1 404 Not Found", "text/plain", "Not Found")
  }

  private fun writeHttp(writer: java.io.OutputStream, status: String, contentType: String, body: String) {
    val data = body.toByteArray(Charsets.UTF_8)
    val sb = StringBuilder()
    sb.append("$status\r\n")
    sb.append("Content-Type: $contentType\r\n")
    sb.append("Content-Length: ${data.size}\r\n")
    sb.append("Connection: close\r\n")
    sb.append("Access-Control-Allow-Origin: *\r\n")
    sb.append("\r\n")
    writer.write(sb.toString().toByteArray(Charsets.UTF_8))
    writer.write(data)
    writer.flush()
  }

  private fun decodeXml(s: String): String {
    return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
  }

  private fun formatTime(ms: Int): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d:%02d", h, m, sec)
  }

  companion object {
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
  }
}
