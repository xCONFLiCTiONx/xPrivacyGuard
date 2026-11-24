#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <string>
#include <mutex>
#include <vector>

#include "aether_ffi.h"

namespace {

JavaVM *g_vm = nullptr;
jobject g_service = nullptr;
jmethodID g_protect_method = nullptr;
std::mutex g_service_mutex;

int protect_android_socket(int fd) {
    std::lock_guard<std::mutex> lock(g_service_mutex);
    if (g_vm == nullptr || g_service == nullptr || g_protect_method == nullptr) return 0;
    JNIEnv *env = nullptr;
    bool attached = false;
    jint status = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    } else if (status != JNI_OK) {
        return 0;
    }
    const jboolean protected_socket = env->CallBooleanMethod(g_service, g_protect_method, static_cast<jint>(fd));
    const bool exception = env->ExceptionCheck();
    if (exception) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
    return (!exception && protected_socket == JNI_TRUE) ? 1 : 0;
}


std::string from_java(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_java(JNIEnv *env, char *raw) {
    if (raw == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"Aether returned a null response\"}");
    }
    jstring result = env->NewStringUTF(raw);
    aether_string_free(raw);
    return result;
}

jstring call_string(JNIEnv *env, jstring value, char *(*function)(const char *)) {
    const std::string input = from_java(env, value);
    return to_java(env, function(input.c_str()));
}

jstring call_id(JNIEnv *env, jlong id, char *(*function)(uint64_t)) {
    return to_java(env, function(static_cast<uint64_t>(id)));
}

jstring call_id_string(JNIEnv *env, jlong id, jstring value,
                       char *(*function)(uint64_t, const char *)) {
    const std::string input = from_java(env, value);
    return to_java(env, function(static_cast<uint64_t>(id), input.c_str()));
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeVersion(
        JNIEnv *env, jobject) {
    return to_java(env, aether_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeIdentityOpen(
        JNIEnv *env, jobject, jstring payload) {
    return call_string(env, payload, aether_identity_open);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeIdentitySummary(
        JNIEnv *env, jobject, jlong identity) {
    return call_id(env, identity, aether_identity_summary);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeIdentityFree(
        JNIEnv *env, jobject, jlong identity) {
    return call_id(env, identity, aether_identity_free);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeScanStart(
        JNIEnv *env, jobject, jlong identity, jstring payload) {
    return call_id_string(env, identity, payload, aether_scan_start);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeVerifyStart(
        JNIEnv *env, jobject, jlong identity, jstring payload) {
    return call_id_string(env, identity, payload, aether_verify_start);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeTunnelStart(
        JNIEnv *env, jobject, jlong identity, jstring payload) {
    return call_id_string(env, identity, payload, aether_tunnel_start);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeJobPoll(
        JNIEnv *env, jobject, jlong job) {
    return call_id(env, job, aether_job_poll);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeJobCancel(
        JNIEnv *env, jobject, jlong job) {
    return call_id(env, job, aether_job_cancel);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeJobFree(
        JNIEnv *env, jobject, jlong job) {
    return call_id(env, job, aether_job_free);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeTeamSignIn(
        JNIEnv *env, jobject, jstring payload) {
    return call_string(env, payload, aether_team_sign_in);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeTeamCodeRequest(
        JNIEnv *env, jobject, jstring payload) {
    return call_string(env, payload, aether_team_code_request);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeTeamCodeResend(
        JNIEnv *env, jobject, jlong session) {
    return call_id(env, session, aether_team_code_resend);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeTeamCodeSubmit(
        JNIEnv *env, jobject, jlong session, jstring code) {
    return call_id_string(env, session, code, aether_team_code_submit);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeTeamSessionFree(
        JNIEnv *env, jobject, jlong session) {
    return call_id(env, session, aether_team_session_free);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeTeamTokenSet(
        JNIEnv *env, jobject, jstring token) {
    return call_string(env, token, aether_team_token_set);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeTeamTokenClear(
        JNIEnv *env, jobject) {
    return to_java(env, aether_team_token_clear());
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeRegisterSocketProtector(
        JNIEnv *env, jobject, jobject service) {
    std::lock_guard<std::mutex> lock(g_service_mutex);
    if (service == nullptr) {
        aether_set_android_socket_protector(nullptr);
        if (g_service != nullptr) env->DeleteGlobalRef(g_service);
        g_service = nullptr;
        g_protect_method = nullptr;
        return JNI_TRUE;
    }
    if (env->GetJavaVM(&g_vm) != JNI_OK) return JNI_FALSE;
    jclass service_class = env->GetObjectClass(service);
    if (service_class == nullptr) return JNI_FALSE;
    jmethodID protect = env->GetMethodID(service_class, "protect", "(I)Z");
    env->DeleteLocalRef(service_class);
    if (protect == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
    jobject global_service = env->NewGlobalRef(service);
    if (global_service == nullptr) return JNI_FALSE;
    if (g_service != nullptr) env->DeleteGlobalRef(g_service);
    g_service = global_service;
    g_protect_method = protect;
    aether_set_android_socket_protector(protect_android_socket);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeRawTunnelStart(
        JNIEnv *env, jobject, jlong identity, jstring payload) {
    return call_id_string(env, identity, payload, aether_raw_tunnel_start);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeRawTunnelSend(
        JNIEnv *env, jobject, jlong tunnel, jbyteArray packet, jint length) {
    if (packet == nullptr || length <= 0 || length > 65535) return -1;
    const jsize arr_len = env->GetArrayLength(packet);
    if (length > arr_len) return -1;
    uint8_t stack_buf[2048];
    std::vector<uint8_t> heap_buf;
    uint8_t *ptr = stack_buf;
    if (length > static_cast<jint>(sizeof(stack_buf))) {
        heap_buf.resize(static_cast<size_t>(length));
        ptr = heap_buf.data();
    }
    env->GetByteArrayRegion(packet, 0, length, reinterpret_cast<jbyte *>(ptr));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return static_cast<jint>(aether_raw_tunnel_send(
            static_cast<uint64_t>(tunnel), ptr, static_cast<size_t>(length)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeRawTunnelRecv(
        JNIEnv *env, jobject, jlong tunnel, jbyteArray out_buf, jint timeout_ms) {
    if (out_buf == nullptr) return -1;
    const jsize cap = env->GetArrayLength(out_buf);
    if (cap <= 0) return -1;
    uint8_t stack_buf[2048];
    std::vector<uint8_t> heap_buf;
    uint8_t *ptr = stack_buf;
    const size_t read_cap = static_cast<size_t>(cap > 65535 ? 65535 : cap);
    if (read_cap > sizeof(stack_buf)) {
        heap_buf.resize(read_cap);
        ptr = heap_buf.data();
    }
    const int32_t n = aether_raw_tunnel_recv(
            static_cast<uint64_t>(tunnel),
            ptr,
            read_cap,
            static_cast<uint32_t>(timeout_ms > 0 ? timeout_ms : 100));
    if (n > 0) {
        env->SetByteArrayRegion(out_buf, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(ptr));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return -1;
        }
    }
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeRawTunnelIsAlive(
        JNIEnv *, jobject, jlong tunnel) {
    return aether_raw_tunnel_is_alive(static_cast<uint64_t>(tunnel)) == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeRawTunnelMigrate(
        JNIEnv *, jobject, jlong tunnel) {
    return aether_raw_tunnel_migrate(static_cast<uint64_t>(tunnel)) == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xconflictionx_xprivacyguard_vpn_transport_AetherNative_nativeRawTunnelStop(
        JNIEnv *env, jobject, jlong tunnel) {
    return call_id(env, tunnel, aether_raw_tunnel_stop);
}
