package com.cjcrafter.openai.completions

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*

/**
 * The `CompletionResponse` contains all the data returned by the OpenAI Completions
 * API. For most use cases, [CompletionResponse.get] (passing 0 to the index argument)
 * is all you need.
 *
 * @property id      The unique id for your request.
 * @property created The Unix timestamp (measured in seconds since 00:00:00 UTC on Junuary 1, 1970) when the API response was created.
 * @property model   The model used to generate the completion.
 * @property choices The generated completion(s).
 * @property usage   The number of tokens used in this request/response.
 * @constructor Create Completion response (for internal usage)
 */
data class CompletionResponse(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<CompletionChoice>,
    val usage: CompletionUsage
) {

    /**
     * Returns the [Instant] time that the OpenAI Completion API sent this response.
     * The time is measured as a unix timestamp (measured in seconds since
     * 00:00:00 UTC on January 1, 1970).
     *
     * Note that users expect time to be measured in their timezone, so
     * [getZonedTime] is preferred.
     *
     * @return The instant the api created this response.
     * @see getZonedTime
     */
    fun getTime(): Instant {
        return Instant.ofEpochSecond(created)
    }

    /**
     * Returns the time-zoned instant that the OpenAI Completion API sent this
     * response. By default, this method uses the system's timezone.
     *
     * @param timezone The user's timezone.
     * @return The timezone adjusted date time.
     * @see TimeZone.getDefault
     */
    @JvmOverloads
    fun getZonedTime(timezone: ZoneId = TimeZone.getDefault().toZoneId()): ZonedDateTime {
        return ZonedDateTime.ofInstant(getTime(), timezone)
    }

    /**
     * Shorthand for accessing the generated messages (shorthand for
     * [CompletionResponse.choices]).
     *
     * @param index The index of the message.
     * @return The generated [CompletionChoice] at the index.
     */
    operator fun get(index: Int): CompletionChoice {
        return choices[index]
    }
}
