
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class KeywordSpotterConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var maxActivePaths: Int = 4,
    var keywordsFile: String = "keywords.txt",
    var keywordsScore: Float = 1.5f,
    var keywordsThreshold: Float = 0.25f,
    var numTrailingBlanks: Int = 2,
)

data class KeywordSpotterResult(
    val keyword: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
) {
    override fun toString(): String {
        val tokensStr = tokens.joinToString(", ")
        val timestampsStr = timestamps.joinToString(", ") { "%.2f".format(it) }
        return "Keyword: $keyword\nTokens: [$tokensStr]\nTimestamps: [$timestampsStr]"
    }
}

class KeywordSpotter(
    assetManager: AssetManager? = null,
    val config: KeywordSpotterConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) {
            "Invalid KeywordSpotterConfig: failed to create native KeywordSpotter"
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(keywords: String = ""): OnlineStream {
        val p = createStream(ptr, keywords)
        return OnlineStream(p)
    }

    fun decode(stream: OnlineStream) = decode(ptr, stream.ptr)
    fun reset(stream: OnlineStream) = reset(ptr, stream.ptr)
    fun isReady(stream: OnlineStream) = isReady(ptr, stream.ptr)

    fun getResult(stream: OnlineStream): KeywordSpotterResult {
        return getResult(ptr, stream.ptr)
    }

    private external fun delete(ptr: Long)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: KeywordSpotterConfig,
    ): Long

    private external fun newFromFile(
        config: KeywordSpotterConfig,
    ): Long

    private external fun createStream(ptr: Long, keywords: String): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): KeywordSpotterResult

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}


fun getKwsModelConfig(type: Int): OnlineModelConfig? {
    if (type != 1) return null

    val modelDir = "gigaspeech-model"

    return OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
            encoder = "$modelDir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            decoder = "$modelDir/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            joiner = "$modelDir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        ),
        tokens = "$modelDir/tokens.txt",
        modelType = "zipformer2"
    )
}

fun getKeywordsFile(type: Int): String {
    return if (type == 1) {
        "gigaspeech-model/keywords.txt"
    } else {
        ""
    }
}
