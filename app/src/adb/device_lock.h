#ifndef SC_DEVICE_LOCK_H
#define SC_DEVICE_LOCK_H

#include "common.h"

#include <stdbool.h>

/**
 * 设备占用锁
 *
 * 用于避免多个 scrcpy 实例同时连接同一台设备：实例在选择设备前先探测
 * 目标是否已被占用，选定后加锁并持有至进程退出。
 *
 * 底层使用 flock()，锁随进程退出（或 fd 关闭）自动释放，不会留下陈旧的
 * 锁文件状态。
 */

// 探测设备是否已被其他 scrcpy 实例占用（不获取锁）
bool
sc_device_lock_is_busy(const char *serial);

// 占用设备。成功返回 true，锁持有至进程退出（或调用 release）
bool
sc_device_lock_acquire(const char *serial);

// 释放设备占用（一般无需调用，进程退出时自动释放）
void
sc_device_lock_release(const char *serial);

#endif
