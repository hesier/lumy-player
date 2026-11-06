package com.hesier.lumy.ui.player

/** Routes legacy test probes to the feature that now owns each state value. */
private fun PlayerActivity.testFieldOwner(name: String): Any = when (name) {
    "version", "quality", "plan", "speed" -> javaClass.getDeclaredField("selection").apply { isAccessible = true }.get(this)
    "scrubTarget" -> javaClass.getDeclaredField("seek").apply { isAccessible = true }.get(this)
    "passthroughEncoding" -> javaClass.getDeclaredField("info").apply { isAccessible = true }.get(this)
    else -> this
}

internal fun PlayerActivity.readPlaybackField(name: String): Any? {
    val owner = testFieldOwner(name)
    return owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}

internal fun PlayerActivity.writePlaybackField(name: String, value: Any?) {
    val owner = testFieldOwner(name)
    owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
}
