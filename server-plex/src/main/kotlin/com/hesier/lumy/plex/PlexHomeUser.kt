package com.hesier.lumy.plex

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class PlexHomeUser(
    val id: String,
    val title: String,
    val thumb: String? = null,
    val protected: Boolean = false,
    val admin: Boolean = false,
    val restricted: Boolean = false,
)

internal object PlexHomeXml {
    fun users(body: String): List<PlexHomeUser> {
        val root = root(body)
        if (root.tagName != "MediaContainer") throw IOException("Invalid Plex Home users response")
        val users = root.getElementsByTagName("User")
        return (0 until users.length).map { index ->
            val user = users.item(index) as Element
            val id = user.getAttribute("id").takeIf { it.isNotBlank() }
                ?: throw IOException("Plex Home user has no ID")
            PlexHomeUser(
                id = id,
                title = user.getAttribute("title"),
                thumb = user.getAttribute("thumb").takeIf { it.isNotBlank() },
                protected = user.flag("protected"),
                admin = user.flag("admin"),
                restricted = user.flag("restricted"),
            )
        }
    }

    fun token(body: String): String = root(body).getAttribute("authenticationToken")
        .takeIf { it.isNotBlank() } ?: throw IOException("Plex Home switch returned no account token")

    private fun root(body: String): Element {
        val builder = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        // Account responses never need external resources, including external DTDs.
        builder.setEntityResolver { _, _ -> throw SAXException("External XML entities are unsupported") }
        return builder.parse(InputSource(StringReader(body))).documentElement
    }

    private fun Element.flag(name: String): Boolean =
        getAttribute(name).let { it == "1" || it.equals("true", ignoreCase = true) }
}
