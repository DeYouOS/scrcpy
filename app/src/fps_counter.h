#ifndef SC_FPSCOUNTER_H
#define SC_FPSCOUNTER_H

#include "common.h"

#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>

#include "util/thread.h"
#include "util/tick.h"

struct sc_fps_counter;

// 统计信息回调：每秒触发一次，传递 fps、跳帧数、码率（bytes/s）
typedef void (*sc_fps_counter_on_stats)(struct sc_fps_counter *counter,
                                        unsigned fps,
                                        unsigned skipped,
                                        uint64_t bytes_per_sec,
                                        void *userdata);

struct sc_fps_counter {
    sc_thread thread;
    sc_mutex mutex;
    sc_cond state_cond;

    bool thread_started;

    // atomic so that we can check without locking the mutex
    // if the FPS counter is disabled, we don't want to lock unnecessarily
    atomic_bool started;

    // the following fields are protected by the mutex
    bool interrupted;
    unsigned nr_rendered;
    unsigned nr_skipped;
    uint64_t nr_bytes; // 当前周期内收到的网络字节数
    sc_tick next_timestamp;

    // 统计回调（可选），在 fps_counter 线程中调用
    sc_fps_counter_on_stats on_stats;
    void *on_stats_userdata;
};

bool
sc_fps_counter_init(struct sc_fps_counter *counter);

void
sc_fps_counter_destroy(struct sc_fps_counter *counter);

bool
sc_fps_counter_start(struct sc_fps_counter *counter);

void
sc_fps_counter_stop(struct sc_fps_counter *counter);

bool
sc_fps_counter_is_started(struct sc_fps_counter *counter);

// request to stop the thread (on quit)
// must be called before sc_fps_counter_join()
void
sc_fps_counter_interrupt(struct sc_fps_counter *counter);

void
sc_fps_counter_join(struct sc_fps_counter *counter);

void
sc_fps_counter_add_rendered_frame(struct sc_fps_counter *counter);

void
sc_fps_counter_add_skipped_frame(struct sc_fps_counter *counter);

void
sc_fps_counter_add_bytes(struct sc_fps_counter *counter, uint64_t bytes);

#endif
