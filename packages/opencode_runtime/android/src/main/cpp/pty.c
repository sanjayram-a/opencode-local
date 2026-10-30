// PTY bridge: forkpty + execve for interactive sessions (terminal tab). The exec target is always an absolute
// path supplied by Kotlin (proot in nativeLibraryDir), with an explicit environment.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static char **to_cstrings(JNIEnv *env, jobjectArray arr) {
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_cstrings(char **v) {
    if (!v) return;
    for (char **p = v; *p; p++) free(*p);
    free(v);
}

JNIEXPORT jintArray JNICALL
Java_dev_opencodeapk_runtime_Pty_spawn(JNIEnv *env, jclass clazz, jobjectArray jargv, jobjectArray jenv,
                                      jstring jcwd, jint rows, jint cols) {
    (void) clazz;
    char **argv = to_cstrings(env, jargv);
    char **envp = to_cstrings(env, jenv);
    const char *cwd_utf = (*env)->GetStringUTFChars(env, jcwd, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwd_utf);

    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    int master = -1;
    pid_t pid = (argv && envp && cwd && argv[0]) ? forkpty(&master, NULL, NULL, &ws) : -1;
    if (pid == 0) {
        // Child: reset signals the app may have changed, then exec. Never returns on success.
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, NULL);
        for (int sig = 1; sig < 32; sig++) signal(sig, SIG_DFL);
        if (chdir(cwd) != 0) _exit(126);
        execve(argv[0], argv, envp);
        _exit(127);
    }
    int err = errno;
    free_cstrings(argv);
    free_cstrings(envp);
    free(cwd);
    if (pid < 0) {
        jclass ex = (*env)->FindClass(env, "java/io/IOException");
        (*env)->ThrowNew(env, ex, err ? strerror(err) : "forkpty failed");
        return NULL;
    }
    fcntl(master, F_SETFD, FD_CLOEXEC);
    jintArray result = (*env)->NewIntArray(env, 2);
    jint vals[2] = {master, pid};
    (*env)->SetIntArrayRegion(env, result, 0, 2, vals);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_opencodeapk_runtime_Pty_resize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env; (void) clazz;
    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_dev_opencodeapk_runtime_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_dev_opencodeapk_runtime_Pty_kill(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env; (void) clazz;
    kill(pid, sig);
}
