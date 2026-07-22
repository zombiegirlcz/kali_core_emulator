/*
 * usbfd_jni.c — JNI bridge: Unix Domain Socket + SCM_RIGHTS fd passing
 *
 * This library runs INSIDE the Android app process (loaded via System.loadLibrary).
 * It creates a Unix Domain Socket server that PRoot-native code can connect to.
 * When a PRoot process connects, we pass the pre-opened USB device fd via
 * SCM_RIGHTS, so the PRoot process can do direct ioctl(2) on the USB device.
 *
 * Build for ARM64:
 *   aarch64-linux-android24-clang -shared -o libusbfd_exporter.so usbfd_jni.c \
 *     -I$NDK/sysroot/usr/include -I$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include
 */

#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>

#define TAG "UsbFdExporter_JNI"
#define MAX_CLIENTS 4

#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * Create a Unix Domain Socket server bound to `path`.
 * Returns fd on success, negative errno on failure.
 */
JNIEXPORT jint JNICALL
Java_com_linux_core_core_UsbFdExporter_nativeCreateServerSocket(
    JNIEnv *env, jobject thiz, jstring path)
{
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) return -EINVAL;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        int err = errno;
        LOGE("socket() failed: %s", strerror(err));
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        return -err;
    }

    // Remove stale socket file
    unlink(cpath);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, cpath, sizeof(addr.sun_path) - 1);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        int err = errno;
        LOGE("bind(%s) failed: %s", cpath, strerror(err));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        return -err;
    }

    // Make socket world-readable/writable so PRoot (same UID) can connect
    chmod(cpath, 0666);

    if (listen(fd, MAX_CLIENTS) < 0) {
        int err = errno;
        LOGE("listen() failed: %s", strerror(err));
        close(fd);
        unlink(cpath);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        return -err;
    }

    LOGI("UDS server bound to %s, fd=%d", cpath, fd);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return fd;
}

/*
 * Accept one client connection and send `usbFd` via SCM_RIGHTS.
 * Blocks until a client connects.
 * Returns client fd on success, negative errno on failure.
 */
JNIEXPORT jint JNICALL
Java_com_linux_core_core_UsbFdExporter_nativeAcceptAndSendFd(
    JNIEnv *env, jobject thiz, jint serverFd, jint usbFd)
{
    int clientFd = accept(serverFd, NULL, NULL);
    if (clientFd < 0) {
        int err = errno;
        LOGE("accept() failed: %s", strerror(err));
        return -err;
    }

    LOGI("Client connected on fd=%d, sending USB fd=%d", clientFd, usbFd);

    // SCM_RIGHTS: pass the USB fd to the client
    // We send a single dummy byte + ancillary fd
    char dummy = 'F'; // 'F' = fd handoff marker
    struct iovec iov;
    iov.iov_base = &dummy;
    iov.iov_len = 1;

    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;

    // Ancillary data buffer for SCM_RIGHTS
    union {
        struct cmsghdr cm;
        char control[CMSG_SPACE(sizeof(int))];
    } control_un;
    msg.msg_control = control_un.control;
    msg.msg_controllen = sizeof(control_un.control);

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &usbFd, sizeof(int));

    ssize_t sent = sendmsg(clientFd, &msg, 0);
    if (sent < 0) {
        int err = errno;
        LOGE("sendmsg(SCM_RIGHTS) failed: %s", strerror(err));
        close(clientFd);
        return -err;
    }

    LOGI("USB fd %d sent to client fd=%d", usbFd, clientFd);
    return clientFd;
}

/*
 * Close a socket fd.
 */
JNIEXPORT void JNICALL
Java_com_linux_core_core_UsbFdExporter_nativeCloseSocket(
    JNIEnv *env, jobject thiz, jint fd)
{
    if (fd >= 0) {
        LOGI("Closing socket fd=%d", fd);
        close(fd);
    }
}

/*
 * Read raw bytes from client fd. Returns number of bytes read, or negative errno.
 */
JNIEXPORT jint JNICALL
Java_com_linux_core_core_UsbFdExporter_nativeReadClient(
    JNIEnv *env, jobject thiz, jint clientFd, jbyteArray buf, jint offset, jint len)
{
    jbyte *cBuf = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!cBuf) return -ENOMEM;

    ssize_t n = read(clientFd, cBuf + offset, len);
    (*env)->ReleaseByteArrayElements(env, buf, cBuf, 0);

    if (n < 0) return -errno;
    return (jint)n;
}

/*
 * Write raw bytes to client fd.
 */
JNIEXPORT jint JNICALL
Java_com_linux_core_core_UsbFdExporter_nativeWriteClient(
    JNIEnv *env, jobject thiz, jint clientFd, jbyteArray buf, jint offset, jint len)
{
    jbyte *cBuf = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!cBuf) return -ENOMEM;

    ssize_t n = write(clientFd, cBuf + offset, len);
    (*env)->ReleaseByteArrayElements(env, buf, cBuf, 0);

    if (n < 0) return -errno;
    return (jint)n;
}
