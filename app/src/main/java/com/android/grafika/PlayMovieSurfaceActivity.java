/*
 * Copyright 2014 Google Inc. All rights reserved.
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

import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;
import android.app.Activity;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.WindowSurface;

import java.io.File;
import java.io.IOException;

/**
 * 从应用私有目录选择视频，并将解码结果输出到 {@link SurfaceView} 的示例 Activity。
 *
 * <p>本类主要负责界面、Surface 生命周期和播放器线程的协调；真正的媒体处理由
 * {@link MoviePlayer} 完成。播放链路可以概括为：</p>
 *
 * <pre>
 * SurfaceView
 *     └─ SurfaceHolder 创建 Surface
 *             └─ MoviePlayer 将 MediaCodec 配置为输出到 Surface
 *                     └─ MediaExtractor 读取压缩样本
 *                             └─ MediaCodec 解码并提交视频帧
 * </pre>
 *
 * <p>本类和 {@link PlayMovieActivity} 的主要区别是输出目标不同：</p>
 * <ul>
 *   <li>{@code TextureView} 像普通 View 一样参与应用窗口，可以方便地使用矩阵缩放、平移
 *       和旋转；</li>
 *   <li>{@code SurfaceView} 在窗口中提供一个独立的 Surface 图层，系统合成器可以直接对它
 *       进行合成，长时间播放时通常更节省功耗；</li>
 *   <li>SurfaceView 的内容不完全受普通 View 矩阵控制，保持视频宽高比需要调整外层布局，
 *       本类使用 {@link AspectFrameLayout} 完成这件事；</li>
 *   <li>某些受保护内容要求解码器直接输出到可由硬件合成器处理的 Surface，SurfaceView
 *       是应用侧常用的承载方式。</li>
 * </ul>
 *
 * <p>MediaCodec 会按照视频尺寸向输出 Surface 请求缓冲区，系统再把视频缩放到 SurfaceView
 * 的显示区域。因此这里不调用 {@link SurfaceHolder#setFixedSize(int, int)}，而是通过调整
 * View 布局来保持正确的宽高比。实际的“读取样本 → 解码 → 提交帧”流程与 TextureView
 * 播放完全相同，区别只在于输出 Surface 的来源。</p>
 */
public class PlayMovieSurfaceActivity extends Activity implements OnItemSelectedListener,
        SurfaceHolder.Callback, MoviePlayer.PlayerFeedback {
    private static final String TAG = MainActivity.TAG;

    // SurfaceView 是一个 View 外壳，真正接收 MediaCodec 输出的是它的 Surface。
    private SurfaceView mSurfaceView;
    // getFilesDir() 下可供选择的 MP4 文件名；数组中只保存文件名，不保存完整路径。
    private String[] mMovieFiles;
    // Spinner 当前选中文件在 mMovieFiles 中的索引。
    private int mSelectedMovie;
    // UI 是否显示 Stop 状态。真正的播放结束以 playbackStopped() 回调为准。
    private boolean mShowStopLabel;
    // 当前播放任务；为 null 表示没有正在运行或等待结束的播放器。
    private MoviePlayer.PlayTask mPlayTask;
    // SurfaceHolder 是否已经通过 surfaceCreated() 进入可用状态。
    private boolean mSurfaceHolderReady = false;

    /**
     * 获取本 Activity 使用的布局资源 ID。
     *
     * <p>保留为可重写方法，是为了让子类替换布局；替换后的布局仍需提供与默认布局
     * 兼容的控件，至少包括播放按钮、文件 Spinner、{@link AspectFrameLayout} 和
     * {@link SurfaceView}。</p>
     */
    protected int getContentViewId() {
        return R.layout.activity_play_movie_surface;
    }

    /**
     * 初始化 SurfaceView、SurfaceHolder 回调和影片文件列表。
     *
     * <p>此时 SurfaceView 对象可能已经创建，但其底层 Surface 尚未创建完成。因此播放按钮
     * 的最终可用状态要等到 {@link #surfaceCreated(SurfaceHolder)} 回调后再确定。</p>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getContentViewId());

        mSurfaceView = (SurfaceView) findViewById(R.id.playMovie_surface);
        // SurfaceHolder.Callback 用于接收 Surface 创建、尺寸变化和销毁事件。
        mSurfaceView.getHolder().addCallback(this);

        // 扫描应用私有目录中所有 MP4 文件，文件名显示在 Spinner 中。
        Spinner spinner = (Spinner) findViewById(R.id.playMovieFile_spinner);
        mMovieFiles = MiscUtils.getFiles(getFilesDir(), "*.mp4");
        // simple_spinner_item 用于 Spinner 收起后的显示，dropdown 布局用于展开列表。
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mMovieFiles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        // 初始时 Surface 可能尚未就绪，因此这里通常会禁用播放按钮。
        updateControls();
    }

    /** Activity 回到前台时记录生命周期；本示例不会在这里自动恢复播放。 */
    @Override
    protected void onResume() {
        Log.d(TAG, "PlayMovieSurfaceActivity onResume");
        super.onResume();
    }

    /**
     * Activity 即将离开前台时停止播放，并等待播放线程完成清理。
     *
     * <p>停止请求本身是异步的：{@link #stopPlayback()} 只设置播放器的停止标志。随后调用
     * {@link MoviePlayer.PlayTask#waitForStop()}，确保 {@code MoviePlayer.play()} 已经返回，
     * Decoder 和 Extractor 已经释放，避免 SurfaceView 被销毁后后台线程仍提交视频帧。</p>
     */
    @Override
    protected void onPause() {
        Log.d(TAG, "PlayMovieSurfaceActivity onPause");
        super.onPause();
        // 当前实现不保存播放进度；旋转或进入后台时结束本次播放。
        if (mPlayTask != null) {
            stopPlayback();
            // 等待后台任务退出后再让 SurfaceView 生命周期继续收尾。
            mPlayTask.waitForStop();
        }
    }

    /**
     * SurfaceHolder 对应的 Surface 创建完成。
     *
     * <p>只有从这里开始，{@link SurfaceHolder#getSurface()} 才能返回可用于
     * {@link android.media.MediaCodec#configure(android.media.MediaFormat, Surface, android.media.MediaCrypto, int)}
     * 的输出 Surface。Surface 未就绪时启动播放器可能导致配置失败或黑屏。</p>
     */
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");
        mSurfaceHolderReady = true;
        // Surface 可以作为 MediaCodec 的输出目标，允许用户开始播放。
        updateControls();
    }

    /**
     * Surface 的格式或尺寸发生变化。
     *
     * <p>{@code format} 是 Surface 的像素格式，{@code width/height} 是 Surface 当前尺寸。
     * 示例只记录日志，不在这里重建播放器；外层 {@link AspectFrameLayout} 负责保持视频比例。</p>
     */
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // ignore
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height);
    }

    /**
     * Surface 即将销毁。
     *
     * <p>当前生命周期设计假定 Activity 会在此之前通过 {@link #onPause()} 停止并等待
     * 播放任务，因此这里不主动操作播放器或释放从 Holder 获取的 Surface。下一次
     * {@link #surfaceCreated(SurfaceHolder)} 到来后，SurfaceHolder 会提供新的有效 Surface。</p>
     */
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // ignore
        Log.d(TAG, "Surface destroyed");
    }

    /**
     * Spinner 选择变化时保存当前影片索引。
     *
     * <p>这里只更新选择状态，不打开文件；文件路径和 MoviePlayer 会在点击播放时创建。</p>
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        // mMovieFiles 只保存文件名，真正路径在 clickPlayStop() 中与 getFilesDir() 拼接。
        mSelectedMovie = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + mSelectedMovie + " '" + mMovieFiles[mSelectedMovie] + "'");
    }

    /** 没有选中项时不做额外处理。 */
    @Override public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * “播放/停止”按钮的点击处理方法，由布局中的 {@code android:onClick} 调用。
     *
     * <p>停止分支只发送异步停止请求；播放分支则依次创建帧速率回调、输出 Surface、
     * MoviePlayer 和 PlayTask，最后启动后台播放线程。</p>
     *
     * @param unused Android XML onClick 传入的按钮 View，本方法不需要使用。
     */
    public void clickPlayStop(@SuppressWarnings("unused") View unused) {
        if (mShowStopLabel) {
            Log.d(TAG, "stopping movie");
            // 不在这里立刻恢复按钮，因为后台线程可能仍在提交最后几帧。
            // playbackStopped() 收到通知后再更新 UI 状态。
            stopPlayback();
            //mShowStopLabel = false;
            //updateControls();
        } else {
            // 防止旧任务尚未完成时重复创建播放器。
            if (mPlayTask != null) {
                Log.w(TAG, "movie already playing");
                return;
            }

            Log.d(TAG, "starting movie");
            // MoviePlayer 在提交每个输出帧前调用它，根据 PTS 控制播放节奏。
            SpeedControlCallback callback = new SpeedControlCallback();
            SurfaceHolder holder = mSurfaceView.getHolder();
            // surfaceCreated() 已经保证这里取得的是有效的 Surface。
            Surface surface = holder.getSurface();

            // 清除上一部视频残留的最后一帧。若新旧视频宽高比不同，残留画面会尤其明显。
            clearSurface(surface);

            MoviePlayer player = null;
            try {
                 // MoviePlayer 会在构造时读取视频尺寸，在 play() 中重新创建 Extractor 和 Decoder。
                 player = new MoviePlayer(
                        new File(getFilesDir(), mMovieFiles[mSelectedMovie]), surface, callback);
            } catch (IOException ioe) {
                Log.e(TAG, "Unable to play movie", ioe);
                // 播放器创建失败时，本方法仍然负责释放刚创建的 Surface 引用。
                surface.release();
                return;
            }

            // 设置外层布局的目标宽高比；SurfaceView 本身仍占满 AspectFrameLayout 的内容区域。
            AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.playMovie_afl);
            int width = player.getVideoWidth();
            int height = player.getVideoHeight();
            layout.setAspectRatio((double) width / height);
            // 不设置 Surface 的固定缓冲区尺寸，让 MediaCodec 根据视频尺寸请求缓冲区。
            //holder.setFixedSize(width, height);

            // PlayTask 将阻塞式播放放到后台线程，并在结束后回调 playbackStopped()。
            mPlayTask = new MoviePlayer.PlayTask(player, this);

            // 先更新 UI，再启动线程，避免快速点击时重复创建播放任务。
            mShowStopLabel = true;
            updateControls();
            mPlayTask.execute();
        }
    }

    /**
     * 如果存在播放任务，异步请求停止当前影片。
     *
     * <p>此方法不会等待停止完成，也不会立即清空 mPlayTask；任务结束后由
     * {@link #playbackStopped()} 统一完成 UI 收尾。</p>
     */
    private void stopPlayback() {
        if (mPlayTask != null) {
            mPlayTask.requestStop();
        }
    }

    /**
     * 播放任务完成后的回调，在 PlayTask 的 Handler 所在线程执行（通常是 UI 线程）。
     *
     * <p>此时 MoviePlayer 已经退出 play() 并释放 Decoder/Extractor，Activity 可以安全地
     * 允许用户开始下一次播放。</p>
     */
    @Override   // MoviePlayer.PlayerFeedback
    public void playbackStopped() {
        Log.d(TAG, "playback stopped");
        mShowStopLabel = false;
        mPlayTask = null;
        updateControls();
    }

    /**
     * 根据播放状态和 Surface 是否就绪刷新界面控件。
     *
     * <p>播放按钮的文字由 mShowStopLabel 决定，可用性由 mSurfaceHolderReady 决定。
     * SurfaceView 选项没有额外的帧率/循环 CheckBox，因此这里只更新播放按钮。</p>
     */
    private void updateControls() {
        Button play = (Button) findViewById(R.id.play_stop_button);
        if (mShowStopLabel) {
            play.setText(R.string.stop_button_text);
        } else {
            play.setText(R.string.play_button_text);
        }
        play.setEnabled(mSurfaceHolderReady);
    }

    /**
     * 使用 OpenGL 将播放 Surface 清成黑色。
     *
     * <p>清理上一部视频最后一帧的目的，是避免新视频开始前短暂显示旧画面，尤其避免新旧
     * 视频宽高比不同时出现残留区域。</p>
     *
     * <p>这里使用 EGL/OpenGL，而不是 Canvas：</p>
     * <ul>
     *   <li>Surface 后续要交给 MediaCodec 或其他图形生产者使用，避免先建立不合适的
     *       Canvas 软件渲染连接；</li>
     *   <li>OpenGL 直接向 Surface 的图形缓冲区清色，和 SurfaceView 的图形输出路径一致。</li>
     * </ul>
     *
     * <p>操作顺序是：创建 EGL 上下文 → 将 Surface 包装为 WindowSurface → 绑定为当前窗口
     * → 清除颜色缓冲区 → 交换缓冲区使清屏结果显示 → 释放窗口和 EGL 资源。</p>
     *
     * @param surface 要清除的播放 Surface，必须处于有效状态。
     */
    private void clearSurface(Surface surface) {
        // Surface 尺寸变化后新增区域默认可能是黑色；这里在当前尺寸下重新清屏，确保整个
        // Surface 都不会留下上一部视频的内容。
        EglCore eglCore = new EglCore();
        // false 表示这里不需要记录可见内容或其他特殊窗口配置，直接使用目标 Surface。
        WindowSurface win = new WindowSurface(eglCore, surface, false);
        // 后续 GLES20 调用作用于这个 Surface 对应的 EGL 绘制目标。
        win.makeCurrent();
        // Alpha 设为 0，但清除的是 Surface 的颜色缓冲区；最终效果是黑色背景。
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // 将清除后的缓冲区提交给 SurfaceView，否则清屏命令仍只停留在当前 back buffer。
        win.swapBuffers();
        // 清屏完成后必须断开 EGL 与 Surface 的连接，避免影响后续 MediaCodec 使用该 Surface。
        win.release();
        eglCore.release();
    }
}
