package com.hesier.lumy.data.backup

import android.content.Context
import com.hesier.lumy.net.BackupCipher

/** File encoding is independent of the validated backup payload. */
internal class BackupArchive(context: Context) {
    private val store = BackupStore(context)

    fun export(password: CharArray): ByteArray {
        val plain = store.export()
        if (password.all { it.isWhitespace() }) return plain
        return try { BackupCipher.encrypt(plain, password) } finally { plain.fill(0) }
    }

    fun inspect(bytes: ByteArray, password: CharArray = charArrayOf()): BackupData {
        if (!BackupCipher.isEncrypted(bytes)) return store.inspect(bytes)
        val plain = BackupCipher.decrypt(bytes, password)
        return try { store.inspect(plain) } finally { plain.fill(0) }
    }
}
