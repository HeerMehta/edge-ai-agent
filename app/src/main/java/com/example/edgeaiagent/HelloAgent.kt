package com.example.edgeaiagent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import java.io.File

// WORKSHOP: set true if GPU misbehaves
const val FORCE_CPU = false

// WORKSHOP: students edit this
const val SYSTEM_PROMPT =
    "You are Buddy, a warm and easygoing friend to chat with. " +
        "Keep replies short and natural, like texting a friend. " +
        "You can also tell people the current time."

// WORKSHOP: students edit this (how "creative" the model is)
val SAMPLER = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.8)

const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
const val MAX_OUTPUT_TOKENS = 256

/** Wraps the LiteRT-LM Engine (the loaded model) and the one Conversation we reuse. */
class HelloAgent(private val context: Context) : Assistant {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** The model lives in the app's external files dir; this call also creates that folder. */
    private fun modelFile(): File = File(context.getExternalFilesDir(null), MODEL_FILE_NAME)

    /**
     * Loads the model. Blocking and slow (~10 s), so call it from a background dispatcher.
     * Returns the name of the backend that ended up active ("GPU" or "CPU").
     */
    override fun start(): String {
        if (!modelFile().exists()) {
            Log.w(TAG, "Model file missing: ${modelFile()}")
            throw ModelMissingException(
                "adb push $MODEL_FILE_NAME /sdcard/Android/data/${context.packageName}/files/"
            )
        }

        var backendName = "CPU"
        var loaded: Engine? = null

        if (!FORCE_CPU) {
            try {
                loaded = createEngine(Backend.GPU(), "GPU")
                backendName = "GPU"
            } catch (e: Exception) {
                Log.w(TAG, "GPU init failed, falling back to CPU", e)
            }
        }
        // GPU failed (or was skipped): build a brand-new Engine on the CPU
        val eng = loaded ?: createEngine(Backend.CPU(), "CPU")

        // ONE conversation for the whole session: it remembers the chat history itself
        conversation = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of("$SYSTEM_PROMPT $TOOL_RULE"),
                samplerConfig = SAMPLER,
                thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                tools = listOf(tool(ClockToolSet())),
                automaticToolCalling = false, // we run the tool loop ourselves
            )
        )
        engine = eng
        Log.i(TAG, "Backend in use: $backendName")
        return backendName
    }

    /** Builds an Engine for one backend and initializes it; closes it again if that throws. */
    private fun createEngine(backend: Backend, label: String): Engine {
        Log.i(TAG, "init start ($label)")
        val t0 = SystemClock.elapsedRealtime()
        val eng = Engine(
            EngineConfig(
                modelPath = modelFile().path,
                backend = backend,
                cacheDir = context.cacheDir.path,
            )
        )
        try {
            eng.initialize()
        } catch (e: Exception) {
            eng.close()
            Log.e(TAG, "init failed ($label) after ${SystemClock.elapsedRealtime() - t0} ms")
            throw e
        }
        Log.i(TAG, "init end ($label) in ${SystemClock.elapsedRealtime() - t0} ms")
        return eng
    }

    /** Blocking. Hands the question to the tool loop and returns the final answer. */
    override fun chat(text: String, onStep: (String) -> Unit): String {
        val conv = checkNotNull(conversation) { "Model not loaded yet" }
        return runToolLoop(conv, text, onStep)
    }

    /*
    /** Stage 1: no tools, just ONE blocking call. Swap it in by replacing runToolLoop with chatOnce in chat(). */
    private fun chatOnce(conv: Conversation, text: String, onStep: (String) -> Unit): String {
        onStep("model: thinking…")
        return conv.sendMessage(Message.user(text)).text()
    }
    */

    override fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }
}

/** A Message holds a list of Content parts; we only want the text ones. */
fun Message.text(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
