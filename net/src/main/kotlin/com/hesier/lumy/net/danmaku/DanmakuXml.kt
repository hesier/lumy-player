package com.hesier.lumy.net.danmaku

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import javax.xml.parsers.SAXParserFactory

/** 禁止 DTD 和外部实体；仅识别 Bilibili 的 i/d/p 结构。 */
internal fun parseDanmakuXml(bytes: ByteArray, records: CommentCollector) {
    val handler = object : DefaultHandler2() {
        var rootSeen = false
        var properties: String? = null
        var content: StringBuilder? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            if (!rootSeen) {
                if (qName != "i") throw SAXException("Invalid root")
                rootSeen = true
            }
            if (qName == "d") {
                properties = attrs.getValue("p")
                content = StringBuilder()
            }
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            content?.append(chars, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName == "d") {
                records.add(properties, content?.toString())
                content = null
            }
        }

        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTD forbidden") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("Entity forbidden") }
    }
    val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
    reader.contentHandler = handler
    reader.errorHandler = handler
    reader.entityResolver = handler
    reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
    reader.parse(InputSource(bytes.inputStream()))
    if (!handler.rootSeen) throw DanmakuException(DanmakuFailure.INVALID_RESPONSE)
}
