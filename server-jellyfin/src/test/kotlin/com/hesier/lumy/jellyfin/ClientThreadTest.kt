package com.hesier.lumy.jellyfin

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.util.concurrent.Executors

class ClientThreadTest {
    private val web = MockWebServer()
    private val client = JellyfinClient(JellyfinClientInfo("Test", "1", "id", "Emu"))

    @After
    fun tearDown() = web.shutdown()

    @Test
    fun `response decoding leaves the calling UI thread`() {
        web.enqueue(MockResponse().setBody("\"payload\""))
        Executors.newSingleThreadExecutor { Thread(it, "test-ui") }.asCoroutineDispatcher().use { ui ->
            runBlocking(ui) {
                val caller = Thread.currentThread()
                val decoded = client.fetch<DecodingThread>(Request.Builder().url(web.url("/data")).build())
                assertSame(caller, Thread.currentThread())
                assertNotSame(caller, decoded.thread)
            }
        }
    }
}

@Serializable(with = DecodingThreadSerializer::class)
private data class DecodingThread(val thread: Thread)

private object DecodingThreadSerializer : KSerializer<DecodingThread> {
    override val descriptor = PrimitiveSerialDescriptor("DecodingThread", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DecodingThread {
        decoder.decodeString()
        return DecodingThread(Thread.currentThread())
    }

    override fun serialize(encoder: Encoder, value: DecodingThread) = encoder.encodeString(value.thread.name)
}
