package com.example.edgeaiagent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

// WORKSHOP: set true if GPU misbehaves
const val FORCE_CPU = false

const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

/**
 * Loads the model for the ADK assistant. Blocking and slow (~10 s),
 * so call it from a background dispatcher. Tries the GPU first and falls back to the CPU.
 * Returns the engine and the name of the backend that ended up active ("GPU" or "CPU").
 */
fun loadEngine(context: Context): Pair<Engine, String> {
    // The model lives in the app's external files dir; this call also creates that folder
    val modelFile = File(context.getExternalFilesDir(null), MODEL_FILE_NAME)
    if (!modelFile.exists()) {
        Log.w(TAG, "Model file missing: $modelFile")
        throw ModelMissingException(
            "adb push $MODEL_FILE_NAME /sdcard/Android/data/${context.packageName}/files/"
        )
    }

    if (!FORCE_CPU) {
        try {
            return createEngine(context, modelFile, Backend.GPU(), "GPU") to "GPU"
        } catch (e: Exception) {
            Log.w(TAG, "GPU init failed, falling back to CPU", e)
        }
    }
    // GPU failed (or was skipped): build a brand-new Engine on the CPU
    return createEngine(context, modelFile, Backend.CPU(), "CPU") to "CPU"
}

/** Builds an Engine for one backend and initializes it; closes it again if that throws. */
private fun createEngine(context: Context, modelFile: File, backend: Backend, label: String): Engine {
    Log.i(TAG, "init start ($label)")
    val t0 = SystemClock.elapsedRealtime()
    val eng = Engine(
        EngineConfig(
            modelPath = modelFile.path,
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
