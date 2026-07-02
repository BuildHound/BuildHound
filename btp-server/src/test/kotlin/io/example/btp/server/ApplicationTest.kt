package io.example.btp.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    private val minimalPayload = """
        {
          "schemaVersion": 1,
          "buildId": "11111111-2222-3333-4444-555555555555",
          "startedAt": 1751450000000,
          "finishedAt": 1751450042000,
          "outcome": "SUCCESS",
          "requestedTasks": ["build"],
          "mode": "ci"
        }
    """.trimIndent()

    @Test
    fun `health endpoint responds ok`() = testApplication {
        application { btpModule() }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun `ingest accepts a schema v1 payload and dedupes on buildId`() = testApplication {
        application { btpModule() }

        val first = client.post("/v1/builds") {
            contentType(ContentType.Application.Json)
            setBody(minimalPayload)
        }
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertTrue(first.bodyAsText().contains("accepted"))

        val second = client.post("/v1/builds") {
            contentType(ContentType.Application.Json)
            setBody(minimalPayload)
        }
        assertEquals(HttpStatusCode.Accepted, second.status)
        assertTrue(second.bodyAsText().contains("duplicate"))
    }

    @Test
    fun `ingest rejects malformed payloads`() = testApplication {
        application { btpModule() }

        val response = client.post("/v1/builds") {
            contentType(ContentType.Application.Json)
            setBody("""{"not": "a build payload"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `ingest rejects payloads from a newer major schema`() = testApplication {
        application { btpModule() }

        val response = client.post("/v1/builds") {
            contentType(ContentType.Application.Json)
            setBody(minimalPayload.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }
}
