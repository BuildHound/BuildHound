package dev.buildhound.gradle

private const val HEX_RADIX = 16

/** Lowercase hexadecimal without signed-byte padding artifacts. */
internal fun ByteArray.toLowerHex(): String =
    joinToString("") { byte -> byte.toUByte().toString(HEX_RADIX).padStart(2, '0') }
