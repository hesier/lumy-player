# HTTPS on Android 5.0–7.0

English | [简体中文](tls-compatibility.zh-CN.md)

Android releases before 7.1.1 do not normally include ISRG Root X1. A valid Let's Encrypt chain ending at this root can therefore fail with `CertPathValidatorException: Trust anchor for certification path not found`.

For API 21–24, the Android app adds the bundled official ISRG Root X1 to the system trust anchors in its shared OkHttp client. The platform still performs certificate path validation and OkHttp still verifies the hostname. Existing system roots remain available. This covers Plex / Jellyfin / Emby API requests, Coil image requests and the Media3 OkHttp data source. API 25+ keeps its normal platform trust configuration.

The root is loaded locally; no certificate is downloaded at runtime. We do not trust a server-provided certificate as a new root, disable certificate validation, or change the process-wide TLS defaults. Self-signed leaves, expired leaves, hostname mismatches and incomplete chains are not accepted by this compatibility fix.

## Bundled certificate provenance

- Resource: `app/src/main/res/raw/isrg_root_x1.pem`
- Official listing: https://letsencrypt.org/certificates/
- Source: https://github.com/letsencrypt/website/blob/main/static/certs/isrgrootx1.pem
- DER SHA-256: `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6`

Review the official root program guidance when updating this resource. Do not substitute a site's leaf or intermediate certificate for the root.

## Validation

`TlsTrustTest` (in the `net` module) uses local TLS servers and a simulated old trust store. It verifies additional CA support through an intermediate, retained existing CAs, and rejection of an unknown self-signed leaf, wrong hostname, expired leaf and missing intermediate. It also rejects adding a leaf as a CA root.

JVM tests do not emulate Android's TLS provider, so the fix was also checked on a Hisense VIDAA TV (Android 5.1.1 / API 22): an Emby server with a Let's Encrypt certificate failed with the error above before the fix, and signs in and plays after it.
