package com.loopcast.domain

import java.net.URI
import java.net.URISyntaxException

data class RtmpEndpoint(val baseUrl: String, val key: StreamKey) {
    fun fullUrl(): String {
        val separator = baseUrl.indexOf('?')
        return if (separator < 0) {
            baseUrl.trimEnd('/') + "/" + key.reveal()
        } else {
            val path = baseUrl.substring(0, separator).trimEnd('/')
            path + "/" + key.reveal() + baseUrl.substring(separator)
        }
    }

    override fun toString(): String = "RtmpEndpoint(baseUrl=${safeBaseUrl()}, key=$key)"

    private fun safeBaseUrl(): String = runCatching { URI(baseUrl).host }.getOrNull() ?: baseUrl

    companion object {
        const val YOUTUBE_RTMPS = "rtmps://a.rtmp.youtube.com:443/live2"
        const val YOUTUBE_RTMP = "rtmp://a.rtmp.youtube.com/live2"
        const val YOUTUBE_BACKUP_RTMPS = "rtmps://b.rtmp.youtube.com:443/live2?backup=1"

        fun parseFromUserInput(url: String, key: String): Result<RtmpEndpoint> {
            val trimmedUrl = url.trim()
            val trimmedKey = key.trim()
            val uri = try {
                URI(trimmedUrl)
            } catch (_: URISyntaxException) {
                return Result.failure(MalformedUrl)
            }
            if (uri.scheme != "rtmp" && uri.scheme != "rtmps") {
                return Result.failure(InvalidScheme)
            }
            if (uri.host.isNullOrBlank() || uri.path.isNullOrBlank()) {
                return Result.failure(MalformedUrl)
            }

            val pathParts = uri.path.trim('/').split('/')
            val pastedKeyText = pathParts.lastOrNull()?.takeIf { pathParts.size > 1 }
            val pastedKey = pastedKeyText?.let { StreamKey.parse(it).getOrNull() }
            if (pastedKeyText != null && pastedKey == null && trimmedKey.isBlank()) {
                return Result.failure(InvalidKey)
            }
            val parsedKey = if (pastedKey != null) pastedKey else {
                if (trimmedKey.isBlank()) return Result.failure(MissingKey)
                StreamKey.parse(trimmedKey).getOrElse { return Result.failure(InvalidKey) }
            }
            val basePath = if (pastedKey != null) {
                "/" + pathParts.dropLast(1).joinToString("/")
            } else {
                uri.path
            }
            val base = buildBaseUrl(uri, basePath)
            return Result.success(RtmpEndpoint(base, parsedKey))
        }

        private fun buildBaseUrl(uri: URI, path: String): String {
            val authority = buildString {
                append(uri.scheme)
                append("://")
                append(uri.rawAuthority)
            }
            return authority + path + (uri.rawQuery?.let { "?$it" } ?: "")
        }
    }
}

sealed class RtmpEndpointInputError(message: String) : IllegalArgumentException(message)
data object InvalidScheme : RtmpEndpointInputError("Only rtmp:// and rtmps:// URLs are supported")
data object MissingKey : RtmpEndpointInputError("Stream key is missing")
data object InvalidKey : RtmpEndpointInputError("Stream key is invalid")
data object MalformedUrl : RtmpEndpointInputError("RTMP URL is malformed")
