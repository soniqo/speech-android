package audio.soniqo.speech

import java.net.HttpURLConnection
import java.net.URL

/**
 * Implements the LLM vtable for speech-core via the Anthropic Messages API.
 *
 * Called from the native pipeline worker thread via JNI. The [chat] method
 * blocks until the full streaming response is delivered, feeding tokens back
 * to speech-core via [NativeBridge.nativeDeliverLlmToken].
 */
internal class LlmBridge(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
) {

    @Volatile private var connection: HttpURLConnection? = null

    /**
     * Called from native when speech-core needs an LLM response.
     * Blocks until the full response is streamed.
     */
    fun chat(
        roles: Array<String>,
        contents: Array<String>,
        onTokenFnPtr: Long,
        tokenCtxPtr: Long,
    ) {
        // Separate system message from conversation turns
        val sysMessages = roles.indices.filter { roles[it] == "system" }
        val system = if (sysMessages.isNotEmpty()) {
            contents[sysMessages.last()]
        } else {
            systemPrompt
        }

        val turnIndices = roles.indices.filter { roles[it] != "system" }
        val messagesJson = buildString {
            append("[")
            turnIndices.forEachIndexed { idx, i ->
                if (idx > 0) append(",")
                append("""{"role":"${roles[i]}","content":${jsonString(contents[i])}}""")
            }
            append("]")
        }

        val body = """{"model":${jsonString(model)},"max_tokens":1024,"stream":true,""" +
                   """"system":${jsonString(system)},"messages":$messagesJson}"""

        try {
            val conn = (URL("https://api.anthropic.com/v1/messages").openConnection()
                    as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 120_000
            }
            connection = conn

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                NativeBridge.nativeDeliverLlmToken(onTokenFnPtr, tokenCtxPtr, "[Error: $err]", true)
                return
            }

            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (!line.startsWith("data: ")) return@forEach
                    val data = line.removePrefix("data: ")
                    if (data == "[DONE]") return@forEach
                    val token = extractTextDelta(data) ?: return@forEach
                    NativeBridge.nativeDeliverLlmToken(onTokenFnPtr, tokenCtxPtr, token, false)
                }
            }
            NativeBridge.nativeDeliverLlmToken(onTokenFnPtr, tokenCtxPtr, "", true)

        } catch (_: Exception) {
            NativeBridge.nativeDeliverLlmToken(onTokenFnPtr, tokenCtxPtr, "", true)
        } finally {
            connection = null
        }
    }

    fun cancel() {
        connection?.disconnect()
        connection = null
    }

    // Parse {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
    private fun extractTextDelta(json: String): String? {
        if (!json.contains("text_delta")) return null
        val match = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json) ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"'  -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}
