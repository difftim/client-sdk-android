# IP 直连与自签证书验证 — Android SDK 使用指南

## 1. 功能概述

LiveKit Android SDK 支持 **IP 直连** 和 **自签证书验证** 能力，适用于以下场景：

- 客户端绕过公共 DNS，直接通过 IP 地址连接服务器
- 服务端使用企业内部自签 CA 签发的 TLS 证书

该功能同时支持 **WebSocket** 和 **QUIC (ttsignal)** 两种信令传输通道，通过 `ConnectOptions` 统一配置。

### 核心行为

| 配置 | 行为 |
|------|------|
| 不设置 `caCertPem` | 使用系统默认信任库，与原有行为完全一致 |
| 设置 `caCertPem` 为空字符串 | 等同于不设置 |
| 设置有效 `caCertPem` | 启用自签证书验证（证书链校验 + 域名匹配） |
| 设置无效 `caCertPem` | 记录错误日志，降级到系统默认信任库 |

---

## 2. 新增参数

在 `ConnectOptions` 中新增两个参数：

### `caCertPem: String?`

| 属性 | 说明 |
|------|------|
| 类型 | `String?` |
| 默认值 | `null` |
| 格式 | PEM 编码的 Root CA 证书，包含 `-----BEGIN CERTIFICATE-----` 和 `-----END CERTIFICATE-----` |
| 适用传输 | WebSocket 和 QUIC 均生效 |

设置后，TLS 握手时会验证服务端证书链是否由该 Root CA 签发，并检查证书 SAN 是否匹配主机名。

### `serverHost: String?`

| 属性 | 说明 |
|------|------|
| 类型 | `String?` |
| 默认值 | `null` |
| 适用传输 | WebSocket 和 QUIC 均生效 |

逻辑域名，用于 TLS SNI 和证书 hostname 验证。**当连接 URL 使用 IP 地址时必须设置**，确保 TLS 握手使用正确的域名。不设置时，自动使用连接 URL 中的 host。

> **重要**：IP 直连场景下，`serverHost` 应设置为证书中 SAN 字段对应的逻辑域名（如 `livekit.example.com`），而非 IP 地址。

---

## 3. 使用示例

### 场景一：IP 直连 + 自签证书（QUIC 通道）

```kotlin
val caCert = """
-----BEGIN CERTIFICATE-----
MIIBxTCCAWugAwIBAgIJAL...
...（Root CA 证书内容）...
-----END CERTIFICATE-----
""".trimIndent()

val options = ConnectOptions(
    useQuicSignal = true,
    serverHost = "livekit.mycompany.com",   // 逻辑域名（匹配证书 SAN）
    caCertPem = caCert,                      // 自签 Root CA 证书
)

// URL 使用 IP 地址直连
room.connect("wss://203.0.113.50:443", token, options)
```

### 场景二：IP 直连 + 自签证书（WebSocket 通道）

```kotlin
val options = ConnectOptions(
    useQuicSignal = false,                   // 使用 WebSocket
    serverHost = "livekit.mycompany.com",
    caCertPem = caCert,
)

room.connect("wss://203.0.113.50:443", token, options)
```

### 场景三：域名连接 + 自签证书（无需 IP 直连）

当 URL 使用域名时，不需要设置 `serverHost`，SDK 自动从 URL 中提取域名用于 TLS 验证。

```kotlin
val options = ConnectOptions(
    caCertPem = caCert,
    // serverHost 不设置 → 自动使用 URL 中的域名 "livekit.mycompany.com"
)

room.connect("wss://livekit.mycompany.com:443", token, options)
```

### 场景四：多个根 CA 证书

将多个 PEM 证书拼接在一起即可：

```kotlin
val multiCaCert = """
-----BEGIN CERTIFICATE-----
...（Root CA 1）...
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
...（Root CA 2）...
-----END CERTIFICATE-----
""".trimIndent()

val options = ConnectOptions(
    useQuicSignal = true,
    serverHost = "livekit.mycompany.com",
    caCertPem = multiCaCert,
)
```

### 场景五：不启用证书验证（默认行为，向后兼容）

无需任何额外配置，行为与改动前完全一致：

```kotlin
val options = ConnectOptions(
    useQuicSignal = true,
)

room.connect("wss://livekit.mycompany.com:443", token, options)
```

---

## 4. 证书要求

| 要求 | 说明 |
|------|------|
| 格式 | PEM 编码（Base64 文本），包含 BEGIN/END 标记 |
| 层级 | 仅需传入 **Root CA** 证书；中间证书由服务端在 TLS 握手时下发 |
| SAN | 服务端叶子证书的 Subject Alternative Name (SAN) 必须包含 `serverHost` 对应的域名 |
| 算法 | 支持 RSA、ECDSA 等标准算法 |

### PEM 证书格式示例

```
-----BEGIN CERTIFICATE-----
MIICpDCCAYwCCQDU+pQ4pHgSpDANBgkqhkiG9w0BAQsFADAUMRIwEAYDVQQDDAls
b2NhbGhvc3QwHhcNMjMwMTAxMDAwMDAwWhcNMjQwMTAxMDAwMDAwWjAUMRIwEAYD
...（Base64 编码的证书数据）...
VQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC7
-----END CERTIFICATE-----
```

### DER 转 PEM 工具方法

如果持有的是 DER 二进制格式证书，可使用以下方法转换：

```kotlin
fun derToPem(derBytes: ByteArray): String {
    val base64 = android.util.Base64.encodeToString(derBytes, android.util.Base64.NO_WRAP)
    return "-----BEGIN CERTIFICATE-----\n" +
        base64.chunked(64).joinToString("\n") +
        "\n-----END CERTIFICATE-----"
}

// 多个 DER 证书转为拼接的 PEM
val caCertPem = derFileList.joinToString("\n") { derToPem(it) }
```

---

## 5. 内部工作原理

### WebSocket 通道

```
ConnectOptions(caCertPem, serverHost)
  │
  ▼
WebSocketTransport.configureClient()
  │
  ├─ caCertPem 非空 → 构建自定义 TrustManager（仅信任该 CA）
  │   └─ OkHttpClient.newBuilder().sslSocketFactory(customSSL)
  │
  └─ serverHost 非空（IP 直连）
      ├─ URL 重写: wss://1.2.3.4:443/rtc → wss://real.domain.com:443/rtc
      ├─ 自定义 DNS: real.domain.com → 1.2.3.4（绕过公共 DNS）
      └─ TLS 握手自动使用 real.domain.com 作为 SNI 和 SAN 校验
```

### QUIC 通道

```
ConnectOptions(caCertPem, serverHost)
  │
  ▼
QuicTransport.connect()
  │
  └─ Config {
       hostname  = IP（连接目标）
       serverHost = real.domain.com（TLS SNI + 域名校验）
       caCertPem  = PEM 证书
     }
     │
     ▼ JNI → C++ 层
  证书链验证 + 域名匹配校验（OpenSSL）
```

---

## 6. 错误处理

| 场景 | 行为 |
|------|------|
| `caCertPem` 格式无效 | 记录错误日志，降级到系统默认信任库（不崩溃） |
| `caCertPem` 为空字符串 | 等同于未设置，使用系统默认信任库 |
| URL 解析异常 | 记录错误日志，使用原始 URL 连接（自签证书仍生效） |
| 证书链校验失败 | TLS 握手失败，连接被拒绝，触发 `RoomEvent.FailedToConnect` |
| 域名不匹配（SAN 不含 serverHost） | TLS 握手失败，连接被拒绝 |

---

## 7. 参数组合速查表

| URL | serverHost | caCertPem | 效果 |
|-----|-----------|-----------|------|
| `wss://1.2.3.4:443` | `real.domain.com` | 有效 PEM | IP 直连 + 自签证书校验 |
| `wss://real.domain.com:443` | 不设置 | 有效 PEM | 域名连接 + 自签证书校验 |
| `wss://real.domain.com:443` | 不设置 | 不设置 | 域名连接 + 系统信任库（默认行为） |
| `wss://1.2.3.4:443` | `real.domain.com` | 不设置 | IP 直连 + 系统信任库（通常失败，因系统无该 CA） |
| `wss://1.2.3.4:443` | 不设置 | 有效 PEM | 自签证书校验，但用 IP 做域名校验（通常失败，因证书无 IP SAN） |

> **最佳实践**：IP 直连场景下，`serverHost` 和 `caCertPem` 应一起配置。

---

## 8. 注意事项

1. **证书由上层应用传递**：SDK 不内置任何自签名根证书，由调用方在 `ConnectOptions.caCertPem` 中提供。
2. **重连自动继承**：首次连接设置的 `caCertPem` 和 `serverHost` 在断线重连时自动复用，无需额外处理。
3. **仅支持 PEM 格式**：不支持 DER 二进制格式直接传入，需先转换为 PEM 文本。
4. **QUIC 与 WebSocket 共用配置**：两种传输通道使用相同的 `caCertPem` 和 `serverHost` 参数，通过 `useQuicSignal` 切换通道。
