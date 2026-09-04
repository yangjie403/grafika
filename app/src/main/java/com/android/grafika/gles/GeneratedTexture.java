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

package com.android.grafika.gles;

import android.opengl.GLES20;

import java.nio.ByteBuffer;

/**
 * 生成纹理测试图像的工具类。
 *
 * <p>这些图像不是业务资源，而是专门用于验证纹理上传和采样是否正确：
 * 非对称颜色块可以检查上下方向和左右方向，透明色块可以检查 alpha 通道，
 * 不同频率的棋盘格可以观察缩放、过滤和纹理边界行为。</p>
 *
 * <p>像素数据按普通图像习惯从左上角开始排列，而 OpenGL 纹理坐标通常把原点看作左下角，
 * 因此使用这些测试图时需要特别关注 Y 轴翻转。半透明颜色在写入前会进行预乘 alpha，
 * 与项目中常见的混合配置相匹配。</p>
 */
public class GeneratedTexture {
    //private static final String TAG = GlUtil.TAG;  // 预留的日志标签，当前未使用。

    /** 测试图案类型：粗粒度色块或细粒度棋盘格。 */
    public enum Image { COARSE, FINE };

    // 颜色按 little-endian 整数保存：最低字节是 R，随后是 G、B，最高字节是 A。
    private static final int BLACK = 0x00000000;
    private static final int RED = 0x000000ff;
    private static final int GREEN = 0x0000ff00;
    private static final int BLUE = 0x00ff0000;
    private static final int MAGENTA = RED | BLUE;
    private static final int YELLOW = RED | GREEN;
    private static final int CYAN = GREEN | BLUE;
    private static final int WHITE = RED | GREEN | BLUE;
    private static final int OPAQUE = (int) 0xff000000L;
    private static final int HALF = (int) 0x80000000L;
    private static final int LOW = (int) 0x40000000L;
    private static final int TRANSP = 0;

    /** 4x4 粗粒度色块表；生成粗图时按行列索引，必须保持 16 个元素。 */
    private static final int GRID[] = new int[] {    // 必须保持 16 个元素，对应 4x4 网格
        OPAQUE|RED,     OPAQUE|YELLOW,  OPAQUE|GREEN,   OPAQUE|MAGENTA,
        OPAQUE|WHITE,   LOW|RED,        LOW|GREEN,      OPAQUE|YELLOW,
        OPAQUE|MAGENTA, TRANSP|GREEN,   HALF|RED,       OPAQUE|BLACK,
        OPAQUE|CYAN,    OPAQUE|MAGENTA, OPAQUE|CYAN,    OPAQUE|BLUE,
    };

    /** 测试纹理边长；必须是 2 的幂，便于一些纹理采样场景验证。 */
    private static final int TEX_SIZE = 64;
    /** 上传给 glTexImage2D() 的像素格式。 */
    private static final int FORMAT = GLES20.GL_RGBA;
    /** RGBA8888 每个像素的字节数。 */
    private static final int BYTES_PER_PIXEL = 4;

    // 静态初始化时生成一次并复用，避免每次创建测试纹理都重复计算图像数据。
    private static final ByteBuffer sCoarseImageData = generateCoarseData();
    private static final ByteBuffer sFineImageData = generateFineData();


    /**
     * 在当前 GL context 中创建测试纹理。
     *
     * <p>ByteBuffer 的第一个像素按图像约定表示左上角像素；真正显示时是否出现在屏幕左上角，
     * 取决于传入的纹理坐标和纹理矩阵。非不透明颜色已经做了预乘 alpha。</p>
     *
     * @param which 要生成的测试图案
     * @return GL 纹理句柄
     */
    public static int createTestTexture(Image which) {
        ByteBuffer buf;
        switch (which) {
            case COARSE:
                buf = sCoarseImageData;
                break;
            case FINE:
                buf = sFineImageData;
                break;
            default:
                throw new RuntimeException("unknown image");
        }
        return GlUtil.createImageTexture(buf, TEX_SIZE, TEX_SIZE, FORMAT);
    }

    /**
     * 生成粗粒度测试图：把 64x64 图像分成 4x4 个颜色块。
     *
     * <p>不同位置使用不同颜色，两个角落额外放置单像素白点，用于同时检查纹理方向、
     * 覆盖范围和边界是否被裁掉；图案中还包含透明与半透明区域，用于验证 alpha 通道和混合。
     * 图像数据从左上角开始，而 OpenGL 常以左下角为纹理坐标参考，因此通常需要垂直翻转。</p>
     *
     * @return 包含 RGBA8888 数据的 direct ByteBuffer
     */
    private static ByteBuffer generateCoarseData() {
        byte[] buf = new byte[TEX_SIZE * TEX_SIZE * BYTES_PER_PIXEL];
        final int scale = TEX_SIZE / 4;        // 把 64x64 图像划分为 4x4 色块

        for (int i = 0; i < buf.length; i += BYTES_PER_PIXEL) {
            int texRow = (i / BYTES_PER_PIXEL) / TEX_SIZE;
            int texCol = (i / BYTES_PER_PIXEL) % TEX_SIZE;

            int gridRow = texRow / scale;  // 0-3，粗图中的行
            int gridCol = texCol / scale;  // 0-3，粗图中的列
            int gridIndex = (gridRow * 4) + gridCol;  // 展平后的 0-15 索引

            int color = GRID[gridIndex];

            // 两个角落改成白色单像素，便于观察纹理是否完整覆盖到边缘。
            if (i == 0) {
                color = OPAQUE | WHITE;
            } else if (i == buf.length - BYTES_PER_PIXEL) {
                color = OPAQUE | WHITE;
            }

            // 从打包整数中拆出 RGBA；使用 int 是为了避免 Java byte 的有符号问题。
            int red = color & 0xff;
            int green = (color >> 8) & 0xff;
            int blue = (color >> 16) & 0xff;
            int alpha = (color >> 24) & 0xff;

            // 预乘 alpha 后写入 RGBA buffer；渲染时应使用匹配的混合函数。
            float alphaM = alpha / 255.0f;
            buf[i] = (byte) (red * alphaM);
            buf[i+1] = (byte) (green * alphaM);
            buf[i+2] = (byte) (blue * alphaM);
            buf[i+3] = (byte) alpha;
        }

        ByteBuffer byteBuf = ByteBuffer.allocateDirect(buf.length);
        byteBuf.put(buf);
        byteBuf.position(0);
        return byteBuf;
    }

    /**
     * 生成细粒度测试图：四个象限使用不同大小的棋盘格。
     *
     * <p>单像素、双像素、四像素和八像素周期可以直观看出纹理缩放时采用了最近邻还是线性
     * 过滤，也可以帮助定位纹理坐标插值和 mipmap 相关问题。</p>
     *
     * @return 包含 RGBA8888 数据的 direct ByteBuffer
     */
    private static ByteBuffer generateFineData() {
        byte[] buf = new byte[TEX_SIZE * TEX_SIZE * BYTES_PER_PIXEL];

        // 左上：单像素周期的红/蓝棋盘格，最容易暴露过滤和采样问题。
        checkerPattern(buf, 0, 0, TEX_SIZE / 2, TEX_SIZE / 2,
                OPAQUE|RED, OPAQUE|BLUE, 0x01);
        // 右下：双像素周期的红/绿棋盘格。
        checkerPattern(buf, TEX_SIZE / 2, TEX_SIZE / 2, TEX_SIZE, TEX_SIZE,
                OPAQUE|RED, OPAQUE|GREEN, 0x02);
        // 左下：四像素周期的蓝/绿棋盘格。
        checkerPattern(buf, 0, TEX_SIZE / 2, TEX_SIZE / 2, TEX_SIZE,
                OPAQUE|BLUE, OPAQUE|GREEN, 0x04);
        // 右上：八像素周期的黑/白棋盘格。
        checkerPattern(buf, TEX_SIZE / 2, 0, TEX_SIZE, TEX_SIZE / 2,
                OPAQUE|WHITE, OPAQUE|BLACK, 0x08);

        ByteBuffer byteBuf = ByteBuffer.allocateDirect(buf.length);
        byteBuf.put(buf);
        byteBuf.position(0);
        return byteBuf;
    }

    /**
     * 在指定矩形区域填充棋盘格。
     *
     * @param buf 目标 RGBA 字节数组
     * @param left 区域左边界，包含
     * @param top 区域上边界，包含
     * @param right 区域右边界，不包含
     * @param bottom 区域下边界，不包含
     * @param color1/color2 交替使用的两种打包颜色
     * @param bit 决定棋盘格周期的位掩码，值越大周期越粗
     */
    private static void checkerPattern(byte[] buf, int left, int top, int right, int bottom,
            int color1, int color2, int bit) {
        for (int row = top; row < bottom; row++) {
            int rowOffset = row * TEX_SIZE * BYTES_PER_PIXEL;
            for (int col = left; col < right; col++) {
                int offset = rowOffset + col * BYTES_PER_PIXEL;
                int color;
                if (((row & bit) ^ (col & bit)) == 0) {
                    color = color1;
                } else {
                    color = color2;
                }

                // 拆出 RGBA 分量；byte 在 Java 中有符号，不能直接用于 0~255 计算。
                int red = color & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = (color >> 16) & 0xff;
                int alpha = (color >> 24) & 0xff;

                // 写入预乘 alpha 的 RGBA 数据。
                float alphaM = alpha / 255.0f;
                buf[offset] = (byte) (red * alphaM);
                buf[offset+1] = (byte) (green * alphaM);
                buf[offset+2] = (byte) (blue * alphaM);
                buf[offset+3] = (byte) alpha;
            }
        }
    }
}
