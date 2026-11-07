#include "device_lock.h"

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#include "util/log.h"

#define SC_DEVICE_LOCK_MAX 16
#define SC_DEVICE_LOCK_SERIAL_MAX 128
#define SC_DEVICE_LOCK_PATH_MAX 512

struct sc_device_lock_entry {
    char serial[SC_DEVICE_LOCK_SERIAL_MAX];
    int fd;
    bool used;
};

static struct sc_device_lock_entry locks[SC_DEVICE_LOCK_MAX];

// 锁文件放在 XDG_RUNTIME_DIR 下（通常是 /run/user/<uid>），重启后自动清空
static const char *
get_lock_dir(void) {
    static char dir[SC_DEVICE_LOCK_PATH_MAX];
    if (dir[0]) {
        return dir;
    }

    const char *runtime = getenv("XDG_RUNTIME_DIR");
    int r;
    if (runtime && runtime[0]) {
        r = snprintf(dir, sizeof(dir), "%s/scrcpy-device-locks", runtime);
    } else {
        r = snprintf(dir, sizeof(dir), "/tmp/scrcpy-device-locks-%u",
                     (unsigned) getuid());
    }
    if (r <= 0 || (size_t) r >= sizeof(dir)) {
        dir[0] = '\0';
        return NULL;
    }

    if (mkdir(dir, 0700) < 0 && errno != EEXIST) {
        LOGW("Could not create lock directory %s: %s", dir, strerror(errno));
        dir[0] = '\0';
        return NULL;
    }

    return dir;
}

static bool
build_path(const char *serial, char *path, size_t path_size) {
    const char *dir = get_lock_dir();
    if (!dir) {
        return false;
    }

    // 序列号可能包含 ':'（TCP/IP）等不适合做文件名的字符，统一替换
    char safe[SC_DEVICE_LOCK_SERIAL_MAX * 2];
    size_t j = 0;
    for (size_t i = 0; serial[i] && j < sizeof(safe) - 1; ++i) {
        char c = serial[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '-'
                || c == '_') {
            safe[j++] = c;
        } else {
            safe[j++] = '_';
        }
    }
    safe[j] = '\0';

    int r = snprintf(path, path_size, "%s/%s.lock", dir, safe);
    return r > 0 && (size_t) r < path_size;
}

// 返回加锁成功的 fd，失败返回 -1；busy 用于区分“被占用”和其他错误
static int
open_and_lock(const char *serial, bool *busy) {
    *busy = false;

    char path[SC_DEVICE_LOCK_PATH_MAX];
    if (!build_path(serial, path, sizeof(path))) {
        return -1;
    }

    int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) {
        LOGW("Could not open lock file %s: %s", path, strerror(errno));
        return -1;
    }

    if (flock(fd, LOCK_EX | LOCK_NB) < 0) {
        if (errno == EWOULDBLOCK) {
            *busy = true;
        } else {
            LOGW("Could not lock %s: %s", path, strerror(errno));
        }
        close(fd);
        return -1;
    }

    return fd;
}

static struct sc_device_lock_entry *
find_entry(const char *serial) {
    for (size_t i = 0; i < SC_DEVICE_LOCK_MAX; ++i) {
        struct sc_device_lock_entry *e = &locks[i];
        if (e->used && !strcmp(e->serial, serial)) {
            return e;
        }
    }
    return NULL;
}

bool
sc_device_lock_is_busy(const char *serial) {
    assert(serial);

    // 本进程已持有该设备时不算占用
    if (find_entry(serial)) {
        return false;
    }

    bool busy;
    int fd = open_and_lock(serial, &busy);
    if (fd >= 0) {
        // 探测成功，立即释放
        close(fd);
        return false;
    }

    return busy;
}

bool
sc_device_lock_acquire(const char *serial) {
    assert(serial);

    if (find_entry(serial)) {
        return true;
    }

    bool busy;
    int fd = open_and_lock(serial, &busy);
    if (fd < 0) {
        return false;
    }

    // 写入 pid，便于排查设备被哪个实例占用
    char buf[32];
    int len = snprintf(buf, sizeof(buf), "%d\n", (int) getpid());
    if (len > 0) {
        if (ftruncate(fd, 0) == 0) {
            ssize_t w = write(fd, buf, (size_t) len);
            (void) w; // 仅用于调试，失败不影响加锁
        }
    }

    for (size_t i = 0; i < SC_DEVICE_LOCK_MAX; ++i) {
        struct sc_device_lock_entry *e = &locks[i];
        if (!e->used) {
            snprintf(e->serial, sizeof(e->serial), "%s", serial);
            e->fd = fd;
            e->used = true;
            LOGD("Device lock acquired: %s", serial);
            return true;
        }
    }

    // 一个实例只连接一台设备，理论上不会走到这里
    LOGW("Device lock table full, releasing %s", serial);
    close(fd);
    return false;
}

void
sc_device_lock_release(const char *serial) {
    struct sc_device_lock_entry *e = find_entry(serial);
    if (!e) {
        return;
    }

    close(e->fd);
    e->fd = -1;
    e->used = false;
    e->serial[0] = '\0';
}
