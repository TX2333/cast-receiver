package expo.modules.castreceiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Enumeration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CastReceiverModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("CastReceiver")

    Function("getDeviceId") {
      val prefs = appContext.reactContext?.getSharedPreferences("cast_receiver_prefs", Context.MODE_PRIVATE)
      var id = prefs?.getString("device_id", null)
      if (id == null) {
        id = UUID.randomUUID().toString()
        prefs?.edit()?.putString("device_id", id)?.apply()
      }
      id
    }

    AsyncFunction("startAsync") { deviceName: String ->
      startService(deviceName)
    }

    AsyncFunction("stopAsync") {
      stopService()
    }

    Function("isRunning") {
      isRunning.get()
    }

    Events("onPlay", "onPause", "onStop", "onSeek", "onVolume", "onError")

    OnDestroy {
      stopService()
    }
  }

  companion object {
    private const val TAG = "CastReceiverModule"
    private const val SSDP_PORT = 1900
    private const val SSDP_ADDR = "239.255.255.250"
    private const val NOTIFY_INTERVAL_MS = 30_000L
    private const val CHANNEL_ID = "cast_receiver_channel"
    private const val NOTIFICATION_ID = 9527
  }

  private val running = AtomicBoolean(false)
  private val isRunning = AtomicBoolean(false)
  private val uuid: String by lazy {
    val prefs = appContext.reactContext?.getSharedPreferences("cast_receiver_prefs", Context.MODE_PRIVATE)
    var id = prefs?.getString("device_id", null)
    if (id == null) {
      id = UUID.randomUUID().toString()
      prefs?.edit()?.putString("device_id", id)?.apply()
    }
    id!!
  }

  private var deviceName = "投屏助手"
  private var httpPort = 0
  private var localIp: String = "0.0.0.0"
  private var activeInterface: NetworkInterface? = null

  private var httpServerThread: Thread? = null
  private var ssdpListenerThread: Thread? = null
  private var ssdpNotifyThread: Thread? = null
  private var serverSocket: ServerSocket? = null
  private var multicastSocket: MulticastSocket? = null
  private var notifySocket: DatagramSocket? = null
  private var multicastLock: WifiManager.MulticastLock? = null
  private var startedLatch = CountDownLatch(1)

  private fun emit(eventName: String, body: Map<String, Any?> = emptyMap()) {
    try {
      sendEvent(eventName, body)
    } catch (e: Exception) {
      Log.e(TAG, "emit $eventName failed", e)
    }
  }

  private fun startService(name: String) {
    if (running.getAndSet(true)) return
    deviceName = name.ifBlank { "投屏助手" }

    try {
      ensureNotificationChannel()
      val ctx = appContext.reactContext ?: throw IllegalStateException("React context not available")
      val intent = Intent(ctx, CastReceiverService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(intent)
      } else {
        ctx.startService(intent)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to start foreground service, continuing without it", e)
    }

    startedLatch = CountDownLatch(1)
    httpServerThread = Thread(::runHttpServer, "cast-http-server").apply { isDaemon = true; start() }

    try {
      startedLatch.await(5, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {}

    if (httpPort == 0) {
      Log.e(TAG, "HTTP server failed to start")
      running.set(false)
      throw RuntimeException("Failed to start HTTP server")
    }

    acquireMulticastLock()

    ssdpListenerThread = Thread(::runSsdpListener, "cast-ssdp-listener").apply { isDaemon = true; start() }
    ssdpNotifyThread = Thread(::runSsdpNotify, "cast-ssdp-notify").apply { isDaemon = true; start() }

    isRunning.set(true)
    Log.i(TAG, "Cast receiver started on $localIp:$httpPort (uuid=$uuid)")
  }

  private fun stopService() {
    if (!running.getAndSet(false)) return
    isRunning.set(false)

    try { sendByebye() } catch (_: Exception) {}

    multicastLock?.release()
    multicastLock = null

    try { serverSocket?.close() } catch (_: Exception) {}
    try { multicastSocket?.close() } catch (_: Exception) {}
    try { notifySocket?.close() } catch (_: Exception) {}
    serverSocket = null
    multicastSocket = null
    notifySocket = null

    val ctx = appContext.reactContext
    if (ctx != null) {
      try { ctx.stopService(Intent(ctx, CastReceiverService::class.java)) } catch (_: Exception) {}
    }

    httpServerThread?.join(2000)
    ssdpListenerThread?.join(2000)
    ssdpNotifyThread?.join(2000)
    httpServerThread = null
    ssdpListenerThread = null
    ssdpNotifyThread = null

    Log.i(TAG, "Cast receiver stopped")
  }

  private fun acquireMulticastLock() {
    try {
      val ctx = appContext.reactContext ?: return
      val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
      multicastLock = wifi.createMulticastLock("castReceiverLock").apply {
        setReferenceCounted(false)
        acquire()
      }
      Log.i(TAG, "Multicast lock acquired")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to acquire multicast lock (may be on ethernet)", e)
    }
  }

  private fun detectLocalAddress(): InetAddress {
    try {
      val cm = appContext.reactContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      if (cm != null) {
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
          val linkProperties = cm.getLinkProperties(activeNetwork)
          if (linkProperties != null) {
            for (addr in linkProperties.linkAddresses) {
              val a = addr.address
              if (a is Inet4Address && !a.isLoopbackAddress) {
                val iface = NetworkInterface.getByInetAddress(a)
                if (iface != null && iface.isUp && !iface.isLoopback) {
                  activeInterface = iface
                  localIp = a.hostAddress ?: "0.0.0.0"
                  Log.i(TAG, "Detected IP from active network: $localIp (iface=${iface.name})")
                  return a
                }
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to detect via ConnectivityManager, falling back", e)
    }

    try {
      val ifaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
      for (iface in Collections.list(ifaces)) {
        if (!iface.isUp || iface.isLoopback) continue
        val name = iface.name.lowercase()
        val isPreferred = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") || name.startsWith("wl")
        if (!isPreferred) continue
        for (addr in Collections.list(iface.inetAddresses)) {
          if (addr is Inet4Address && !addr.isLoopbackAddress) {
            activeInterface = iface
            localIp = addr.hostAddress ?: "0.0.0.0"
            Log.i(TAG, "Detected IP via enumeration: $localIp (iface=${iface.name})")
            return addr
          }
        }
      }
      for (iface in Collections.list(ifaces)) {
        if (!iface.isUp || iface.isLoopback) continue
        for (addr in Collections.list(iface.inetAddresses)) {
          if (addr is Inet4Address && !addr.isLoopbackAddress) {
            activeInterface = iface
            localIp = addr.hostAddress ?: "0.0.0.0"
            Log.i(TAG, "Detected IP (last resort): $localIp (iface=${iface.name})")
            return addr
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to enumerate network interfaces", e)
    }
    throw RuntimeException("No suitable network interface found")
  }

  // ---------- HTTP Server ----------

  private fun runHttpServer() {
    try {
      detectLocalAddress()
      val addr = InetAddress.getByName(localIp)
      serverSocket = ServerSocket()
      serverSocket?.reuseAddress = true
      serverSocket?.bind(InetSocketAddress(addr, 0), 50)
      httpPort = serverSocket?.localPort ?: 0
      startedLatch.countDown()
      Log.i(TAG, "HTTP server listening on $localIp:$httpPort")

      while (running.get()) {
        val client: Socket = try {
          serverSocket?.accept() ?: break
        } catch (_: Exception) { break }
        Thread({ handleHttpClient(client) }, "cast-http-client-${System.nanos()}").apply { isDaemon = true; start() }
      }
    } catch (e: Exception) {
      Log.e(TAG, "HTTP server error", e)
      startedLatch.countDown()
    }
  }

  private fun handleHttpClient(client: Socket) {
    try {
      client.soTimeout = 10_000
      val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
      val requestLine = reader.readLine() ?: return
      Log.d(TAG, "HTTP request: $requestLine from ${client.inetAddress.hostAddress}")

      val headers = mutableMapOf<String, String>()
      while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
      }

      val method = requestLine.substringBefore(' ')
      val path = requestLine.substringAfter(' ').substringBefore(' ')

      val response = when {
        method == "GET" && (path == "/description.xml" || path.startsWith("/description.xml")) ->
          buildDescriptionResponse()
        method == "SUBSCRIBE" -> handleSubscribe(headers)
        method == "POST" && path.contains("AVTransport") -> handleAvTransport(headers, reader)
        method == "POST" && path.contains("RenderingControl") -> handleRenderingControl(headers, reader)
        method == "POST" && path.contains("ConnectionManager") -> handleConnectionManager(headers, reader)
        else -> buildSimpleResponse("404 Not Found", "text/plain", "Not Found")
      }

      client.getOutputStream().write(response.toByteArray(StandardCharsets.UTF_8))
      client.getOutputStream().flush()
    } catch (e: Exception) {
      Log.e(TAG, "HTTP client error", e)
    } finally {
      try { client.close() } catch (_: Exception) {}
    }
  }

  private fun buildDescriptionResponse(): String {
    val urlBase = "http://$localIp:$httpPort"
    val safeName = deviceName.replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    return """HTTP/1.1 200 OK
Content-Type: application/xml; charset=utf-8
Connection: close
Cache-Control: no-cache
Access-Control-Allow-Origin: *

<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$safeName</friendlyName>
    <manufacturer>CastReceiver</manufacturer>
    <manufacturerURL>https://example.com</manufacturerURL>
    <modelName>CastReceiver</modelName>
    <modelNumber>1</modelNumber>
    <UDN>uuid:$uuid</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>$urlBase/AVTransport/scpd.xml</SCPDURL>
        <controlURL>$urlBase/AVTransport/control</controlURL>
        <eventSubURL>$urlBase/AVTransport/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>$urlBase/RenderingControl/scpd.xml</SCPDURL>
        <controlURL>$urlBase/RenderingControl/control</controlURL>
        <eventSubURL>$urlBase/RenderingControl/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>$urlBase/ConnectionManager/scpd.xml</SCPDURL>
        <controlURL>$urlBase/ConnectionManager/control</controlURL>
        <eventSubURL>$urlBase/ConnectionManager/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>""".replace("\n", "\r\n")
  }

  private fun handleSubscribe(headers: Map<String, String>): String {
    val sid = "uuid:${UUID.randomUUID()}"
    val timeout = headers["timeout"]?.replace("Second-", "")?.toIntOrNull() ?: 1800
    return """HTTP/1.1 200 OK
SID: $sid
TIMEOUT: Second-$timeout
Server: CastReceiver/1.0
Connection: close

""".replace("\n", "\r\n")
  }

  private fun readBody(reader: BufferedReader, headers: Map<String, String>): String {
    val len = headers["content-length"]?.toIntOrNull() ?: 0
    if (len <= 0) return ""
    val chars = CharArray(len)
    var total = 0
    while (total < len) {
      val r = reader.read(chars, total, len - total)
      if (r < 0) break
      total += r
    }
    return String(chars, 0, total)
  }

  private fun handleAvTransport(headers: Map<String, String>, reader: BufferedReader): String {
    val body = readBody(reader, headers)
    val soapAction = headers["soapaction"]?.removePrefix("\"")?.removeSuffix("\"") ?: ""
    Log.d(TAG, "AVTransport action: $soapAction")
    when {
      soapAction.contains("SetAVTransportURI") -> {
        val uri = extractTag(body, "CurrentURI")
        val meta = extractTag(body, "CurrentURIMetaData")
        if (uri.isNotBlank()) emit("onPlay", mapOf("url" to uri, "metadata" to meta))
      }
      soapAction.contains("Play") -> emit("onPlay", emptyMap())
      soapAction.contains("Pause") -> emit("onPause", emptyMap())
      soapAction.contains("Stop") -> emit("onStop", emptyMap())
      soapAction.contains("Seek") -> {
        val target = extractTag(body, "Target")
        emit("onSeek", mapOf("position" to target))
      }
    }
    return buildSoapResponse()
  }

  private fun handleRenderingControl(headers: Map<String, String>, reader: BufferedReader): String {
    val body = readBody(reader, headers)
    val soapAction = headers["soapaction"]?.removePrefix("\"")?.removeSuffix("\"") ?: ""
    Log.d(TAG, "RenderingControl action: $soapAction")
    when {
      soapAction.contains("SetVolume") -> {
        val vol = extractTag(body, "DesiredVolume").toIntOrNull() ?: 50
        emit("onVolume", mapOf("volume" to (vol / 100.0)))
      }
      soapAction.contains("SetMute") -> {
        val mute = extractTag(body, "DesiredMute").equals("1", ignoreCase = true)
        emit("onVolume", mapOf("muted" to mute))
      }
    }
    return buildSoapResponse()
  }

  private fun handleConnectionManager(headers: Map<String, String>, reader: BufferedReader): String {
    val soapAction = headers["soapaction"]?.removePrefix("\"")?.removeSuffix("\"") ?: ""
    Log.d(TAG, "ConnectionManager action: $soapAction")
    return when {
      soapAction.contains("GetProtocolInfo") -> buildSoapResponse("""<u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"><Source>http-get:*:*:*</Source><Sink>http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Sink></u:GetProtocolInfoResponse>""")
      soapAction.contains("GetCurrentConnectionIDs") -> buildSoapResponse("""<u:GetCurrentConnectionIDsResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"><ConnectionIDs>0</ConnectionIDs></u:GetCurrentConnectionIDsResponse>""")
      soapAction.contains("GetCurrentConnectionInfo") -> buildSoapResponse("""<u:GetCurrentConnectionInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"><RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo>http-get:*:*:*</ProtocolInfo><PeerConnectionManager>-1</PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status></u:GetCurrentConnectionInfoResponse>""")
      else -> buildSoapResponse()
    }
  }

  private fun buildSoapResponse(actionXml: String = ""): String {
    val body = if (actionXml.isBlank()) """<u:Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"></u:Response>""" else actionXml
    return """HTTP/1.1 200 OK
Content-Type: text/xml; charset=utf-8
EXT:
Server: CastReceiver/1.0 UPnP/1.0
Connection: close

<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>$body</s:Body></s:Envelope>""".replace("\n", "\r\n")
  }

  private fun buildSimpleResponse(status: String, contentType: String, body: String): String {
    return """HTTP/1.1 $status
Content-Type: $contentType; charset=utf-8
Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}
Connection: close

$body""".replace("\n", "\r\n")
  }

  private fun extractTag(xml: String, tag: String): String {
    val open = "<$tag>"
    val close = "</$tag>"
    val s = xml.indexOf(open)
    if (s < 0) return ""
    val e = xml.indexOf(close, s + open.length)
    return if (e > s) xml.substring(s + open.length, e) else ""
  }

  // ---------- SSDP ----------

  private fun buildSsdpNotify(nt: String, nts: String): String {
    val usn = if (nt == "uuid:$uuid") "uuid:$uuid" else "uuid:$uuid::$nt"
    val location = "http://$localIp:$httpPort/description.xml"
    val maxAge = NOTIFY_INTERVAL_MS / 1000 * 2
    return buildString {
      append("NOTIFY * HTTP/1.1\r\n")
      append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
      append("CACHE-CONTROL: max-age=$maxAge\r\n")
      append("LOCATION: $location\r\n")
      append("NT: $nt\r\n")
      append("NTS: $nts\r\n")
      append("SERVER: CastReceiver/1.0 UPnP/1.0\r\n")
      append("USN: $usn\r\n")
      append("\r\n")
    }
  }

  private fun buildSsdpResponse(st: String): String {
    val usn = when (st) {
      "upnp:rootdevice" -> "uuid:$uuid::upnp:rootdevice"
      "uuid:$uuid" -> "uuid:$uuid"
      else -> "uuid:$uuid::$st"
    }
    val location = "http://$localIp:$httpPort/description.xml"
    val maxAge = NOTIFY_INTERVAL_MS / 1000 * 2
    return buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("CACHE-CONTROL: max-age=$maxAge\r\n")
      append("EXT:\r\n")
      append("LOCATION: $location\r\n")
      append("SERVER: CastReceiver/1.0 UPnP/1.0\r\n")
      append("ST: $st\r\n")
      append("USN: $usn\r\n")
      append("\r\n")
    }
  }

  private fun sendSsdpMessage(socket: DatagramSocket, message: String, address: InetAddress, port: Int) {
    try {
      val data = message.toByteArray(StandardCharsets.UTF_8)
      val packet = DatagramPacket(data, data.size, address, port)
      socket.send(packet)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send SSDP to $address:$port", e)
    }
  }

  private fun runSsdpListener() {
    var socket: MulticastSocket? = null
    try {
      val group = InetAddress.getByName(SSDP_ADDR)
      socket = MulticastSocket(SSDP_PORT)
      socket.reuseAddress = true
      socket.soTimeout = 5000
      socket.broadcast = true

      try {
        val iface = activeInterface
        if (iface != null) {
          socket.joinGroup(InetSocketAddress(group, 0), iface)
          Log.i(TAG, "Joined SSDP on ${iface.name}")
        } else {
          socket.joinGroup(group)
          Log.i(TAG, "Joined SSDP (default)")
        }
      } catch (e: Exception) {
        Log.w(TAG, "joinGroup with iface failed, trying default", e)
        try { socket.joinGroup(group) } catch (e2: Exception) { Log.e(TAG, "joinGroup failed", e2) }
      }

      multicastSocket = socket
      val buf = ByteArray(2048)

      while (running.get()) {
        try {
          val packet = DatagramPacket(buf, buf.size)
          socket.receive(packet)
          val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
          if (text.startsWith("M-SEARCH", ignoreCase = true)) {
            handleMSearch(text, packet.address, packet.port, socket)
          }
        } catch (_: SocketTimeoutException) {
        } catch (e: Exception) {
          if (running.get()) Log.e(TAG, "SSDP receive error", e)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "SSDP listener fatal", e)
    } finally {
      try { socket?.close() } catch (_: Exception) {}
      if (multicastSocket === socket) multicastSocket = null
    }
  }

  private fun handleMSearch(text: String, senderAddr: InetAddress, senderPort: Int, socket: MulticastSocket) {
    val st = Regex("(?i)ST:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: return
    Log.d(TAG, "M-SEARCH from ${senderAddr.hostAddress}:$senderPort ST=$st")

    val responses = when (st) {
      "ssdp:all" -> listOf(
        "upnp:rootdevice",
        "uuid:$uuid",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1"
      )
      "upnp:rootdevice" -> listOf("upnp:rootdevice")
      "uuid:$uuid" -> listOf("uuid:$uuid")
      "urn:schemas-upnp-org:device:MediaRenderer:1" -> listOf("urn:schemas-upnp-org:device:MediaRenderer:1")
      "urn:schemas-upnp-org:service:AVTransport:1" -> listOf("urn:schemas-upnp-org:service:AVTransport:1")
      "urn:schemas-upnp-org:service:RenderingControl:1" -> listOf("urn:schemas-upnp-org:service:RenderingControl:1")
      "urn:schemas-upnp-org:service:ConnectionManager:1" -> listOf("urn:schemas-upnp-org:service:ConnectionManager:1")
      else -> return
    }

    Thread({
      try {
        for ((idx, respSt) in responses.withIndex()) {
          if (idx > 0) Thread.sleep(50)
          val response = buildSsdpResponse(respSt)
          // CRITICAL: unicast response directly to the searcher
          sendSsdpMessage(socket, response, senderAddr, senderPort)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed sending M-SEARCH response", e)
      }
    }, "cast-ssdp-resp").apply { isDaemon = true; start() }
  }

  private fun runSsdpNotify() {
    var socket: DatagramSocket? = null
    try {
      socket = DatagramSocket()
      socket.broadcast = true
      notifySocket = socket

      val group = InetAddress.getByName(SSDP_ADDR)
      val types = listOf(
        "upnp:rootdevice",
        "uuid:$uuid",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1"
      )

      // Send initial alive 3 times for reliability
      for (i in 0 until 3) {
        for (type in types) {
          sendSsdpMessage(socket, buildSsdpNotify(type, "ssdp:alive"), group, SSDP_PORT)
        }
        Thread.sleep(200)
      }

      while (running.get()) {
        Thread.sleep(NOTIFY_INTERVAL_MS)
        if (!running.get()) break
        for (type in types) {
          sendSsdpMessage(socket, buildSsdpNotify(type, "ssdp:alive"), group, SSDP_PORT)
          Thread.sleep(50)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "SSDP notify error", e)
    } finally {
      try { socket?.close() } catch (_: Exception) {}
      if (notifySocket === socket) notifySocket = null
    }
  }

  private fun sendByebye() {
    try {
      val socket = notifySocket ?: DatagramSocket()
      socket.broadcast = true
      val group = InetAddress.getByName(SSDP_ADDR)
      val types = listOf(
        "upnp:rootdevice",
        "uuid:$uuid",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1"
      )
      for (type in types) {
        sendSsdpMessage(socket, buildSsdpNotify(type, "ssdp:byebye"), group, SSDP_PORT)
      }
      if (notifySocket !== socket) socket.close()
    } catch (_: Exception) {}
  }

  // ---------- Notification ----------

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val ctx = appContext.reactContext ?: return
      val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (nm.getNotificationChannel(CHANNEL_ID) == null) {
        val ch = NotificationChannel(CHANNEL_ID, "投屏接收服务", NotificationManager.IMPORTANCE_LOW)
        ch.description = "DLNA投屏接收服务运行中"
        nm.createNotificationChannel(ch)
      }
    }
  }
}
