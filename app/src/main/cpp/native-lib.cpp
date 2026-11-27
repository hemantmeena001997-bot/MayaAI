
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include <sys/stat.h>
#include "llama.h"

#define MAYA_LOG(...) __android_log_print(ANDROID_LOG_INFO, "MAYA_OFFLINE", __VA_ARGS__)

static std::mutex mayaMutex;
static llama_model* mayaModel = nullptr;
static std::string mayaModelPath;
static bool mayaBackendReady = false;

extern "C"
JNIEXPORT jstring JNICALL
Java_com_maya_ai_MainActivity_getNativeMessage(
    JNIEnv* env,
    jobject
) {
    return env->NewStringUTF("MAYA Native Engine Ready");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_maya_ai_MainActivity_generateOffline(
    JNIEnv* env,
    jobject,
    jstring modelPath,
    jstring userPrompt
) {
    std::lock_guard<std::mutex> lock(mayaMutex);

    if (modelPath == nullptr || userPrompt == nullptr) {
        return env->NewStringUTF("MAYA: Invalid input");
    }

    const char* pathChars =
        env->GetStringUTFChars(modelPath, nullptr);

    const char* promptChars =
        env->GetStringUTFChars(userPrompt, nullptr);

    if (pathChars == nullptr || promptChars == nullptr) {
        if (pathChars != nullptr) {
            env->ReleaseStringUTFChars(modelPath, pathChars);
        }
        if (promptChars != nullptr) {
            env->ReleaseStringUTFChars(userPrompt, promptChars);
        }
        return env->NewStringUTF("MAYA: Input error");
    }

    std::string path(pathChars);
    std::string question(promptChars);

    env->ReleaseStringUTFChars(modelPath, pathChars);
    env->ReleaseStringUTFChars(userPrompt, promptChars);

    struct stat fileInfo{};

    if (stat(path.c_str(), &fileInfo) != 0 ||
        fileInfo.st_size < 100000000) {
        return env->NewStringUTF("MAYA: Model file invalid");
    }

    MAYA_LOG("Starting offline engine");

    if (!mayaBackendReady) {
        llama_backend_init();
        mayaBackendReady = true;
        MAYA_LOG("Backend initialized");
    }

    if (mayaModel == nullptr || mayaModelPath != path) {

        if (mayaModel != nullptr) {
            llama_model_free(mayaModel);
            mayaModel = nullptr;
        }

        MAYA_LOG("Loading GGUF model");

        llama_model_params modelParams =
            llama_model_default_params();

        modelParams.n_gpu_layers = 0;

        mayaModel = llama_model_load_from_file(
            path.c_str(),
            modelParams
        );

        if (mayaModel == nullptr) {
            mayaModelPath.clear();
            MAYA_LOG("Model loading failed");
            return env->NewStringUTF("MAYA: Model load failed");
        }

        mayaModelPath = path;
        MAYA_LOG("Model loaded successfully");
    }

    llama_context_params contextParams =
        llama_context_default_params();

    contextParams.n_ctx = 1024;
    contextParams.n_batch = 1024;
    contextParams.n_ubatch = 128;
    contextParams.n_threads = 6;
    contextParams.n_threads_batch = 6;

    MAYA_LOG("Creating context");

    llama_context* ctx = llama_init_from_model(
        mayaModel,
        contextParams
    );

    if (ctx == nullptr) {
        return env->NewStringUTF("MAYA: Context creation failed");
    }

    const llama_vocab* vocab =
        llama_model_get_vocab(mayaModel);

    std::string prompt =
        "<|im_start|>system\n"
        "Tum MAYA ho, ek accurate aur helpful Hindi AI assistant. "
        "Hamesha user ke sawal ka seedha aur sachcha jawab do. "
        "Agar jawab pata na ho to saaf batao, galat jawab mat banao. "
        "Hindi ya Hinglish mein chhote aur spasht jawab do.\n"
        "<|im_end|>\n"
        "<|im_start|>user\n" +
        question +
        "\n<|im_end|>\n"
        "<|im_start|>assistant\n";

    std::vector<llama_token> tokens(4096);

    int32_t tokenCount = llama_tokenize(
        vocab,
        prompt.c_str(),
        (int32_t)prompt.size(),
        tokens.data(),
        (int32_t)tokens.size(),
        true,
        true
    );

    if (tokenCount < 0) {
        tokens.resize(-tokenCount);

        tokenCount = llama_tokenize(
            vocab,
            prompt.c_str(),
            (int32_t)prompt.size(),
            tokens.data(),
            (int32_t)tokens.size(),
            true,
            true
        );
    }

    if (tokenCount <= 0 || tokenCount > 1024) {
        llama_free(ctx);
        return env->NewStringUTF("MAYA: Prompt error");
    }

    tokens.resize(tokenCount);

    MAYA_LOG("Prompt token count: %d", tokenCount);

    llama_batch batch = llama_batch_get_one(
        tokens.data(),
        tokenCount
    );

    if (llama_decode(ctx, batch) != 0) {
        llama_free(ctx);
        return env->NewStringUTF("MAYA: Prompt decode failed");
    }

    llama_sampler* sampler =
        llama_sampler_init_greedy();

    if (sampler == nullptr) {
        llama_free(ctx);
        return env->NewStringUTF("MAYA: Sampler error");
    }

    std::string answer;
    std::vector<char> piece(512);

    MAYA_LOG("Generating offline answer");

    for (int i = 0; i < 64; i++) {

        llama_token newToken = llama_sampler_sample(
            sampler,
            ctx,
            -1
        );

        if (llama_vocab_is_eog(vocab, newToken)) {
            break;
        }

        llama_sampler_accept(sampler, newToken);

        int32_t pieceLength = llama_token_to_piece(
            vocab,
            newToken,
            piece.data(),
            (int32_t)piece.size(),
            0,
            true
        );

        if (pieceLength < 0) {
            piece.resize(-pieceLength);

            pieceLength = llama_token_to_piece(
                vocab,
                newToken,
                piece.data(),
                (int32_t)piece.size(),
                0,
                true
            );
        }

        if (pieceLength > 0) {
            answer.append(piece.data(), pieceLength);
        }

        batch = llama_batch_get_one(&newToken, 1);

        if (llama_decode(ctx, batch) != 0) {
            MAYA_LOG("Token decode failed");
            break;
        }
    }

    llama_sampler_free(sampler);
    llama_free(ctx);

    MAYA_LOG("Offline generation finished");

    if (answer.empty()) {
        answer = "MAYA: Koi jawab nahi mila";
    }

    return env->NewStringUTF(answer.c_str());
}
