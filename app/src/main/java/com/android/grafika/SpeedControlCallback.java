/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.util.Log;

/**
 * MoviePlayer 的帧速率控制回调。
 *
 * <p>视频文件中的每一帧通常带有 presentation timestamp（PTS，展示时间戳）。PTS 描述的是
 * 这帧在媒体时间轴上应该何时出现，而不是解码器把它解码出来后必须立即显示。因此，
 * 如果应用拿到输出帧后马上调用 {@code releaseOutputBuffer()}，就可能以“解码器能达到的
 * 最大速度”播放，导致视频明显快于正常速度。</p>
 *
 * <p>本类在每次帧提交给 Surface 之前，根据当前帧与上一帧的 PTS 差值进行等待，使帧提交
 * 节奏尽量接近原视频的播放速度。它使用 {@link System#nanoTime()} 作为单调时钟，不使用
 * {@code System.currentTimeMillis()}，因为墙上时钟可能被系统校准或用户修改。</p>
 *
 * <p>本类不直接与 VSYNC（垂直同步）协调。显示器刷新率由系统和硬件决定，播放器也无法
 * 完全控制，因此实际显示过程中仍可能发生丢帧或重复帧；本类只控制“何时把帧提交给图形
 * 管线”。</p>
 *
 * <p>所有 {@link MoviePlayer.FrameCallback} 方法都应只由 MoviePlayer 的播放/解码线程调用。
 * 特别是 {@link #preRender(long)} 可能阻塞等待，不应在 Android UI 线程执行。</p>
 */
public class SpeedControlCallback implements MoviePlayer.FrameCallback {
    private static final String TAG = MainActivity.TAG;

    // 是否记录 Thread.sleep() 的计划休眠时间和实际休眠时间，用于调试设备的休眠精度。
    // 正常播放时关闭，避免每一帧产生额外日志和测量开销。
    private static final boolean CHECK_SLEEP_TIME = false;

    // 一秒对应的微秒数。视频 PTS 和本类内部的时间变量都使用微秒作为单位。
    private static final long ONE_MILLION = 1000000L;

    // 上一帧在视频时间轴上的 PTS，单位微秒。
    private long mPrevPresentUsec;

    // 上一帧对应的单调时钟时间，单位微秒。
    // 它表示“播放器的现实时间轴”走到哪里，而 mPrevPresentUsec 表示“视频时间轴”走到哪里。
    private long mPrevMonoUsec;

    // 固定帧率模式下相邻两帧之间的固定间隔，单位微秒。
    // 为 0 表示使用视频文件本身的 PTS，不启用固定帧率模式。
    private long mFixedFrameDurationUsec;

    // MoviePlayer 循环播放后会调用 loopReset()，下一帧到来时在这里处理时间轴重置。
    private boolean mLoopReset;

    /**
     * 设置固定播放帧率。
     *
     * <p>启用后，播放器忽略视频文件中相邻帧的 PTS 差值，统一按照
     * {@code 1_000_000 / fps} 微秒的间隔提交帧。例如传入 60，帧间隔约为 16,666 微秒。</p>
     *
     * <p>该设置必须在播放线程开始前完成；本类没有为播放过程中修改帧率提供线程同步。</p>
     *
     * @param fps 目标帧率，必须大于 0。
     */
    public void setFixedPlaybackRate(int fps) {
        mFixedFrameDurationUsec = ONE_MILLION / fps;
    }

    /**
     * 在即将把一帧提交给 Surface 前调用，用于控制帧提交时间。
     *
     * <p>调用时序来自 MoviePlayer：</p>
     *
     * <pre>
     * dequeueOutputBuffer()
     *     ↓
     * preRender(presentationTimeUs)  ← 本方法可能等待
     *     ↓
     * releaseOutputBuffer(outputIndex, true)
     *     ↓
     * postRender()
     * </pre>
     *
     * <p>首帧只建立“视频时间轴”和“单调时钟”的对应关系，不等待。之后每一帧按照
     * 当前 PTS 与上一帧 PTS 的差值推算目标现实时间，并在提交前等待到该时间。</p>
     *
     * <p>运行线程：MoviePlayer 的解码线程。不要从 UI 线程直接调用。</p>
     *
     * @param presentationTimeUsec 当前输出帧的展示时间戳，单位微秒。
     */
    @Override
    public void preRender(long presentationTimeUsec) {
        // 对首帧来说，没有上一帧可以计算间隔。因此只记录两个时间轴的起点：
        //   视频时间轴：presentationTimeUsec
        //   现实时间轴：当前单调时钟
        // 后续帧会以这两个起点为基础安排提交时间。
        //
        // 如果视频帧率高于屏幕刷新率，理想的显示系统应当丢弃多余帧；但这里并不直接
        // 控制 VSYNC，也不保证所有 Android 版本和设备都会按预期自动丢帧。

        if (mPrevMonoUsec == 0) {
            // 锁存首帧的时间基准后立即返回，首帧不需要等待。
            mPrevMonoUsec = System.nanoTime() / 1000;
            mPrevPresentUsec = presentationTimeUsec;
        } else {
            // 计算上一帧到当前帧之间应该经过多长时间。
            long frameDelta;
            if (mLoopReset) {
                // 循环播放时，新一轮的 PTS 通常会从接近 0 的位置重新开始，而上一轮的
                // mPrevPresentUsec 还停留在文件末尾。如果直接相减，会得到一个很大的负数。
                //
                // MoviePlayer 没有告诉我们上一轮最后一帧实际应该停留多久，因此这里假设
                // 循环边界的帧间隔约为 1/30 秒，让第一帧循环帧以合理的节奏接上。
                // 更精确的做法可以使用上一帧间隔或多帧平均间隔。
                mPrevPresentUsec = presentationTimeUsec - ONE_MILLION / 30;
                mLoopReset = false;
            }
            if (mFixedFrameDurationUsec != 0) {
                // 固定帧率模式：忽略文件 PTS，使用调用者指定的固定帧间隔。
                frameDelta = mFixedFrameDurationUsec;
            } else {
                // 普通模式：使用媒体中当前帧与上一帧的 PTS 差值。
                frameDelta = presentationTimeUsec - mPrevPresentUsec;
            }
            if (frameDelta < 0) {
                // 时间戳倒退通常表示文件时间戳异常，或视频存在非预期的时间轴变化。
                // 不能倒退现实时间，所以把本帧间隔钳制为 0，尽快提交当前帧。
                Log.w(TAG, "Weird, video times went backward");
                frameDelta = 0;
            } else if (frameDelta == 0) {
                // 两帧 PTS 相同，意味着它们理论上应该同时出现；这通常提示素材生成过程
                // 可能没有正确递增时间戳，但仍允许播放器继续工作。
                Log.i(TAG, "Warning: current frame and previous frame had same timestamp");
            } else if (frameDelta > 10 * ONE_MILLION) {
                // 理论上相邻帧间隔可以任意长，但本播放器不希望因为一个异常时间戳卡住很久。
                // 例如把纳秒误当成微秒时，间隔可能会大几个数量级。
                // 这里记录日志，并把单次等待最多限制为 5 秒。
                Log.i(TAG, "Inter-frame pause was " + (frameDelta / ONE_MILLION) +
                        "sec, capping at 5 sec");
                frameDelta = 5 * ONE_MILLION;
            }

            // 目标现实时间 = 上一帧目标现实时间 + 当前帧应经过的时间。
            // 使用上一帧的目标时间，而不是调用此方法时的当前时间，可以避免解码耗时和
            // 线程调度延迟不断被累加到后续帧中。
            long desiredUsec = mPrevMonoUsec + frameDelta;
            long nowUsec = System.nanoTime() / 1000;

            // 在目标时间之前等待。预留 100 微秒的提前量，是因为 sleep 的实际精度受设备、
            // OS 调度器和线程状态影响；距离目标已经很近时继续 sleep 反而更容易睡过头。
            while (nowUsec < (desiredUsec - 100) /*&& mState == RUNNING*/) {
                // 最多休眠 500ms 后重新检查一次。这样即使目标时间很远，也能定期响应
                // 停止条件；当前代码没有直接使用 mState，而是保留了分段等待的结构。
                //
                // Thread.sleep() 的精度在不同设备上差异很大，可能提前或延后唤醒；同时
                // 线程也可能受到系统调度影响。因此醒来后必须重新读取单调时钟，而不能
                // 假定 sleep 已经精确等待了请求的时长。
                long sleepTimeUsec = desiredUsec - nowUsec;
                if (sleepTimeUsec > 500000) {
                    sleepTimeUsec = 500000;
                }
                try {
                    if (CHECK_SLEEP_TIME) {
                        // 调试模式：测量实际休眠时间，观察设备的 sleep 误差。
                        long startNsec = System.nanoTime();
                        Thread.sleep(sleepTimeUsec / 1000, (int) (sleepTimeUsec % 1000) * 1000);
                        long actualSleepNsec = System.nanoTime() - startNsec;
                        Log.d(TAG, "sleep=" + sleepTimeUsec + " actual=" + (actualSleepNsec/1000) +
                                " diff=" + (Math.abs(actualSleepNsec / 1000 - sleepTimeUsec)) +
                                " (usec)");
                    } else {
                        // Thread.sleep() 的第一个参数是毫秒，第二个参数是额外纳秒。
                        // 将微秒拆成 ms + ns，尽量保留本类计算出的等待精度。
                        Thread.sleep(sleepTimeUsec / 1000, (int) (sleepTimeUsec % 1000) * 1000);
                    }
                } catch (InterruptedException ie) {
                    // 当前实现把中断视为一次提前唤醒：重新读取时钟并继续判断是否需要等待。
                    // 如果要把中断作为停止信号，应在这里设置停止状态并直接返回。
                }
                nowUsec = System.nanoTime() / 1000;
            }

            // 使用“计算出的目标时间”推进两个时间轴，而不是使用实际唤醒后的 nowUsec。
            // 实际唤醒时间可能早于或晚于目标时间；若把它直接作为下一帧基准，误差会逐帧
            // 累积，最终造成播放越来越快或越来越慢。这里的推进方式可以抑制这种漂移。
            mPrevMonoUsec += frameDelta;
            mPrevPresentUsec += frameDelta;
        }
    }

    /**
     * 帧已经提交给 MediaCodec/Surface 后的回调。
     *
     * <p>当前播放器不需要在提交后执行额外操作，因此为空实现。注意：该方法返回并不
     * 表示画面已经真正显示在屏幕上，只表示输出缓冲区提交调用已经返回。</p>
     *
     * <p>运行线程：MoviePlayer 的解码线程。</p>
     */
    @Override public void postRender() {}

    /**
     * 通知本类下一次 {@link #preRender(long)} 将处理循环播放的新时间轴。
     *
     * <p>MoviePlayer 在最后一帧输出并完成 Extractor/Decoder 重置后调用此方法。这里不立即
     * 清零时间变量，是因为下一帧的实际 PTS 只有在 {@code preRender()} 中才能取得。</p>
     *
     * <p>运行线程：MoviePlayer 的解码线程。</p>
     */
    @Override
    public void loopReset() {
        mLoopReset = true;
    }
}
