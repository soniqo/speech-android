package audio.soniqo.speech

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Unit tests for model download logic: retries, resume, validation.
 * Uses OkHttp MockWebServer — no device or network needed.
 */
class ModelManagerDownloadTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -- helpers --

    private fun downloadFile(
        url: String,
        dest: File,
        maxRetries: Int = 3,
        onBytes: (Long) -> Unit = {},
    ) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        var lastException: IOException? = null

        for (attempt in 1..maxRetries) {
            try {
                val existingBytes = if (tmp.exists()) tmp.length() else 0L
                val rb = Request.Builder().url(url)
                if (existingBytes > 0) rb.header("Range", "bytes=$existingBytes-")

                val response = client.newCall(rb.build()).execute()
                if (!response.isSuccessful && response.code != 206) {
                    response.close()
                    throw IOException("HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("Empty response")
                val contentLength = body.contentLength()
                val isResume = response.code == 206

                FileOutputStream(tmp, isResume).use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(4096)
                        var total = if (isResume) existingBytes else 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            total += n
                            onBytes(total)
                        }
                    }
                }
                response.close()

                if (!isResume && contentLength > 0 && tmp.length() != contentLength) {
                    throw IOException("Incomplete: got ${tmp.length()}, expected $contentLength")
                }

                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                return
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) Thread.sleep(100L * attempt)
            }
        }

        tmp.delete()
        throw IOException("Failed after $maxRetries attempts: ${lastException?.message}", lastException)
    }

    // -- tests --

    @Test
    fun `successful download writes correct content`() {
        val content = "hello world model data"
        server.enqueue(MockResponse().setBody(content))

        val dest = File(tmpDir.root, "model.onnx")
        downloadFile(server.url("/model.onnx").toString(), dest)

        assertTrue(dest.exists())
        assertEquals(content, dest.readText())
    }

    @Test
    fun `progress callback reports bytes`() {
        val content = ByteArray(16384) { it.toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(content)))

        val dest = File(tmpDir.root, "model.onnx")
        val reported = mutableListOf<Long>()
        downloadFile(server.url("/model.onnx").toString(), dest) { reported.add(it) }

        assertTrue(reported.isNotEmpty())
        assertEquals(content.size.toLong(), reported.last())
    }

    @Test
    fun `retries on server error`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("ok"))

        val dest = File(tmpDir.root, "model.onnx")
        downloadFile(server.url("/model.onnx").toString(), dest)

        assertTrue(dest.exists())
        assertEquals("ok", dest.readText())
        assertEquals(3, server.requestCount)
    }

    @Test(expected = IOException::class)
    fun `throws after max retries exhausted`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val dest = File(tmpDir.root, "model.onnx")
        downloadFile(server.url("/model.onnx").toString(), dest, maxRetries = 3)
    }

    @Test
    fun `cleans up tmp file after all retries fail`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val dest = File(tmpDir.root, "model.onnx")
        try {
            downloadFile(server.url("/model.onnx").toString(), dest, maxRetries = 2)
        } catch (_: IOException) {}

        assertFalse(dest.exists())
        assertFalse(File(tmpDir.root, "model.onnx.tmp").exists())
    }

    @Test
    fun `resume sends Range header for partial file`() {
        // First attempt: server drops after partial content
        val fullContent = "ABCDEFGHIJKLMNOP"
        server.enqueue(MockResponse().setResponseCode(500).setBody("ABCD"))

        // Second attempt: server returns remaining bytes
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody("EFGHIJKLMNOP")
                .setHeader("Content-Range", "bytes 4-15/16")
        )

        // Pre-create a partial tmp file simulating interrupted download
        val dest = File(tmpDir.root, "model.onnx")
        val tmp = File(tmpDir.root, "model.onnx.tmp")
        tmp.writeText("ABCD")

        // Should resume from byte 4
        downloadFile(server.url("/model.onnx").toString(), dest)

        assertTrue(dest.exists())
        assertEquals("ABCDEFGHIJKLMNOP", dest.readText())

        // Verify second request used Range header
        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertEquals("bytes=4-", req2.getHeader("Range"))
    }

    @Test(expected = IOException::class)
    fun `rejects HTTP 404`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        val dest = File(tmpDir.root, "model.onnx")
        downloadFile(server.url("/missing.onnx").toString(), dest)
    }

    @Test
    fun `handles redirect`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/actual")))
        server.enqueue(MockResponse().setBody("redirected content"))

        val dest = File(tmpDir.root, "model.onnx")
        downloadFile(server.url("/redirect").toString(), dest)

        assertEquals("redirected content", dest.readText())
    }
}
