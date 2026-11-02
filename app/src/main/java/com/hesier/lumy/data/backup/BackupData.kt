package com.hesier.lumy.data.backup

/** Validated preview; the payload cannot be modified after inspection. */
class BackupData internal constructor(
    val createdAt: Long,
    val appVersion: String,
    val serverCount: Int,
    internal val payload: String,
)

data class RestoreOptions(
    val settings: Boolean = true,
    val servers: Boolean = true,
    val deviceSettings: Boolean = false,
)
