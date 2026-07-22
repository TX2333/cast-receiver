// 应用侧封装：直接复用 expo-cast-receiver 原生模块（DLNA/UPnP DMR）。
// 该模块负责在局域网内广播被发现、响应 SSDP，并通过 SOAP 接收投屏指令。
export * from 'expo-cast-receiver';
