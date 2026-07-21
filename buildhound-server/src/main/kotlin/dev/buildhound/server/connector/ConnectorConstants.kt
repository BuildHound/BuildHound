package dev.buildhound.server.connector

internal const val HTTP_SUCCESS_MIN = 200
internal const val HTTP_SUCCESS_MAX = 299
internal const val HTTP_RATE_LIMITED = 429
internal const val HTTP_SERVER_ERROR_MIN = 500
internal const val HTTP_SERVER_ERROR_MAX = 599
internal const val CONNECT_TIMEOUT_MS = 5_000L
internal const val REQUEST_TIMEOUT_MS = 15_000L
internal const val DEFAULT_MAX_RETRIES = 3
internal const val INITIAL_BACKOFF_MS = 5_000L
internal const val MAX_BACKOFF_MS = 120_000L
