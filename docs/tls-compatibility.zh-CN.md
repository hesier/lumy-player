# Android 5.0–7.0 的 HTTPS 兼容

[English](tls-compatibility.md) | 简体中文

Android 7.1.1 之前的系统通常没有内置 ISRG Root X1。因此，即使服务器的 Let's Encrypt 证书链完全有效，也可能连接失败，报错 `CertPathValidatorException: Trust anchor for certification path not found`。

在 API 21–24 上，应用会把内置的官方 ISRG Root X1 加进共用的 OkHttp 客户端，和系统根证书一起使用。证书链仍由系统校验，域名仍由 OkHttp 校验，原有的系统根证书也都保留。Plex / Jellyfin / Emby 的 API 请求、Coil 加载海报和 Media3 的 OkHttp 数据源都走这个客户端。API 25 及以上保持系统默认的信任配置。

根证书随应用打包，运行时不会下载任何证书。不会把服务器发来的证书当作新的根证书信任，不会关闭证书校验，也不会修改整个进程的 TLS 默认设置。自签名证书、过期证书、域名不匹配和缺少中间证书的情况，这项兼容都不会放行。

## 内置证书来源

- 资源文件：`app/src/main/res/raw/isrg_root_x1.pem`
- 官方列表：https://letsencrypt.org/certificates/
- 来源：https://github.com/letsencrypt/website/blob/main/static/certs/isrgrootx1.pem
- DER SHA-256：`96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6`

更新这个文件时请参考官方根证书计划的说明，不要用某个网站的叶子证书或中间证书代替根证书。

## 验证

`net` 模块里的 `TlsTrustTest` 用本地 TLS 服务器模拟较旧的系统证书库，验证了：补充的根证书能通过中间证书完成校验、原有根证书仍然可用，未知的自签名证书、错误域名、过期证书和缺少中间证书都会被拒绝，叶子证书也不能被当作根证书添加。

JVM 测试无法模拟 Android 的 TLS 实现，所以这项修复还在海信 VIDAA 电视（Android 5.1.1 / API 22）上实测过：使用 Let's Encrypt 证书的 Emby 服务器，修复前报上面的错误，修复后可以正常登录和播放。
