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

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 可以按照指定宽高比测量自身尺寸的 {@link FrameLayout}。
 *
 * <p>这个布局通常作为视频预览或视频播放控件的外层容器使用。调用方通过
 * {@link #setAspectRatio(double)} 设置目标宽高比后，本类会在 {@link #onMeasure(int, int)}
 * 中尽量利用父布局提供的空间，并通过缩小宽度或高度来保持该比例。多余的空间会留在
 * 父布局中，从而形成类似 letterbox（上下留黑边）或 pillarbox（左右留黑边）的效果。</p>
 *
 * <p>宽高比的定义是 {@code width / height}，例如 16:9 应传入
 * {@code 16.0 / 9.0}。宽高比只作用于内容区域，不包含本布局的 padding；padding 会在
 * 计算完成后重新加回最终尺寸。</p>
 *
 * <p>如果没有设置有效的目标宽高比，本类不会改写父布局传入的测量规格，而是完全交给
 * {@link FrameLayout} 的默认测量逻辑处理。</p>
 */
public class AspectFrameLayout extends FrameLayout {
    /** 用于区分本布局日志的标签。 */
    private static final String TAG = MainActivity.TAG + "-AFL";

    /**
     * 目标宽高比，定义为“内容宽度 / 内容高度”。
     *
     * <p>负值表示调用方尚未设置目标比例，此时使用父布局提供的默认尺寸。字段初始值
     * 使用负数而不是 0，是因为正常的视频宽高比必须是正数。</p>
     */
    private double mTargetAspect = -1.0;

    /**
     * 以代码方式创建布局。
     *
     * @param context 创建该 View 所需的 Context
     */
    public AspectFrameLayout(Context context) {
        super(context);
    }

    /**
     * 从 XML 创建布局。
     *
     * @param context 创建该 View 所需的 Context
     * @param attrs XML 中声明的 View 属性
     */
    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * 设置期望的内容区域宽高比。
     *
     * <p>参数的含义是 {@code width / height}，不是“宽和高的数值”或百分比。例如，16:9
     * 视频应传入 {@code 16.0 / 9.0}，4:3 视频应传入 {@code 4.0 / 3.0}。</p>
     *
     * <p>设置新比例后调用 {@link #requestLayout()}，使 Android 在下一次布局遍历中重新执行
     * {@link #onMeasure(int, int)}。如果新比例与当前比例相同，则不请求重新布局。</p>
     *
     * @param aspectRatio 目标宽高比，必须大于或等于 0；实际测量时只有大于 0 的值会启用
     *                    比例调整
     * @throws IllegalArgumentException 当 {@code aspectRatio} 小于 0 时抛出
     */
    public void setAspectRatio(double aspectRatio) {
        if (aspectRatio < 0) {
            throw new IllegalArgumentException();
        }
        Log.d(TAG, "Setting aspect ratio to " + aspectRatio + " (was " + mTargetAspect + ")");
        if (mTargetAspect != aspectRatio) {
            mTargetAspect = aspectRatio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // onMeasure() 运行在 UI 线程。这里记录父布局传入的测量规格，便于排查“父布局给了
        // 多大空间、最终为什么得到这个尺寸”等布局问题。
        Log.d(TAG, "onMeasure target=" + mTargetAspect +
                " width=[" + MeasureSpec.toString(widthMeasureSpec) +
                "] height=[" + View.MeasureSpec.toString(heightMeasureSpec) + "]");

        // mTargetAspect 小于 0 表示尚未设置目标比例。此时不要改写 width/height 的
        // MeasureSpec，直接使用 FrameLayout 的默认测量行为。
        if (mTargetAspect > 0) {
            // MeasureSpec.getSize() 取得父布局为当前 View 提供的尺寸部分。该示例假定这个
            // size 可以作为可用区域的上限来计算比例；最终会把计算结果以 EXACTLY 重新构造
            // MeasureSpec，再交给 super.onMeasure() 测量子 View。
            int initialWidth = MeasureSpec.getSize(widthMeasureSpec);
            int initialHeight = MeasureSpec.getSize(heightMeasureSpec);

            // 宽高比只约束内容区域，不约束 padding。先把水平和垂直 padding 从父布局给出的
            // 外部尺寸中扣除，避免 padding 被错误地算进视频画面的宽高比。
            int horizPadding = getPaddingLeft() + getPaddingRight();
            int vertPadding = getPaddingTop() + getPaddingBottom();
            initialWidth -= horizPadding;
            initialHeight -= vertPadding;

            // 当前可用区域的宽高比。由于初始比例有效时通常宽高都大于 0，因此这里直接做
            // 除法；如果父布局传入的 size 为 0，Java 的 double 除法会得到 Infinity/NaN，
            // 后续判断可能不会调整尺寸，最终仍交给父类处理。
            double viewAspectRatio = (double) initialWidth / initialHeight;

            // 用相对误差比较目标比例和当前可用比例：
            //   aspectDiff = target / current - 1
            // aspectDiff > 0 表示目标比例更“宽”，当前区域的高度需要收窄；
            // aspectDiff < 0 表示目标比例更“窄”，当前区域的宽度需要收窄。
            double aspectDiff = mTargetAspect / viewAspectRatio - 1;

            // 允许 1% 的比例误差，避免由于浮点误差或像素取整，把本来已经合适的尺寸
            // 从例如 1280x720 改成 1280x719。保留原尺寸也能避免不必要的布局抖动。
            if (Math.abs(aspectDiff) < 0.01) {
                Log.d(TAG, "aspect ratio is good (target=" + mTargetAspect +
                        ", view=" + initialWidth + "x" + initialHeight + ")");
            } else {
                if (aspectDiff > 0) {
                    // 目标比例大于当前区域比例，说明当前区域相对偏高（宽度是限制因素）。
                    // 保持内容宽度不变，按目标比例反推内容高度：
                    //     height = width / targetAspect
                    initialHeight = (int) (initialWidth / mTargetAspect);
                } else {
                    // 目标比例小于当前区域比例，说明当前区域相对偏宽（高度是限制因素）。
                    // 保持内容高度不变，按目标比例反推内容宽度：
                    //     width = height * targetAspect
                    initialWidth = (int) (initialHeight * mTargetAspect);
                }

                Log.d(TAG, "new size=" + initialWidth + "x" + initialHeight + " + padding " +
                        horizPadding + "x" + vertPadding);

                // 计算出的 initialWidth/initialHeight 是内容区域尺寸；重新加回 padding 后
                // 才是该 AspectFrameLayout 的最终外部尺寸。
                initialWidth += horizPadding;
                initialHeight += vertPadding;

                // 将调整后的结果固定为 EXACTLY，确保 super.onMeasure() 和子 View 使用这个
                // 保持比例的外部尺寸，而不是再次按照原始 MeasureSpec 选择其他尺寸。
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(initialWidth, MeasureSpec.EXACTLY);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(initialHeight, MeasureSpec.EXACTLY);
            }
        }

        // FrameLayout 负责根据最终的 width/height MeasureSpec 测量自身及其子 View。若目标
        // 比例未设置，传入的仍是父布局原始规格；若目标比例有效，传入的是上面重新计算的
        // EXACTLY 规格。
        //Log.d(TAG, "set width=[" + MeasureSpec.toString(widthMeasureSpec) +
        //        "] height=[" + View.MeasureSpec.toString(heightMeasureSpec) + "]");
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
