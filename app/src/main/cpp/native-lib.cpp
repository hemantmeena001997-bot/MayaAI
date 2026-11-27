#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include "llama.h"

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

    const char* pathChars = env->GetStringUTFChars(modelPath, nullptr);
    const char* promptChars = env->GetStringUTFChars(userPrompt, nullptr);

    if (pathChars == nullptr || promptChars == nullptr) {
        if (pathChars) env->ReleaseStringUTFChars(modelPath, pathChars);
        if (promptChars) env->ReleaseStringUTFChars(userPrompt, promptChars);
        return env->NewStringUTF("MAYA: Input error");
    }

    std::string path(pathChars);
    std::string question(promptChars);

    env->ReleaseStringUTFChars(modelPath, pathChars);
    env->ReleaseStringUTFChars(userPrompt, promptChars);

    if (!mayaBackendReady) {
        llama_backend_init();
        mayaBackendReady = true;
    }

    if (mayaModel == nullptr || mayaModelPath != path) {
        if (mayaModel != nullptr) {
            llama_model_free(mayaModel);
            mayaModel = nullptr;
        }

        llama_model_params modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;

        mayaModel = llama_model_load_from_file(
            path.c_str(),
            modelParams
        );

        if (mayaModel == nullptr) {
            mayaModelPath.clear();
            return env->NewStringUTF("MAYA: Model load nahi hua");
        }

        mayaModelPath = path;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = 2048;
    contextParams.n_batch = 512;
    contextParams.n_ubatch = 512;

    llama_context* ctx = llama_init_from_model(
        mayaModel,
        contextParams
    );

    if (ctx == nullptr) {
        return env->NewStringUTF("MAYA: Context error");
    }

    const llama_vocab* vocab = llama_model_get_vocab(mayaModel);

    std::string prompt =
        "<|im_start|>system\n"
        "Tum MAYA ho, ek samajhdar aur friendly Hindi AI assistant. "
        "User ko seedhe, sahi aur saral jawab do. "
        "Hindi ya Hinglish mein jawab do.\n"
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

    if (tokenCount <= 0 || tokenCount > 2048) {
        llama_free(ctx);
        return env->NewStringUTF("MAYA: Prompt error");
    }

    tokens.resize(tokenCount);

    llama_batch batch = llama_batch_get_one(
        tokens.data(),
        tokenCount
    );

    if (llama_decode(ctx, batch) != 0) {
        llama_free(ctx);
        return env->NewStringUTF("MAYA: Decode error");
    }

    llama_sampler* sampler = llama_sampler_init_greedy();

    std::string answer;
    std::vector<char> piece(512);

    for (int i = 0; i < 128; i++) {
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

        llama_token nextToken = newToken;

        batch = llama_batch_get_one(&nextToken, 1);

        if (llama_decode(ctx, batch) != 0) {
            break;
        }
    }

    llama_sampler_free(sampler);
    llama_free(ctx);

    if (answer.empty()) {
        answer = "MAYA: Koi jawab nahi mila";
    }

    return env->NewStringUTF(answer.c_str());
}
