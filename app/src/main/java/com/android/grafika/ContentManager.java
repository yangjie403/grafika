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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;

/**
 * 管理应用运行所需的预生成媒体内容。
 *
 * <p>Grafika 中有一些播放和解码实验依赖固定的测试视频。ContentManager 负责：
 * 保存这些视频的标识和文件名、检查文件是否已经存在、在首次启动或用户主动操作时
 * 生成视频，以及向播放 Activity 提供视频文件路径。</p>
 *
 * <p>这里采用“首次启动时一次性生成”的方式，而不是在每个 Activity 第一次使用时按需生成。
 * 生成过程通过 {@link AsyncTask} 放到后台线程执行，进度通过 {@link ProgressUpdater} 回传
 * 到 UI 线程。生成好的 MP4 位于 {@link Context#getFilesDir()} 返回的应用私有目录中。</p>
 *
 * <p>类使用单例加锁保护初始化和实例创建，ContentManager 自身可以被多个线程访问。
 * 但传入的 Activity、AlertDialog 和 UI 控件仍然只能在主线程操作。</p>
 */
public class ContentManager {
    /** 与 MainActivity 统一的日志标签。 */
    private static final String TAG = MainActivity.TAG;

    // 内容标识同时作为 mContent ArrayList 的下标，因此必须从 0 开始连续排列，不能使用稀疏值。
    // TODO: 可以考虑改成 String + HashMap；当前 prepare() 依赖内容按 tag 顺序加入列表，较脆弱。
    /** 八个矩形动画测试视频的标识。 */
    public static final int MOVIE_EIGHT_RECTS = 0;
    /** 滑动矩形动画测试视频的标识。 */
    public static final int MOVIE_SLIDERS = 1;

    /** 首次初始化时需要检查或生成的全部内容标识。 */
    private static final int[] ALL_TAGS = new int[] {
            MOVIE_EIGHT_RECTS,
            MOVIE_SLIDERS
    };

    /** 保护单例创建和初始化过程的锁。 */
    private static final Object sLock = new Object();
    /** 全局唯一的 ContentManager 实例。 */
    private static ContentManager sInstance = null;

    /** 是否已经完成文件目录和内容列表初始化。 */
    private boolean mInitialized = false;
    /** 应用私有文件目录，生成的 MP4 都保存于此目录。 */
    private File mFilesDir;
    /** 按内容 tag 索引的已生成内容对象。 */
    private ArrayList<Content> mContent;

    /**
     * 返回全局唯一的 ContentManager 实例。
     *
     * <p>该方法只负责创建对象，不负责设置文件目录。使用 {@link #getPath(int)}、
     * {@link #isContentCreated(Context)} 或生成内容前，必须先调用 {@link #initialize(Context)}。</p>
     */
    public static ContentManager getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new ContentManager();
            }
            return sInstance;
        }
    }

    /** 私有构造函数，强制调用方通过 {@link #getInstance()} 获取单例。 */
    private ContentManager() {}

    /**
     * 使用应用 Context 初始化内容管理器。
     *
     * <p>初始化是幂等的：重复调用不会重新创建列表，也不会删除或重新生成已有文件。
     * 使用 {@code context.getFilesDir()} 获取应用私有目录，避免持有 Activity Context
     * 造成不必要的生命周期引用。</p>
     *
     * @param context 用于取得应用私有文件目录的 Context
     */
    public static void initialize(Context context) {
        ContentManager mgr = getInstance();
        synchronized (sLock) {
            if (!mgr.mInitialized) {
                mgr.mFilesDir = context.getFilesDir();
                mgr.mContent = new ArrayList<Content>();
                mgr.mInitialized = true;
            }
        }
    }

    /**
     * 检查所有预生成内容对应的文件是否存在且可读。
     *
     * <p>当前实现只检查文件系统，不逐个验证 MP4 内容是否完整，也不检查 mContent 缓存。
     * 如果返回 {@code false}，调用方通常应在主线程调用 {@link #createAll(Activity)}。
     * 传入的 Context 参数是为了保持旧接口兼容，当前实现不会使用它。</p>
     *
     * @param unused 为兼容旧调用方保留的 Context 参数
     * @return 所有测试视频文件都可读时返回 {@code true}
     */
    public boolean isContentCreated(@SuppressWarnings("unused") Context unused) {
        // 更精细的实现可以逐个检查内容并只生成缺失项；这里采用简单策略，只检查文件是否可读。
        // 如果测试视频生成逻辑发生变化，需要用户通过菜单强制重新生成或清除应用数据。

        for (int i = 0; i < ALL_TAGS.length; i++) {
            File file = getPath(i);
            if (!file.canRead()) {
                Log.d(TAG, "Can't find readable " + file);
                return false;
            }
        }
        return true;
    }

    /**
     * 生成全部测试内容，并覆盖同名文件。
     *
     * <p>必须从主线程调用，因为方法会创建进度对话框；真正的媒体编码工作会在后台线程执行，
     * 因此该方法会很快返回，不会同步等待所有视频生成完成。</p>
     *
     * @param caller 用于创建进度对话框的 Activity
     */
    public void createAll(Activity caller) {
        prepareContent(caller, ALL_TAGS);
    }

    /**
     * 异步生成指定的内容。
     *
     * <p>方法首先显示不可取消的进度对话框，然后创建 GenerateTask。GenerateTask 在后台线程
     * 中逐项调用 {@link #prepare(ProgressUpdater, int)}，并在 UI 线程更新当前文件名和总体进度。
     * 该方法必须从主线程调用，并会立即返回。</p>
     *
     * @param caller 用于显示进度和错误对话框的 Activity
     * @param tags 要生成的内容标识数组
     */
    public void prepareContent(Activity caller, int[] tags) {
        // 显示不可取消的内容生成进度对话框。
        AlertDialog.Builder builder = WorkDialog.create(caller, R.string.preparing_content);
        builder.setCancelable(false);
        AlertDialog dialog = builder.show();

        // 通过 AsyncTask 在后台线程生成内容，避免阻塞主线程。
        GenerateTask genTask = new GenerateTask(caller, dialog, tags);
        genTask.execute();
    }

    /**
     * 返回已缓存的指定内容对象。
     *
     * <p>该方法只返回内存中的 Content，不会触发文件生成。调用前应确保对应内容已经由
     * GenerateTask 准备完成。</p>
     *
     * @param tag 内容标识，同时也是 mContent 中的索引
     * @return 对应的 Content 对象
     */
    public Content getContent(int tag) {
        synchronized (mContent) {
            return mContent.get(tag);
        }
    }

    /**
     * 在后台线程中生成一个指定内容项，并把结果放入内存缓存。
     *
     * <p>不同 tag 对应不同的 GeneratedMovie 子类。create() 负责编码并写入 MP4 文件，
     * 成功后再把对象按 tag 加入 mContent。mContent 的写入使用同步块，以便 UI 或其他线程
     * 读取时不会观察到并发修改。</p>
     *
     * @param prog 接收当前视频生成进度的回调
     * @param tag 要生成的内容标识
     */
    private void prepare(ProgressUpdater prog, int tag) {
        GeneratedMovie movie;
        switch (tag) {
            case MOVIE_EIGHT_RECTS:
                movie = new MovieEightRects();
                movie.create(getPath(tag), prog);
                synchronized (mContent) {
                    mContent.add(tag, movie);
                }
                break;
            case MOVIE_SLIDERS:
                movie = new MovieSliders();
                movie.create(getPath(tag), prog);
                synchronized (mContent) {
                    mContent.add(tag, movie);
                }
                break;
            default:
                throw new RuntimeException("Unknown tag " + tag);
        }
    }

    /**
     * 根据内容标识返回固定文件名。
     *
     * @param tag 内容标识
     * @return 对应的 MP4 文件名
     */
    private String getFileName(int tag) {
        switch (tag) {
            case MOVIE_EIGHT_RECTS:
                return "gen-eight-rects.mp4";
            case MOVIE_SLIDERS:
                return "gen-sliders.mp4";
            default:
                throw new RuntimeException("Unknown tag " + tag);
        }
    }

    /**
     * 返回指定内容在应用私有目录中的完整路径。
     *
     * @param tag 内容标识
     * @return 内容文件路径
     */
    public File getPath(int tag) {
        return new File(mFilesDir, getFileName(tag));
    }

    public interface ProgressUpdater {
        /**
         * 更新当前内容项的生成进度。
         *
         * <p>回调由后台编码线程触发；实现方如果要修改控件，应通过 UI 线程调度。</p>
         *
         * @param percent 当前内容项完成百分比，范围为 0 到 100
         */
        void updateProgress(int percent);
    }

    /**
     * 在 AsyncTask 的后台线程中依次生成内容，并把进度转发给 UI 线程。
     *
     * <p>一个任务可以包含多个 tag。mCurrentIndex 表示正在处理的 tag，最终进度通过
     * {@code index * 100 + percent} 映射到整个任务的进度条。任意一项发生 RuntimeException
     * 后会停止后续生成，并在 UI 线程显示错误对话框。</p>
     */
    private static class GenerateTask extends AsyncTask<Void, Integer, Integer>
            implements ProgressUpdater {
        // ----- 只在 UI 线程访问 -----
        /** 用于显示错误对话框的 Context，通常是调用方 Activity。 */
        private final Context mContext;
        /** 正在显示总体生成进度的对话框。 */
        private final AlertDialog mPrepDialog;
        /** 对话框中的总体进度条。 */
        private final ProgressBar mProgressBar;

        // ----- 只在后台线程访问 -----
        /** 当前正在生成的 tag 在 mTags 中的索引。 */
        private int mCurrentIndex;

        // ----- 后台线程写入，UI 线程读取 -----
        /** 本次任务需要依次生成的内容标识。 */
        private final int[] mTags;
        /** 后台生成失败的异常；volatile 保证 UI 线程能看到最新值。 */
        private volatile RuntimeException mFailure;


        /**
         * 创建一个内容生成任务，并初始化总体进度条。
         *
         * @param context 用于显示失败提示的 Context
         * @param dialog 已创建的内容准备对话框
         * @param tags 本次任务需要生成的内容标识
         */
        public GenerateTask(Context context, AlertDialog dialog, int[] tags) {
            mContext = context;
            mPrepDialog = dialog;
            mTags = tags;
            mProgressBar = (ProgressBar) mPrepDialog.findViewById(R.id.work_progress);
            mProgressBar.setMax(tags.length * 100);
        }

        /** 在后台线程中逐项生成内容，避免阻塞主线程。 */
        @Override // 后台线程
        protected Integer doInBackground(Void... params) {
            ContentManager contentManager = ContentManager.getInstance();

            Log.d(TAG, "doInBackground...");
            for (int i = 0; i < mTags.length; i++) {
                mCurrentIndex = i;
                updateProgress(0);
                try {
                    contentManager.prepare(this, mTags[i]);
                } catch (RuntimeException re) {
                    mFailure = re;
                    break;
                }
                updateProgress(100);
            }

            if (mFailure != null) {
                Log.w(TAG, "Failed while generating content", mFailure);
            } else {
                Log.d(TAG, "generation complete");
            }
            return 0;
        }

        /**
         * 接收当前内容项的局部进度，并发布“第几个文件 + 百分比”给 UI 线程。
         */
        @Override // 后台线程
        public void updateProgress(int percent) {
            publishProgress(mCurrentIndex, percent);
        }

        /** 在 UI 线程更新文件名和总体进度条。 */
        @Override // UI 线程
        protected void onProgressUpdate(Integer... progressArray) {
            int index = progressArray[0];
            int percent = progressArray[1];
            //Log.d(TAG, "进度 " + index + "/" + percent + " / " + mTags.length * 100);
            if (percent == 0) {
                TextView name = (TextView) mPrepDialog.findViewById(R.id.workJobName_text);
                name.setText(ContentManager.getInstance().getFileName(mTags[index]));
            }
            mProgressBar.setProgress(index * 100 + percent);
        }

        /**
         * 后台任务结束后关闭进度对话框；如果生成失败，再显示包含异常信息的错误对话框。
         */
        @Override // UI 线程
        protected void onPostExecute(Integer result) {
            Log.d(TAG, "onPostExecute -- dismss");
            mPrepDialog.dismiss();

            if (mFailure != null) {
                showFailureDialog(mContext, mFailure);
            }
        }

        /** 显示包含失败异常消息的错误对话框。 */
        private void showFailureDialog(Context context, RuntimeException failure) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.contentGenerationFailedTitle);
            String msg = context.getString(R.string.contentGenerationFailedMsg,
                    failure.getMessage());
            builder.setMessage(msg);
            builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });
            builder.setCancelable(false);
            AlertDialog dialog = builder.create();
            dialog.show();
        }
    }
}
