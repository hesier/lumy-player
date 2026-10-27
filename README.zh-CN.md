# Lumy Player

[English](README.md) | 简体中文

[![CI](https://github.com/hesier/lumy-player/actions/workflows/ci.yml/badge.svg)](https://github.com/hesier/lumy-player/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/hesier/lumy-player)](https://github.com/hesier/lumy-player/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)

一个面向 Android TV、安卓盒子和投影仪的媒体客户端，支持 Plex、Jellyfin 和 Emby，用遥控器操作。

<p align="center"><img src="docs/screenshots/home.webp" alt="首页"></p>

## 功能

- **登录与服务器**
  - Plex：plex.tv PIN 码登录，自动发现账号下的服务器并探测可用地址（局域网 / 远程 / 中继），连不上时自动重连
  - Jellyfin / Emby：输入服务器地址、用户名和密码登录（只填 IP 时默认 http 和端口 8096），也可以扫码在手机上填写
  - Android 5.0–7.0 的系统证书库较旧，也能连接使用 Let's Encrypt 证书的 HTTPS 服务器（见 [TLS 兼容说明](docs/tls-compatibility.zh-CN.md)）
  - 可以添加多台服务器（同一台服务器也可以添加多次），在侧边栏顶部切换，在设置里重命名、删除
- **浏览**：首页推荐行（继续观看、最近添加等）、媒体库网格（筛选、排序、首字母索引）、搜索、合集、播放列表、演员作品、更多类似
- **详情页**：影片 / 剧集 / 季 / 集，多版本选择，标记已看 / 未看
- **播放**（Media3 / ExoPlayer）
  - 按本机解码能力决定直接播放或让服务器转码为 HLS，可限制画质 / 码率；HDR、杜比视界、音频直通的支持情况见[播放与解码支持](docs/playback.zh-CN.md)
  - FFmpeg 音频解码扩展、音频直通（AC3 / DTS 交给功放）
  - 本地渲染 ASS / SSA 特效字幕（libass），尽量避免服务器烧录字幕
  - 多段文件、章节、跳过片头、自动下一集
  - 音画 / 字幕延迟调整、刷新率匹配
  - 记住音轨与字幕选择，进度上报给服务器
- **设置**：默认画质、是否允许转码、字幕大小、侧边栏显示项和顺序（每台服务器分开保存）、服务器管理等

## 截图

<table>
  <tr>
    <td><img src="docs/screenshots/movie.webp" alt="电影详情"><br><sub>电影详情</sub></td>
    <td><img src="docs/screenshots/episode.webp" alt="单集详情"><br><sub>单集详情</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/player.webp" alt="播放控制"><br><sub>播放控制</sub></td>
    <td><img src="docs/screenshots/playback-info.webp" alt="播放信息：直接播放 + FFmpeg 音频解码"><br><sub>播放信息：直接播放 + FFmpeg 音频解码</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/quality.webp" alt="画质与转码"><br><sub>画质与转码</sub></td>
    <td><img src="docs/screenshots/skip-intro.webp" alt="跳过片头"><br><sub>跳过片头</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/library.webp" alt="媒体库（筛选与排序）"><br><sub>媒体库（筛选与排序）</sub></td>
    <td><img src="docs/screenshots/add-server.webp" alt="选择服务器类型"><br><sub>选择服务器类型</sub></td>
  </tr>
</table>

## 安装

从 [最新 Release](https://github.com/hesier/lumy-player/releases/latest) 下载 `lumy-player-vX.Y.Z.apk`，侧载到电视、盒子或投影仪上即可。需要 Android 5.0 及以上的 ARM 设备。

自己编译的 APK 签名不同，不能覆盖安装 Release 版本（反之亦然），需要先卸载旧版，服务器和设置要重新配置。

## 已知限制

- 只显示电影和剧集媒体库，暂不支持音乐、照片等其他类型
- 不支持直播电视 / DVR，不支持离线下载
- 不支持 Plex Home 切换用户和 Jellyfin Quick Connect，请直接登录要使用的账号
- 杜比视界 Profile 5 需要设备有杜比视界解码器，否则需要服务器转码（详见[播放与解码支持](docs/playback.zh-CN.md#杜比视界)）
- 只支持 ARM 设备（`armeabi-v7a` / `arm64-v8a`）
- 手机扫码输入要求手机和电视在同一局域网。打不开网页时，请关闭手机 VPN，检查路由器的访客网络或 AP 隔离；电视连着多个网络时，断开用不到的那个

## 隐私

Lumy Player 不包含任何统计或追踪，只连接你添加的服务器，以及 Plex 登录和发现服务器时用到的 plex.tv。

手机扫码输入的网页由电视自己提供，不经过中转，也不加载第三方资源。每个二维码五分钟内有效、只接受一次提交，关闭窗口或应用切到后台就立即停止接收。手机通过局域网 HTTP 明文把信息发到电视，请只在可信的网络里使用。

## 开发环境

- JDK 17
- Android SDK（compileSdk / targetSdk 35）
- 设备：Android 5.0（API 21）及以上，ARM 架构（`armeabi-v7a` / `arm64-v8a`）

## 构建与安装

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:installDebug
```

Release 包：

```bash
./gradlew :app:assembleRelease
```

要用自己的密钥签名，把密钥库和 `keystore.properties` 放在项目根目录的 `.local/` 里（整个目录已加入 `.gitignore`）：

```properties
storeFile=release.jks
storePassword=...
keyAlias=lumy
keyPassword=...
```

`storeFile` 相对于 `.local/`。也可以用环境变量 `LUMY_KEYSTORE_FILE`、`LUMY_KEYSTORE_PASSWORD`、`LUMY_KEY_ALIAS`、`LUMY_KEY_PASSWORD` 提供（方便 CI，`LUMY_KEYSTORE_FILE` 相对于项目根目录）。两者都没有时 release 包退回 debug 签名，只适合侧载测试，不能用于发布。

运行单元测试（core、net 和服务器模块，纯 JVM）：

```bash
./gradlew :core:test :net:test :server-plex:test :server-jellyfin:test
```

### 发布版本

推送 `v<versionName>` 格式的 tag（如 `v0.1.0`）会触发 [release.yml](.github/workflows/release.yml)，构建签名好的 APK 并上传到 GitHub Release。发布说明取自 [CHANGELOG.md](CHANGELOG.md) 和 [CHANGELOG.zh-CN.md](CHANGELOG.zh-CN.md) 里对应版本的那一节，任意一份缺少都会失败。需要在仓库里配置这些 secrets：

| Secret | 值 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -i .local/release.jks` 的输出 |
| `RELEASE_KEYSTORE_PASSWORD` | 密钥库密码 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |

## 项目结构

```
core/          纯 Kotlin：媒体模型和 MediaServer 接口，与具体服务器无关
net/           纯 Kotlin：手机扫码输入的局域网接收服务、旧版 Android 补充的 TLS 根证书
server-plex/   纯 Kotlin：Plex 账号、服务器发现、连接探测和 API 实现（OkHttp + kotlinx.serialization）
server-jellyfin/ 纯 Kotlin：Jellyfin 和 Emby 的登录与 API 实现（两者接口同源，按 ServerKind 区分认证头）
app/           Android 应用：界面（View + RecyclerView，无 Leanback/Compose）和播放器
```

`app` 只通过 `core` 的 `MediaServer` 接口访问服务器，各服务器模块互不依赖。

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)（英文）。编译好的 APK 可以在 [Releases](https://github.com/hesier/lumy-player/releases) 页面下载。

## 致谢

Lumy Player 基于以下开源项目：

- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media)：播放引擎（Apache-2.0）
- [jellyfin-androidx-media](https://github.com/jellyfin/jellyfin-androidx-media)：预编译的 Media3 FFmpeg 音频解码扩展（GPL-3.0），内含 [FFmpeg](https://ffmpeg.org)
- [libass-android / ass-media](https://github.com/peerless2012/libass-android)：Media3 的 ASS / SSA 字幕渲染（MIT），基于 [libass](https://github.com/libass/libass)（ISC）
- [OkHttp](https://github.com/square/okhttp)、[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) 和 [Coil](https://github.com/coil-kt/coil)（Apache-2.0）
- [ZXing](https://github.com/zxing/zxing)：二维码生成（Apache-2.0）

## 许可证

本项目以 [GNU General Public License v3.0](LICENSE) 发布。

Lumy Player 与 Plex Inc.、Jellyfin 项目、Emby 均无关联，相关名称和商标归各自所有者。
