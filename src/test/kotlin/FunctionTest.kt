import com.cjcrafter.openai.OpenAI
import com.cjcrafter.openai.chat.ChatFunction
import com.cjcrafter.openai.chat.ChatMessage.Companion.toSystemMessage
import com.cjcrafter.openai.chat.ChatMessage.Companion.toUserMessage
import com.cjcrafter.openai.chat.ChatRequest
import io.github.cdimascio.dotenv.dotenv
import java.util.Scanner

fun main() {
    val scan = Scanner(System.`in`)

    val prompt = "Tell people the weather if they ask for it. Otherwise, say: Wonderful weather we're having!".toSystemMessage()
    val messages = mutableListOf(prompt)
    val functions = mutableListOf(
        ChatFunction("get_weather", "Returns the temperature in the user's location", ChatFunction.Parameters(properties = mapOf(Pair("city", ChatFunction.Property("string", description = "Which city to check, for example: Boston")))))
    )
    val request = ChatRequest(model = "gpt-3.5-turbo", messages=messages, functions=functions)

    val key = dotenv()["OPENAI_TOKEN"]
    val openai = OpenAI(key)

    while (true) {
        print("Enter text: ")
        val input = scan.nextLine()

        messages.add(input.toUserMessage())

        openai.streamChatCompletion(request, { println(it) })
    }
}