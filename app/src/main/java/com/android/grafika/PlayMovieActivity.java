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

import android.os.Bundle;
import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;

import java.io.File;
import java.io.IOException;

/**
 * 从应用私有目录选择影片，并将视频画面播放到 {@link TextureView} 的示例 Activity。
 *
 * <p>本类主要负责界面和生命周期协调，具体的解封装、解码与帧输出由
 * {@link MoviePlayer} 完成。一次播放的完整流程如下：</p>
 *
 * <pre>
 * onCreate
 *   ├─ 初始化 TextureView，并监听 SurfaceTexture 生命周期
 *   └─ 扫描 filesDir 下的 MP4 文件，填充 Spinner
 *
 * onSurfaceTextureAvailable
 *   └─ SurfaceTexture 可用，启用播放按钮
 *
 * clickPlayStop（播放分支）
 *   ├─ 创建 SpeedControlCallback，负责按照时间戳控制出帧节奏
 *   ├─ SurfaceTexture → Surface
 *   ├─ 创建 MoviePlayer 和后台 PlayTask
 *   ├─ 调整 TextureView 的变换矩阵，保持视频宽高比
 *   └─ 启动播放线程
 *
 * clickPlayStop（停止分支）/ onPause
 *   └─ 向播放线程发送停止请求
 *
 * playbackStopped
 *   └─ 播放线程已结束，恢复界面状态
 * </pre>
 *
 * <p>这里仅播放视频轨道，不处理音频。与使用 {@link android.view.SurfaceView} 的
 * {@link PlayMovieSurfaceActivity} 相比，TextureView 属于普通 View 层级，可以直接使用
 * {@link Matrix} 缩放、平移或旋转画面；代价是画面通常需要经过应用窗口的合成路径。</p>
 *
 * <p>Activity 不保存播放进度。进入暂停状态（例如切到后台或旋转屏幕）时，会停止当前
 * 播放并等待解码线程退出，避免已经失效的 SurfaceTexture 继续接收视频帧。</p>
 */
public class PlayMovieActivity extends Activity implements OnItemSelectedListener,
        TextureView.SurfaceTextureListener, MoviePlayer.PlayerFeedback {
    private static final String TAG = MainActivity.TAG;

    // 视频画面的显示控件。MoviePlayer 实际输出到它内部 SurfaceTexture 包装成的 Surface。
    private TextureView mTextureView;
    // 应用私有 filesDir 中所有可选的 MP4 文件名，内容同时显示在 Spinner 中。
    private String[] mMovieFiles;
    // Spinner 当前选中项在 mMovieFiles 中的索引。
    private int mSelectedMovie;
    // true 表示界面处于“正在播放/正在停止”状态，此时按钮文字显示为 Stop。
    // 它是 UI 状态，不等同于播放线程一定仍在解码；最终以 playbackStopped() 回调为准。
    private boolean mShowStopLabel;
    // 一次播放对应一个 PlayTask；null 表示当前没有活动的播放任务。
    private MoviePlayer.PlayTask mPlayTask;
    // TextureView 背后的 SurfaceTexture 是否已经创建完成。未就绪时不能启动解码器。
    private boolean mSurfaceTextureReady = false;

    // 当前实现未使用的遗留字段；实际停止同步由 MoviePlayer.PlayTask 内部完成。
    private final Object mStopper = new Object();

    /**
     * 创建界面、注册 TextureView 生命周期监听器，并加载可播放文件列表。
     *
     * <p>此时 TextureView 对象虽然已经存在，但它背后的 SurfaceTexture 不一定已经创建，
     * 因此最后调用 updateControls() 时，播放按钮通常仍是禁用状态。</p>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play_movie);

        mTextureView = (TextureView) findViewById(R.id.movie_texture_view);
        // Activity 实现 SurfaceTextureListener，以便只在底层输出目标可用时允许播放。
        mTextureView.setSurfaceTextureListener(this);

        // 用应用私有目录中的 MP4 文件填充影片选择列表。
        Spinner spinner = (Spinner) findViewById(R.id.playMovieFile_spinner);
        // MiscUtils.getFiles() 返回匹配通配符的文件名，不包含 filesDir 的完整路径。
        mMovieFiles = MiscUtils.getFiles(getFilesDir(), "*.mp4");
        // simple_spinner_item 是 Spinner 收起时的行布局；dropdown 布局用于展开后的列表项。
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mMovieFiles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // 绑定数据并监听选择变化；onItemSelected() 会保存当前文件索引。
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        // 根据 SurfaceTexture 和播放状态设置按钮文字及可用性。
        updateControls();
    }

    /** Activity 回到前台时记录生命周期；播放并不会在这里自动恢复。 */
    @Override
    protected void onResume() {
        Log.d(TAG, "PlayMovieActivity onResume");
        super.onResume();
    }

    /**
     * Activity 即将离开前台时停止播放，并同步等待播放线程退出。
     *
     * <p>{@link #stopPlayback()} 本身只是异步设置停止标志；紧接着调用 waitForStop()，
     * 是为了确保 MoviePlayer 不会在 TextureView/SurfaceTexture 被销毁后继续提交帧。
     * 这里会短暂阻塞 UI 线程，但换来了明确的资源生命周期边界。</p>
     */
    @Override
    protected void onPause() {
        Log.d(TAG, "PlayMovieActivity onPause");
        super.onPause();
        // 当前实现不跨 Activity 重建保存播放器及播放进度，所以暂停时直接结束本次播放。
        if (mPlayTask != null) {
            stopPlayback();
            // 必须在非播放线程调用；返回时 play() 的 finally 已经完成解码资源清理。
            mPlayTask.waitForStop();
        }
    }

    /**
     * TextureView 的 SurfaceTexture 创建完成时调用。
     *
     * <p>只有收到此回调后，才能用 SurfaceTexture 创建供 MediaCodec 输出的 Surface。
     * width/height 是当前 TextureView 输出区域的尺寸，不是视频文件的编码尺寸。</p>
     */
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        Log.d(TAG, "SurfaceTexture ready (" + width + "x" + height + ")");
        mSurfaceTextureReady = true;
        // SurfaceTexture 已经可以接收缓冲区，现在允许用户点击播放。
        updateControls();
    }

    /**
     * TextureView 尺寸变化回调。
     *
     * <p>示例当前忽略运行期间的尺寸变化；宽高比矩阵只在开始播放时重新计算。</p>
     */
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        // ignore
    }

    /**
     * TextureView 的 SurfaceTexture 即将销毁时调用。
     *
     * @return true 表示由 TextureView/框架负责释放传入的 SurfaceTexture；Activity 不再持有它。
     */
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        mSurfaceTextureReady = false;
        // 通常该回调紧随 onPause()，界面即将不可见，因此这里不再刷新按钮状态。
        return true;
    }

    /**
     * SurfaceTexture 中出现新画面后调用。
     *
     * <p>画面已经由 MediaCodec 直接输出，本 Activity 不需要逐帧读取或处理纹理。</p>
     */
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // ignore
    }

    /**
     * 用户在 Spinner 中选择影片时保存选中索引。
     *
     * <p>这里只记录选择，不立即打开文件。真正的 File 和 MoviePlayer 会在用户点击播放时创建，
     * 从而避免仅浏览列表就占用媒体资源。</p>
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        // 也可以直接使用 pos；这里从 Spinner 再读取一次当前项，表达“以控件当前状态为准”。
        mSelectedMovie = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + mSelectedMovie + " '" + mMovieFiles[mSelectedMovie] + "'");
    }

    /** Spinner 没有选中项时无需处理；播放按钮的其他状态仍由 updateControls() 管理。 */
    @Override public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * “播放/停止”共用按钮的点击处理方法，由布局中的 {@code android:onClick} 调用。
     *
     * <p>方法根据 mShowStopLabel 分成两条路径：</p>
     * <ul>
     *   <li>正在播放：只发送停止请求，等待后台任务通过 playbackStopped() 确认结束；</li>
     *   <li>未播放：准备帧速控制、Surface、MoviePlayer 和 PlayTask，然后启动后台线程。</li>
     * </ul>
     *
     * @param unused Android XML onClick 约定传入的 View，本方法不需要使用它。
     */
    public void clickPlayStop(@SuppressWarnings("unused") View unused) {
        if (mShowStopLabel) {
            Log.d(TAG, "stopping movie");
            // 这是异步请求。按钮仍显示 Stop，直到 playbackStopped() 确认线程已经退出。
            stopPlayback();
            // 不在此处提前恢复界面，否则用户可能在旧任务尚未结束时启动第二个播放任务。
            //mShowStopLabel = false;
            //updateControls();
        } else {
            // 防御性检查：正常情况下 mShowStopLabel=false 时 mPlayTask 也应当为 null。
            if (mPlayTask != null) {
                Log.w(TAG, "movie already playing");
                return;
            }
            Log.d(TAG, "starting movie");

            // MoviePlayer 会在提交每个解码帧到 Surface 前调用此对象。默认根据视频 PTS
            // 计算等待时间，使播放速度接近原始素材速度。
            SpeedControlCallback callback = new SpeedControlCallback();
            if (((CheckBox) findViewById(R.id.locked60fps_checkbox)).isChecked()) {
                // 固定 60fps 时忽略文件中相邻帧的 PTS 差值，按约 16.67ms 的间隔提交画面。
                // TODO: consider changing this to be "free running" mode
                callback.setFixedPlaybackRate(60);
            }

            // SurfaceTexture 是 TextureView 持有的图像缓冲区消费者；Surface 是生产者侧包装，
            // MediaCodec 通过它把解码帧写入 TextureView。播放按钮只有在 ST 就绪后才可点击。
            SurfaceTexture st = mTextureView.getSurfaceTexture();
            Surface surface = new Surface(st);
            MoviePlayer player = null;
            try {
                 // 文件名来自 Spinner，配合 filesDir 还原为完整的应用私有文件路径。
                 player = new MoviePlayer(
                        new File(getFilesDir(), mMovieFiles[mSelectedMovie]), surface, callback);
            } catch (IOException ioe) {
                Log.e(TAG, "Unable to play movie", ioe);
                // MoviePlayer 没有成功接管播放流程，立即释放刚创建的 Surface 包装对象。
                surface.release();
                return;
            }

            // MoviePlayer 构造时已从 MediaFormat 读取视频宽高；在第一帧出现前设置显示矩阵。
            adjustAspectRatio(player.getVideoWidth(), player.getVideoHeight());

            // PlayTask 把阻塞式 player.play() 放到后台线程，并把结束通知回调给当前 Activity。
            mPlayTask = new MoviePlayer.PlayTask(player, this);
            if (((CheckBox) findViewById(R.id.loopPlayback_checkbox)).isChecked()) {
                mPlayTask.setLoopMode(true);
            }

            // 在线程启动前先切换 UI 状态，避免快速重复点击创建多个并行播放器。
            mShowStopLabel = true;
            updateControls();
            mPlayTask.execute();
        }
    }

    /**
     * 如果存在播放任务，向它发送异步停止请求。
     *
     * <p>此方法不会等待解码器停止，也不会清空 mPlayTask；真正结束后由
     * {@link #playbackStopped()} 统一收尾。</p>
     */
    private void stopPlayback() {
        if (mPlayTask != null) {
            mPlayTask.requestStop();
        }
    }

    /**
     * 播放任务结束后在 UI 线程执行的回调。
     *
     * <p>无论影片自然播完还是用户请求停止，PlayTask 都会在 play() 返回并完成资源清理后
     * 调用这里。此时可以安全地允许用户开始下一次播放。</p>
     */
    @Override   // MoviePlayer.PlayerFeedback
    public void playbackStopped() {
        Log.d(TAG, "playback stopped");
        mShowStopLabel = false;
        mPlayTask = null;
        updateControls();
    }

    /**
     * 设置 TextureView 的变换矩阵，在不裁剪、不拉伸的前提下保持视频宽高比。
     *
     * <p>计算方式类似 {@code centerInside}：</p>
     * <ol>
     *   <li>比较 View 的可用比例和视频比例，判断由宽度还是高度限制画面；</li>
     *   <li>计算保持比例后的 newWidth/newHeight；</li>
     *   <li>按目标尺寸缩放，并将剩余空间的一半作为偏移，使画面居中。</li>
     * </ol>
     *
     * <p>没有被视频占满的区域会形成上下或左右留白。Matrix 只改变 TextureView 内容的
     * 显示方式，不会改变视频解码分辨率，也不会要求 MediaCodec 重新配置。</p>
     *
     * @param videoWidth 视频编码宽度，单位为像素。
     * @param videoHeight 视频编码高度，单位为像素。
     */
    private void adjustAspectRatio(int videoWidth, int videoHeight) {
        // View 尺寸决定画面最多能显示多大；视频尺寸只用于计算比例。
        int viewWidth = mTextureView.getWidth();
        int viewHeight = mTextureView.getHeight();
        // 这里使用“高/宽”，因此给定宽度时，高度 = 宽度 * aspectRatio。
        double aspectRatio = (double) videoHeight / videoWidth;

        int newWidth, newHeight;
        if (viewHeight > (int) (viewWidth * aspectRatio)) {
            // View 相对更高：宽度先达到上限，高度按视频比例缩小，形成上下留白。
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        } else {
            // View 相对更宽：高度先达到上限，宽度按视频比例缩小，形成左右留白。
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        }
        // 两侧剩余空间均分，得到内容左上角相对 View 的居中偏移。
        int xoff = (viewWidth - newWidth) / 2;
        int yoff = (viewHeight - newHeight) / 2;
        Log.v(TAG, "video=" + videoWidth + "x" + videoHeight +
                " view=" + viewWidth + "x" + viewHeight +
                " newView=" + newWidth + "x" + newHeight +
                " off=" + xoff + "," + yoff);

        Matrix txform = new Matrix();
        // 读取当前矩阵后再设置目标变换；setScale 会把矩阵主体设为指定缩放比例。
        mTextureView.getTransform(txform);
        txform.setScale((float) newWidth / viewWidth, (float) newHeight / viewHeight);
        //txform.postRotate(10);          // just for fun
        // 缩放后平移到中央，防止缩小后的内容贴在 TextureView 左上角。
        txform.postTranslate(xoff, yoff);
        mTextureView.setTransform(txform);
    }

    /**
     * 根据当前播放状态和 SurfaceTexture 状态统一刷新界面控件。
     *
     * <p>把控件状态更新集中在这里，可以确保 onCreate、SurfaceTexture 就绪、开始播放和
     * 播放结束四个入口使用相同规则：</p>
     * <ul>
     *   <li>播放中显示 Stop，否则显示 Play；</li>
     *   <li>SurfaceTexture 未就绪时禁用播放按钮；</li>
     *   <li>播放期间锁定帧率和循环选项，避免后台任务运行中途改变配置。</li>
     * </ul>
     */
    private void updateControls() {
        Button play = (Button) findViewById(R.id.play_stop_button);
        if (mShowStopLabel) {
            play.setText(R.string.stop_button_text);
        } else {
            play.setText(R.string.play_button_text);
        }
        play.setEnabled(mSurfaceTextureReady);

        // 当前实现只在创建任务时读取这些选项，不支持播放中动态修改，因此播放期间禁用。
        CheckBox check = (CheckBox) findViewById(R.id.locked60fps_checkbox);
        check.setEnabled(!mShowStopLabel);
        check = (CheckBox) findViewById(R.id.loopPlayback_checkbox);
        check.setEnabled(!mShowStopLabel);
    }
}
