package com.cjcrafter.openai

import com.cjcrafter.openai.gson.ChatChoiceChunkAdapter
import com.cjcrafter.openai.chat.*
import com.cjcrafter.openai.completions.CompletionRequest
import com.cjcrafter.openai.completions.CompletionResponse
import com.cjcrafter.openai.completions.CompletionResponseChunk
import com.cjcrafter.openai.exception.OpenAIError
import com.cjcrafter.openai.exception.WrappedIOError
import com.cjcrafter.openai.gson.ChatUserAdapter
import com.cjcrafter.openai.gson.FinishReasonAdapter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.lang.IllegalStateException
import java.util.function.Consumer

/**
 * The `OpenAI` class contains all the API calls to OpenAI's endpoint. Whether
 * you are working with images, chat, or completions, you need to have an
 * `OpenAI` instance to make the API requests.
 *
 * To get your API key:
 * 1. Log in to your account: Go to [https://www.openai.com/](openai.com) and
 * log in.
 * 2. Access the API dashboard: After logging in, click on the "API" tab.
 * 3. Choose a subscription plan: Select a suitable plan based on your needs
 * and complete the payment process.
 * 4. Obtain your API key: After subscribing to a plan, you will be redirected
 * to the API dashboard, where you can find your unique API key. Copy and store it securely.
 *
 * @property apiKey Your OpenAI API key. It starts with `"sk-"` (without the quotes).
 * @property organization If you belong to multiple organizations, specify which one to use (else `null`).
 * @property client Controls proxies, timeouts, etc.
 * @constructor Create a ChatBot for responding to requests.
 */
class OpenAI @JvmOverloads constructor(
    private val apiKey: String,
    private val organization: String? = null,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = createGson()

    private fun buildRequest(request: Any, endpoint: String): Request {
        val json = gson.toJson(request)
        val body: RequestBody = json.toRequestBody(mediaType)
        return Request.Builder()
            .url("https://api.openai.com/v1/$endpoint")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .apply { if (organization != null) addHeader("OpenAI-Organization", organization) }
            .post(body).build()
    }

    /**
     * Create completion
     *
     * @param request
     * @return
     * @since 1.3.0
     */
    @Throws(OpenAIError::class)
    fun createCompletion(request: CompletionRequest): CompletionResponse {
        @Suppress("DEPRECATION")
        request.stream = false // use streamCompletion for stream=true
        val httpRequest = buildRequest(request, "completions")

        try {
            client.newCall(httpRequest).execute().use { response ->

                // Servers respond to API calls with json blocks. Since raw JSON isn't
                // very developer friendly, we wrap for easy data access.
                val rootObject = JsonParser.parseString(response.body!!.string()).asJsonObject
                if (rootObject.has("error"))
                    throw OpenAIError.fromJson(rootObject.get("error").asJsonObject)

                return gson.fromJson(rootObject, CompletionResponse::class.java)
            }
        } catch (ex: IOException) {
            // Wrap the IOException, so we don't need to catch multiple exceptions
            throw WrappedIOError(ex)
        }
    }

    /**
     * Helper method to call [streamCompletion].
     *
     * @param request    The input information for ChatGPT.
     * @param onResponse The method to call for each chunk.
     * @since 1.3.0
     */
    fun streamCompletionKotlin(request: CompletionRequest, onResponse: CompletionResponseChunk.() -> Unit) {
        streamCompletion(request, { it.onResponse() })
    }

    /**
     * This method does not block the thread. Method calls to [onResponse] are
     * not handled by the main thread. It is crucial to consider thread safety
     * within the context of your program.
     *
     * @param request    The input information for ChatGPT.
     * @param onResponse The method to call for each chunk.
     * @param onFailure  The method to call if the HTTP fails. This method will
     *                   not be called if OpenAI returns an error.
     * @see createCompletion
     * @see streamCompletionKotlin
     * @since 1.3.0
     */
    @JvmOverloads
    fun streamCompletion(
        request: CompletionRequest,
        onResponse: Consumer<CompletionResponseChunk>, // use Consumer instead of Kotlin for better Java syntax
        onFailure: Consumer<OpenAIError> = Consumer { it.printStackTrace() }
    ) {
        @Suppress("DEPRECATION")
        request.stream = true // use requestResponse for stream=false
        val httpRequest = buildRequest(request, "completions")

        client.newCall(httpRequest).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                onFailure.accept(WrappedIOError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.source()?.use { source ->
                    while (!source.exhausted()) {

                        // Parse the JSON string as a map. Every string starts
                        // with "data: ", so we need to remove that.
                        var jsonResponse = source.readUtf8Line() ?: continue
                        if (jsonResponse.isEmpty())
                            continue

                        // TODO comment
                        if (!jsonResponse.startsWith("data: ")) {
                            System.err.println(jsonResponse)
                            continue
                        }

                        jsonResponse = jsonResponse.substring("data: ".length)
                        if (jsonResponse == "[DONE]")
                            continue

                        val rootObject = JsonParser.parseString(jsonResponse).asJsonObject
                        if (rootObject.has("error"))
                            throw OpenAIError.fromJson(rootObject.get("error").asJsonObject)

                        val cache = gson.fromJson(rootObject, CompletionResponseChunk::class.java)
                        onResponse.accept(cache)
                    }
                }
            }
        })
    }

    /**
     * Blocks the current thread until OpenAI responds to https request. The
     * returned value includes information including tokens, generated text,
     * and stop reason. You can access the generated message through
     * [ChatResponse.choices].
     *
     * @param request The input information for ChatGPT.
     * @return The returned response.
     * @throws OpenAIError Invalid request/timeout/io/etc.
     */
    @Throws(OpenAIError::class)
    fun createChatCompletion(request: ChatRequest): ChatResponse {
        @Suppress("DEPRECATION")
        request.stream = false // use streamResponse for stream=true
        val httpRequest = buildRequest(request, "chat/completions")

        try {
            client.newCall(httpRequest).execute().use { response ->

                // Servers respond to API calls with json blocks. Since raw JSON isn't
                // very developer friendly, we wrap for easy data access.
                val rootObject = JsonParser.parseString(response.body!!.string()).asJsonObject
                if (rootObject.has("error"))
                    throw OpenAIError.fromJson(rootObject.get("error").asJsonObject)

                return gson.fromJson(rootObject, ChatResponse::class.java)
            }
        } catch (ex: IOException) {
            // Wrap the IOException, so we don't need to catch multiple exceptions
            throw WrappedIOError(ex)
        }
    }

    /**
     * This is a helper method that calls [streamChatCompletion], which lets you use
     * the generated tokens in real time (As ChatGPT generates them).
     *
     * This method does not block the thread. Method calls to [onResponse] are
     * not handled by the main thread. It is crucial to consider thread safety
     * within the context of your program.
     *
     * Usage:
     * ```
     *     val messages = mutableListOf("Write a poem".toUserMessage())
     *     val request = ChatRequest("gpt-3.5-turbo", messages)
     *     val bot = ChatBot(/* your key */)

     *     bot.streamResponseKotlin(request) {
     *         print(choices[0].delta)
     *
     *         // when finishReason != null, this is the last message (done generating new tokens)
     *         if (choices[0].finishReason != null)
     *             messages.add(choices[0].message)
     *     }
     * ```
     *
     * @param request    The input information for ChatGPT.
     * @param onResponse The method to call for each chunk.
     * @since 1.2.0
     */
    fun streamChatCompletionKotlin(request: ChatRequest, onResponse: ChatResponseChunk.() -> Unit) {
        streamChatCompletion(request, { it.onResponse() })
    }

    /**
     * Uses ChatGPT to generate tokens in real time. As ChatGPT generates
     * content, those tokens are sent in a stream in real time. This allows you
     * to update the user without long delays between their input and OpenAI's
     * response.
     *
     * For *"simpler"* calls, you can use [createChatCompletion] which will block
     * the thread until the entire response is generated.
     *
     * Instead of using the [ChatResponse], this method uses [ChatResponseChunk].
     * This means that it is not possible to retrieve the number of tokens from
     * this method,
     *
     * This method does not block the thread. Method calls to [onResponse] are
     * not handled by the main thread. It is crucial to consider thread safety
     * within the context of your program.
     *
     * @param request    The input information for ChatGPT.
     * @param onResponse The method to call for each chunk.
     * @param onFailure  The method to call if the HTTP fails. This method will
     *                   not be called if OpenAI returns an error.
     * @see createChatCompletion
     * @see streamChatCompletionKotlin
     * @since 1.2.0
     */
    @JvmOverloads
    fun streamChatCompletion(
        request: ChatRequest,
        onResponse: Consumer<ChatResponseChunk>, // use Consumer instead of Kotlin for better Java syntax
        onFailure: Consumer<WrappedIOError> = Consumer { it.printStackTrace() }
    ) {
        @Suppress("DEPRECATION")
        request.stream = true // use requestResponse for stream=false
        val httpRequest = buildRequest(request, "chat/completions")

        client.newCall(httpRequest).enqueue(object : Callback {
            var cache: ChatResponseChunk? = null

            override fun onFailure(call: Call, e: IOException) {
                onFailure.accept(WrappedIOError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.source()?.use { source ->
                    while (!source.exhausted()) {

                        // Parse the JSON string as a map. Every string starts
                        // with "data: ", so we need to remove that.
                        var jsonResponse = source.readUtf8Line() ?: continue
                        if (jsonResponse.isEmpty())
                            continue
                        jsonResponse = jsonResponse.substring("data: ".length)
                        if (jsonResponse == "[DONE]")
                            continue

                        val rootObject = JsonParser.parseString(jsonResponse).asJsonObject
                        if (rootObject.has("error"))
                            throw OpenAIError.fromJson(rootObject.get("error").asJsonObject)

                        if (cache == null)
                            cache = gson.fromJson(rootObject, ChatResponseChunk::class.java)
                        else
                            cache!!.update(rootObject)

                        onResponse.accept(cache!!)
                    }
                }
            }
        })
    }

    companion object {

        /**
         * Returns a `Gson` object that can be used to read/write .json files.
         * This can be used to save requests/responses to a file, so you can
         * keep a history of all API calls you've made.
         *
         * This is especially important for [ChatRequest], since users will
         * expect you to save their conversations to be continued at later
         * times.
         *
         * If you want to add your own type adapters, use [createGsonBuilder]
         * instead.
         *
         * @return Google gson serializer for json files.
         */
        @JvmStatic
        fun createGson(): Gson {
            return createGsonBuilder().create()
        }

        /**
         * Returns a `GsonBuilder` with all [com.google.gson.TypeAdapter] used
         * by `com.cjcrafter.openai`. Unless you want to register your own
         * adapters, I recommend using [createGson] instead of this method.
         *
         * @return Google gson builder for serializing json files.
         */
        @JvmStatic
        fun createGsonBuilder(): GsonBuilder {
             return GsonBuilder()
                 .registerTypeAdapter(ChatUser::class.java, ChatUserAdapter())
                 .registerTypeAdapter(FinishReason::class.java, FinishReasonAdapter())
                 .registerTypeAdapter(ChatChoiceChunk::class.java, ChatChoiceChunkAdapter())
        }
    }
}