package com.loopcast.domain

@JvmInline
value class StreamKey private constructor(private val raw: String) {
    fun reveal(): String = raw

    override fun toString(): String = "••••" + raw.takeLast(4)

    companion object {
        fun parse(input: String): Result<StreamKey> {
            val value = input.trim()
            return when {
                value.isBlank() -> Result.failure(InvalidStreamKey("Stream key is blank"))
                value.any(Char::isWhitespace) ->
                    Result.failure(InvalidStreamKey("Stream key contains whitespace"))
                value.length !in MIN_LENGTH..MAX_LENGTH ->
                    Result.failure(InvalidStreamKey("Stream key length is invalid"))
                else -> Result.success(StreamKey(value))
            }
        }

        private const val MIN_LENGTH = 8
        private const val MAX_LENGTH = 128
    }
}

class InvalidStreamKey(message: String) : IllegalArgumentException(message)
