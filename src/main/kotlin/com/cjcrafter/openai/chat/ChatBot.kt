package com.cjcrafter.openai.chat

import com.cjcrafter.openai.exception.OpenAIError
import com.google.gson.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.function.Consumer

/**
 * The ChatBot class wraps the OpenAI API and lets you send messages and
 * receive responses. For more information on how this works, check out
 * the [OpenAI Documentation](https://platform.openai.com/docs/api-reference/chat)).
 *
 * [com.cjcrafter.openai.chat] differs from [com.cjcrafter.openai.completions].
 * Chat has conversational memory, and is best suited to generate human-readable
 * text. Think of chat as a human that insists on giving elaborate responses.
 * If you are looking for more robotic or specific responses, consider using
 * completions instead.
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
@Deprecated(level = DeprecationLevel.ERROR, message = "Use com.cjcrafter.openai.OpenAI")
class ChatBot @JvmOverloads constructor(
    private val apiKey: String,
    private val organization: String? = null,
    private val client: OkHttpClient = OkHttpClient()
) {

    private val mediaType: MediaType = "application/json; charset=utf-8".toMediaType()
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(ChatUser::class.java, JsonSerializer<ChatUser> { src, _, context -> context!!.serialize(src!!.name.lowercase())!! })
        .create()

    private fun buildRequest(request: ChatRequest): Request {
        val json = gson.toJson(request)
        val body: RequestBody = json.toRequestBody(mediaType)
        return Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .apply { if (organization != null) addHeader("OpenAI-Organization", organization) }
            .post(body).build()
    }

    /**
     * Blocks the current thread until OpenAI responds to https request. The
     * returned value includes information including tokens, generated text,
     * and stop reason. You can access the generated message through
     * [ChatResponse.choices].
     *
     * @param request The input information for ChatGPT.
     * @return The returned response.
     */
    @Throws(IOException::class)
    fun generateResponse(request: ChatRequest): ChatResponse {
        request.stream = false // use streamResponse for stream=true
        val httpRequest = buildRequest(request)

        // Save the JsonObject to check for errors
        var rootObject: JsonObject? = null
        try {
            client.newCall(httpRequest).execute().use { response ->

                rootObject = JsonParser.parseString(response.body!!.string()).asJsonObject
                if (rootObject!!.has("error"))
                    throw OpenAIError.fromJson(rootObject!!["error"].asJsonObject)

                return gson.fromJson(rootObject, ChatResponse::class.java)
            }
        } catch (ex: Throwable) {
            println(rootObject)
            throw ex
        }
    }

    /**
     * This is a helper method that calls [streamResponse], which lets you use
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
    fun streamResponseKotlin(request: ChatRequest, onResponse: ChatResponseChunk.() -> Unit) {
        streamResponse(request, { it.onResponse() })
    }

    /**
     * Uses ChatGPT to generate tokens in real time. As ChatGPT generates
     * content, those tokens are sent in a stream in real time. This allows you
     * to update the user without long delays between their input and OpenAI's
     * response.
     *
     * For *"simpler"* calls, you can use [generateResponse] which will block
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
     * @see generateResponse
     * @see streamResponseKotlin
     * @since 1.2.0
     */
    @JvmOverloads
    fun streamResponse(
        request: ChatRequest,
        onResponse: Consumer<ChatResponseChunk>, // use Consumer instead of Kotlin for better Java syntax
        onFailure: Consumer<IOException> = Consumer { it.printStackTrace() }
    ) {
        request.stream = true // use requestResponse for stream=false
        val httpRequest = buildRequest(request)

        client.newCall(httpRequest).enqueue(object : Callback {
            var cache: ChatResponseChunk? = null

            override fun onFailure(call: Call, e: IOException) {
                onFailure.accept(e)
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
}