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
                value.length !in Defaults.MIN_STREAM_KEY_LENGTH..Defaults.MAX_STREAM_KEY_LENGTH ->
                    Result.failure(InvalidStreamKey("Stream key length is invalid"))
                else -> Result.success(StreamKey(value))
            }
        }

    }
}

class InvalidStreamKey(message: String) : IllegalArgumentException(message)
