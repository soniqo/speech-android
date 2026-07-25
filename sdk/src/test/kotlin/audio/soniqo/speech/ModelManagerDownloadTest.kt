package audio.soniqo.speech

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Drives [ModelManager.downloadFile] against a local MockWebServer:
 * retry, resume via Range, timeout, and redirect handling.
 */
class ModelManagerDownloadTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun download(
        dest: File,
        path: String = "/model.onnx",
        maxRetries: Int = 3,
        client: OkHttpClient = this.client,
        onBytes: (downloaded: Long, fileTotal: Long) -> Unit = { _, _ -> },
    ) = ModelManager.downloadFile(
        url = server.url(path).toString(),
        dest = dest,
        client = client,
        maxRetries = maxRetries,
        retryDelayMs = 10,
        onBytes = onBytes,
    )

    @Test
    fun `successful download writes body to destination`() {
        val content = "hello world model data"
        server.enqueue(MockResponse().setBody(content))

        val dest = File(tmpDir.root, "model.onnx")
        download(dest)

        assertEquals(content, dest.readText())
        assertFalse(File(tmpDir.root, "model.onnx.tmp").exists())
    }

    @Test
    fun `progress callback reports downloaded bytes and file total`() {
        val content = ByteArray(16384) { it.toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(content)))

        val dest = File(tmpDir.root, "model.onnx")
        val reported = mutableListOf<Pair<Long, Long>>()
        download(dest) { bytes, total -> reported.add(bytes to total) }

        assertEquals(0L, reported.first().first)
        assertEquals(content.size.toLong(), reported.last().first)
        assertTrue(reported.all { it.second == content.size.toLong() })
    }

    @Test
    fun `retries on server error until success`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("ok"))

        val dest = File(tmpDir.root, "model.onnx")
        download(dest)

        assertEquals("ok", dest.readText())
        assertEquals(3, server.requestCount)
    }

    @Test(expected = IOException::class)
    fun `throws after max retries exhausted`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }

        download(File(tmpDir.root, "model.onnx"), maxRetries = 3)
    }

    @Test
    fun `preserves partial tmp file after all retries fail`() {
        // DISCONNECT_DURING_RESPONSE_BODY sends half the declared body, so
        // every attempt fails mid-stream with some bytes on disk. The .tmp
        // must survive: the next ensureModels() call resumes from it.
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setBody("ABCDEFGHIJKLMNOP")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
        }

        val dest = File(tmpDir.root, "model.onnx")
        try {
            download(dest, maxRetries = 2)
            fail("expected IOException")
        } catch (_: IOException) {}

        assertFalse("final file should not exist on failure", dest.exists())
        assertTrue(
            "partial .tmp should be preserved for resume",
            File(tmpDir.root, "model.onnx.tmp").exists(),
        )
    }

    @Test
    fun `resume sends Range header and appends to existing tmp`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody("EFGHIJKLMNOP")
                .setHeader("Content-Range", "bytes 4-15/16")
        )

        val dest = File(tmpDir.root, "model.onnx")
        File(tmpDir.root, "model.onnx.tmp").writeText("ABCD")

        val totals = mutableListOf<Pair<Long, Long>>()
        download(dest) { bytes, total -> totals.add(bytes to total) }

        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        assertEquals("ABCDEFGHIJKLMNOP", dest.readText())
        // On a 206 the response length is only the remaining range; the
        // reported file total must include the bytes already on disk.
        assertEquals(16L to 16L, totals.last())
    }

    @Test
    fun `resumed download that ends short is rejected`() {
        // The quiet case: the server returns fewer bytes than the requested
        // range and closes cleanly, so the transfer itself looks successful. A
        // truncated model keeps genuine leading bytes, so the header check
        // passes too and it is cached as valid — the failure only surfaces
        // later, when the runtime cannot parse it.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody("EFGH")  // 4 bytes, but the range promises 12
                .setHeader("Content-Range", "bytes 4-15/16")
        )

        val dest = File(tmpDir.root, "model.onnx")
        File(tmpDir.root, "model.onnx.tmp").writeText("ABCD")

        // One attempt: a retry would resume again and append more, which is
        // the right behaviour but not what this test is pinning down.
        val error = assertThrows(IOException::class.java) { download(dest, maxRetries = 1) }
        assert(error.message!!.contains("Incomplete download")) { error.message!! }
        assertFalse("a short resume must not be published", dest.exists())
    }

    @Test
    fun `206 answering from the wrong offset restarts rather than appending`() {
        // Seen against a real CDN: we ask for bytes=4- and get a 206 whose
        // body starts at 0. Appending it produced a file larger than the real
        // one, with a valid header and a corrupt middle — which a
        // minimum-size check cannot catch.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody("ABCDEFGHIJKLMNOP")
                .setHeader("Content-Range", "bytes 0-15/16")
        )

        val dest = File(tmpDir.root, "model.onnx")
        File(tmpDir.root, "model.onnx.tmp").writeText("ABCD")

        download(dest)

        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
        assertEquals("ABCDEFGHIJKLMNOP", dest.readText())
        assertEquals(16L, dest.length())
    }

    @Test
    fun `complete resume is still accepted`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody("EFGHIJKLMNOP")
                .setHeader("Content-Range", "bytes 4-15/16")
        )

        val dest = File(tmpDir.root, "model.onnx")
        File(tmpDir.root, "model.onnx.tmp").writeText("ABCD")
        download(dest)

        assertEquals("ABCDEFGHIJKLMNOP", dest.readText())
    }

    @Test
    fun `http failure message includes status code`() {
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            download(File(tmpDir.root, "model.onnx"), maxRetries = 1)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue("message was: ${e.message}", e.message!!.contains("HTTP 404"))
        }
    }

    @Test
    fun `follows redirects`() {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", server.url("/actual"))
        )
        server.enqueue(MockResponse().setBody("redirected content"))

        val dest = File(tmpDir.root, "model.onnx")
        download(dest)

        assertEquals("redirected content", dest.readText())
    }

    @Test
    fun `read timeout triggers retry`() {
        val shortTimeoutClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        // 1 byte per 5 s starves the read timeout on the first attempt.
        server.enqueue(
            MockResponse()
                .setBody("partial")
                .throttleBody(1, 5, TimeUnit.SECONDS)
        )
        server.enqueue(MockResponse().setBody("ok"))

        val dest = File(tmpDir.root, "model.onnx")
        download(dest, client = shortTimeoutClient)

        assertEquals("ok", dest.readText())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `large file download preserves all bytes`() {
        val size = 1_048_576
        val data = ByteArray(size) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(data)))

        val dest = File(tmpDir.root, "large.onnx")
        download(dest, path = "/large.onnx")

        assertEquals(size.toLong(), dest.length())
        assertArrayEquals(data, dest.readBytes())
    }
}
