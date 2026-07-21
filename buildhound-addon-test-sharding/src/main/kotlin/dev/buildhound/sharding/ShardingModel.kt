package dev.buildhound.sharding

import java.io.File
import kotlinx.serialization.Serializable

/**
 * The `extensions["testSharding"]` payload block (plan 040). `appliedFilter=false` records a
 * run-all fallback (any fetch failure), so the server can see when sharding degraded.
 */
@Serializable
data class TestShardingExtension(
    val schemaVersion: Int = SCHEMA_VERSION,
    val shardPlanId: String? = null,
    val shardIndex: Int,
    val shardTotal: Int,
    val appliedFilter: Boolean,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val EXTENSION_KEY: String = "testSharding"
    }
}

/** Client copies of the server's plan contract (`POST /v1/addons/test-sharding/plan`, plan 040). */
@Serializable
data class ShardPlanRequest(
    val reference: String,
    val index: Int,
    val total: Int,
    val suites: List<String>,
)

@Serializable
data class ShardPlanResponse(
    val shardPlanId: String,
    val index: Int,
    val classes: List<String>,
    val assigned: List<String> = emptyList(),
)

/**
 * Deterministic test-suite discovery (plan 040): every top-level compiled test class FQCN under the
 * `Test` tasks' `testClassesDirs`. Inner classes (`$`) and `module-info`/`package-info` are
 * excluded; sorted so the union a shard sends is stable across jobs (the join key stays
 * `module/class` at the server). Gradle-free, so it unit-tests without a build.
 */
object SuiteDiscovery {
    fun discover(classDirs: List<File>): List<String> =
        classDirs
            .filter { it.isDirectory }
            .flatMap { root ->
                root
                    .walkTopDown()
                    .filter {
                        it.isFile &&
                            it.extension == "class" &&
                            '$' !in it.name &&
                            !it.name.startsWith("module-info") &&
                            !it.name.startsWith("package-info")
                    }
                    .map {
                        it.relativeTo(root)
                            .path
                            .removeSuffix(".class")
                            .replace(File.separatorChar, '.')
                    }
            }
            .distinct()
            .sorted()
}
