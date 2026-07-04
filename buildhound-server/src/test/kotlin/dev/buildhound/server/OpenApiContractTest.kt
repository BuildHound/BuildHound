package dev.buildhound.server

import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.client.request.get
import io.ktor.server.application.plugin
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drift guard (plan 042): the API paths documented in `openapi.yaml` must exactly equal the live
 * `/v1` + `/health` route table registered by `buildHoundModule` — both directions. Adding or removing
 * a route without touching the spec fails here, so the published docs can never silently lie. Dashboard
 * static assets and the doc-serving routes themselves (`/`, `/docs`, `/openapi.yaml`, …) are not part
 * of the documented API surface and are excluded by the `/v1`-or-`/health` filter.
 */
class OpenApiContractTest {

    private data class Endpoint(val method: String, val path: String)

    @Test
    fun `the openapi spec paths equal the live route table`() = testApplication {
        lateinit var root: RoutingRoot
        application {
            buildHoundModule(ServerStores(InMemoryBuildStore(), InMemoryTokenStore()), RateLimits(0, 0, 0))
            root = plugin(RoutingRoot)
        }
        // Force the application (and its routing) to build.
        client.get("/health")

        val live = root.getAllRoutes()
            .mapNotNull { it.endpointOrNull() }
            .filter { it.path == "/health" || it.path.startsWith("/v1") }
            .toSet()

        val documented = parseSpecEndpoints()

        assertEquals(
            documented - live, emptySet(),
            "openapi.yaml documents routes that don't exist in the router",
        )
        assertEquals(
            live - documented, emptySet(),
            "the router has /v1 or /health routes missing from openapi.yaml",
        )
    }

    /** Reconstructs (METHOD, path) from a leaf route by walking its selector chain; null if no method. */
    private fun Route.endpointOrNull(): Endpoint? {
        var method: String? = null
        val segments = ArrayDeque<String>()
        var route: Route? = this
        while (route != null) {
            when (val selector = route.selector) {
                is HttpMethodRouteSelector -> method = selector.method.value
                is PathSegmentConstantRouteSelector -> segments.addFirst(selector.value)
                is PathSegmentParameterRouteSelector -> segments.addFirst("{${selector.name}}")
                else -> {} // rate-limit / root / trailing-slash selectors carry no path
            }
            route = route.parent
        }
        return method?.let { Endpoint(it, "/" + segments.joinToString("/")) }
    }

    private fun parseSpecEndpoints(): Set<Endpoint> {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("api/openapi.yaml")) {
            "api/openapi.yaml missing from the classpath (processResources copy)"
        }.use { it.readBytes().decodeToString() }

        val pathKey = Regex("""^ {2}(/\S*):\s*$""")
        val methodKey = Regex("""^ {4}(get|post|put|delete|patch):\s*$""")
        var inPaths = false
        var current: String? = null
        val out = mutableSetOf<Endpoint>()
        for (line in text.lines()) {
            if (line == "paths:") { inPaths = true; continue }
            if (!inPaths) continue
            if (line.isNotEmpty() && !line[0].isWhitespace()) { inPaths = false; continue }
            val p = pathKey.find(line)
            if (p != null) { current = p.groupValues[1]; continue }
            val m = methodKey.find(line)
            if (m != null) current?.let { out.add(Endpoint(m.groupValues[1].uppercase(), it)) }
        }
        return out
    }
}
