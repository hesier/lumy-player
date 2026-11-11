package com.hesier.lumy.data

/** The authorizing account is retained only for Home selection, never media requests. */
data class PlexHomeIdentity(
    val accountToken: String,
    val userId: String,
    val userName: String,
    val thumb: String? = null,
)
