#include <jni.h>
#include <node.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <unistd.h>

static std::string readString(JNIEnv* env, jstring value) {
    const char* utf = env->GetStringUTFChars(value, nullptr);
    if (!utf) return {};
    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gridlens_android_NodeRuntime_startNative(JNIEnv* env, jclass,
        jobjectArray arguments, jstring workingDirectory, jstring cacheDirectory) {
    const auto cwd = readString(env, workingDirectory);
    const auto cache = readString(env, cacheDirectory);
    if (env->ExceptionCheck() || chdir(cwd.c_str()) != 0 || setenv("TMPDIR", cache.c_str(), 1) != 0) return -1;
    const jsize count = env->GetArrayLength(arguments);
    if (count < 2 || count > 16) return -1;
    std::vector<std::string> strings;
    size_t bytes = 0;
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        strings.push_back(readString(env, value));
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return -1;
        bytes += strings.back().size() + 1;
    }
    // libuv requires contiguous argument storage, retained until Node exits.
    std::vector<char> storage(bytes);
    std::vector<char*> argv(count + 1, nullptr);
    size_t offset = 0;
    for (jsize i = 0; i < count; ++i) {
        argv[i] = storage.data() + offset;
        std::memcpy(argv[i], strings[i].c_str(), strings[i].size() + 1);
        offset += strings[i].size() + 1;
    }
    return node::Start(count, argv.data());
}
