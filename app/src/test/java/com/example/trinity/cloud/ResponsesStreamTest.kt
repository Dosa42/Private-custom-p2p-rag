package com.example.trinity.cloud

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ResponsesStreamTest {
    @Test
    fun preservesAllCallsAndReasoningFromCompletedStream() = runBlocking {
        val source = """
            : keepalive

            event: response.output_item.done
            data: {"type":"response.output_item.done","output_index":2,"item":{"type":"function_call","call_id":"c2","name":"trinity_sync","arguments":"{}"}}

            data: {"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","encrypted_content":"opaque"}}

            data: {"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","call_id":"c1","name":"trinity_query","arguments":"{\"query\":\"héllo κ\"}"}}

            data: {"type":"response.completed","response":{"id":"r1","status":"completed"}}

        """.trimIndent()
        val output = readResponseEvents(source.reader().buffered())
        assertEquals(3, output.length())
        assertEquals("opaque", output.getJSONObject(0).getString("encrypted_content"))
        assertEquals("c1", output.getJSONObject(1).getString("call_id"))
        assertEquals("héllo κ", JSONObject(output.getJSONObject(1).getString("arguments")).getString("query"))
        assertEquals("c2", output.getJSONObject(2).getString("call_id"))
    }

    @Test
    fun completedResponseCanCarryCanonicalOutput() = runBlocking {
        val output = readResponseEvents("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Complete\"}]}]}}\n\n".reader().buffered())
        assertEquals("Complete", output.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun truncatedOrFailedStreamsNeverBecomeSuccessfulAnswers() = runBlocking {
        val invalid = listOf(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n",
            "data: [DONE]\n\n",
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"Quota exhausted\"}}}\n\n",
            "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n"
        )
        invalid.forEach { stream ->
            try {
                readResponseEvents(stream.reader().buffered())
                fail("A noncompleted stream must fail")
            } catch (e: IOException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }
}
