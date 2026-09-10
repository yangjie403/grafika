Grafika
=======

欢迎来到 Grafika。这是一个用于实验 Android 图形与媒体能力的示例项目集合。

Grafika 的定位：

- 一组用于验证和演示 Android 图形功能的实验代码。
- 一个面向 API 18（Android 4.3）开发的 SDK 应用。部分代码可能可以运行在更低版本上，
  但对旧版本的兼容支持并不完整。
- 采用 Apache 2.0 许可证的开源项目，版权归 Google 所有。你可以按照
  [LICENSE](LICENSE) 中的条款使用代码。
- 一个持续演进的实验项目，会根据需要不断更新。

需要注意：

- 项目不保证稳定性。
- 项目没有经过完整的产品化打磨和充分测试，界面可能比较简陋或不够友好。
- 项目不旨在展示“正确开发 Android 应用”的唯一方式。部分边界情况可能处理不完善，
  日志也可能保持较详细的输出级别。
- 项目文档仍然有限，但本 README 已补充主要 Activity 和技术点说明。
- 项目不属于 Android Open Source Project（AOSP），目前也不接受针对 Grafika 的贡献。
- Grafika 不是 Google 的正式产品，而是由 Google 开源的一组图形与媒体实验代码。
- 项目通常不提供面向生产环境的技术支持。

Grafika 可以作为
[Android 系统级图形架构](http://source.android.com/devices/graphics/architecture.html)
文档的配套示例。该文档解释了本项目示例依赖的底层技术，并使用 Grafika 的部分 Activity
作为例子。如果希望理解项目的整体工作原理，建议先阅读这份文档。

本项目与 [bigflake MediaCodec 示例](http://www.bigflake.com/mediacodec/) 有部分重叠。
bigflake 中的代码主要是“无界面”的 CTS 测试，强调健壮、自包含，并尽量不受普通应用
生命周期问题影响；Grafika 则是一个常规 Android 应用，同时会尽量正确处理应用层问题，
例如避免在 UI 线程执行大量工作。

Grafika 会根据实际需求逐步增加功能，很多功能源于开发者对 Android 平台正确性或性能问题
的观察：有些实验用于确认问题确实存在，有些则用于展示一种可行的处理方式。

项目特别关注以下两个方面：

- 线程安全。使用媒体类时很容易发生隐蔽而危险的线程交叉问题，GL/EGL 对线程本地状态的
  依赖又进一步增加了复杂度。源码注释中会经常标出线程归属和线程切换要求，相关背景可参考
  [Android SMP Primer](http://developer.android.com/training/articles/smp.html)。
- 垃圾回收。GC 停顿会造成卡顿。理想情况下，Activity 进入稳定运行状态后不再分配对象；
  切换模式时（例如开始或停止录制）可以发生必要的分配。

Activity 实现与技术点
---------------------

项目中共有 23 个 Activity。`MainActivity` 通过排序列表展示大多数入口，名称带有
`{~ignore}` 前缀的是默认隐藏的诊断实验。下面的清单基于当前源码和 Manifest 注册项整理。

```
com/android/grafika/
├── gles/                       # OpenGL ES & EGL 底层核心工具包
│   ├── EglCore.java            # EGL 上下文/显示设备管理
│   ├── EglSurfaceBase.java     # EGLSurface 基础包装类
│   ├── WindowSurface.java      # 屏幕与编码器 Surface 的 EGLSurface 包装
│   ├── OffscreenSurface.java   # Pbuffer 离屏 Surface
│   ├── FullFrameRect.java      # 全屏绘制 Quad 辅助类
│   ├── Texture2dProgram.java   # 2D / OES 扩展纹理 Shader 管理器
│   ├── Drawable2d.java         # 顶点数据与网格
│   ├── FlatShadedProgram.java  # 单色渲染 Shader
│   └── GlUtil.java             # OpenGL 错误检查与 Shader 编译工具
│
├── 视频解码与播放核心
│   ├── MoviePlayer.java                # 核心：Extractor + MediaCodec 硬件解码循环
│   ├── SpeedControlCallback.java       # 核心：PTS 显示时间戳控速同步器
│   ├── PlayMovieSurfaceActivity.java   # SurfaceView 播放界面
│   ├── PlayMovieActivity.java          # TextureView 播放界面
│   └── DoubleDecodeActivity.java       # 双路视频解码 + GLES 渲染
│
├── 视频编码与录制核心
│   ├── VideoEncoderCore.java           # 核心：MediaCodec InputSurface 编码 + MediaMuxer 封装
│   ├── RecordFBOActivity.java          # 核心：FBO 离屏渲染 + 屏幕/录制双输出
│   └── TextureViewGLActivity.java      # 自定义 GL 线程渲染到 TextureView
│
├── OpenGL ES 入门示例
│   └── OpenGlesTriangleActivity.java   # GLSurfaceView + GLES 2.0 绘制彩色三角形
│
└── 其他辅助/旧版 API 演示（可跳过）
    ├── CameraCaptureActivity.java      # Camera1 预览与录制
    ├── ContinuousCaptureActivity.java  # 环形缓冲区录制
    ├── ColorBarActivity.java           # 颜色条与 VSYNC 测试
    └── HardwareScalerActivity.java     # 硬件缩放测试
```

| Activity | 实现功能 | 主要技术点 |
| --- | --- | --- |
| [`MainActivity`](app/src/main/java/com/android/grafika/MainActivity.java) | 所有图形和媒体实验的入口，也负责显示 About、重新生成测试视频。 | `ListActivity`、`SimpleAdapter`、基于反射的 Activity 查找、`ContentManager`、后台生成 MP4。 |
| [`PlayMovieActivity`](app/src/main/java/com/android/grafika/PlayMovieActivity.java) | 将视频文件播放到 `TextureView`，支持循环播放和固定 60 FPS 播放。 | `TextureView`/`SurfaceTexture`、`MoviePlayer` 中的 `MediaExtractor` + `MediaCodec` 解码、`SpeedControlCallback`、矩阵宽高比修正、生命周期安全停止播放。 |
| [`PlayMovieSurfaceActivity`](app/src/main/java/com/android/grafika/PlayMovieSurfaceActivity.java) | 将视频播放到 `SurfaceView`，是 TextureView 播放器的对照实现。 | `SurfaceHolder`、解码器输出 `Surface`、`AspectFrameLayout`、SurfaceFlinger 直接合成/硬件 Overlay 路径、播放前使用 EGL/GLES 清屏。 |
| [`ScreenRecordActivity`](app/src/main/java/com/android/grafika/ScreenRecordActivity.java) | 录制设备屏幕为 H.264 MP4，同时保留父类提供的 SurfaceView 播放界面。 | API 23 `MediaProjectionManager`、`VirtualDisplay`、编码器输入 `Surface`、异步 `MediaCodec.Callback`、`MediaMuxer`、屏幕参数和运行时权限流程。 |
| [`LiveCameraActivity`](app/src/main/java/com/android/grafika/LiveCameraActivity.java) | 显示默认摄像头的实时预览。 | 旧版 `android.hardware.Camera`、`TextureView`/`SurfaceTexture`、摄像头显示旋转、摄像头权限和 SurfaceTexture 生命周期回调。 |
| [`CameraCaptureActivity`](app/src/main/java/com/android/grafika/CameraCaptureActivity.java) | 显示摄像头预览、应用可选预览滤镜，并将摄像头画面录制为 MP4。 | `GLSurfaceView`、摄像头外部纹理、共享 EGL Context、`FullFrameRect` Shader 滤镜、`TextureMovieEncoder`、H.264 Surface 输入、`Handler`/`queueEvent()` 跨线程协调。 |
| [`ContinuousCaptureActivity`](app/src/main/java/com/android/grafika/ContinuousCaptureActivity.java) | 持续把摄像头视频写入环形缓冲区，点击“捕获（Capture）”时保存最近一段视频。 | 摄像头 → `SurfaceTexture` → GLES 管线、`CircularEncoder`、固定大小编码帧池以减少 GC、编码线程、呈现时间戳、异步保存 MP4。 |
| [`DoubleDecodeActivity`](app/src/main/java/com/android/grafika/DoubleDecodeActivity.java) | 同时解码两个生成的视频，并在两个区域中并排显示。 | 两个 `TextureView` 和解码线程、旋转时保留静态 `SurfaceTexture`、重新绑定 View、`MoviePlayer`、仅在 Activity 真正退出时停止解码。 |
| [`RecordFBOActivity`](app/src/main/java/com/android/grafika/RecordFBOActivity.java) | 将动画 GL 场景显示到屏幕，同时录制 GL Surface 为 MP4。 | 原生 `SurfaceView`、专用渲染线程、`Choreographer` 垂直同步、EGL/GLES 2/3、Framebuffer Object、重复绘制/FBO/Framebuffer Blit 策略、`TextureMovieEncoder2`、丢帧和呈现时间戳。 |
| [`HardwareScalerActivity`](app/src/main/java/com/android/grafika/HardwareScalerActivity.java) | 在渲染动画的同时动态改变底层 Surface 缓冲区大小。 | `SurfaceHolder.setFixedSize()`、Surface 生命周期、`Choreographer`、Handler 驱动的 GL 渲染线程、正交投影、硬件缩放器和 BufferQueue 行为。 |
| [`ScheduledSwapActivity`](app/src/main/java/com/android/grafika/ScheduledSwapActivity.java) | 测试向 SurfaceFlinger 提交指定未来显示时间的缓冲区。 | API 19 时间戳呈现、`Choreographer`、`WindowSurface.setPresentationTime()`、3-2 Pulldown/更新模式、提前多帧调度、`Trace`/systrace 诊断和丢帧统计。 |
| [`MultiSurfaceActivity`](app/src/main/java/com/android/grafika/MultiSurfaceActivity.java) | 展示三个重叠的 Surface 图层，其中包含安全图层和动画图层。 | `SurfaceView.setSecure()`、Media Overlay 和顶层 Z 顺序、半透明 RGBA Surface、Canvas/GLES 绘制、Alpha 混合、`PorterDuff`、合成器及截屏/录屏行为。 |
| [`TextureViewGLActivity`](app/src/main/java/com/android/grafika/TextureViewGLActivity.java) | 将持续运动的 GL 方块绘制到 `TextureView`。 | 手动管理渲染线程、由 `SurfaceTexture` 支撑的 EGL `WindowSurface`、GLES 2/3、Scissor 渲染、SurfaceTexture 生产者/消费者流程、不等待垂直同步的高速渲染。 |
| [`OpenGlesTriangleActivity`](app/src/main/java/com/android/grafika/OpenGlesTriangleActivity.java) | 使用 `GLSurfaceView` 和 OpenGL ES 2.0 绘制彩色三角形，作为 GLES 入门示例。 | Renderer 生命周期、GL 渲染线程、EGLSurface、vertex/fragment shader、shader 编译与 program 链接、`FloatBuffer`、交错顶点属性、viewport、`glDrawArrays()` 和 GL 错误检查。 |
| [`TextureViewCanvasActivity`](app/src/main/java/com/android/grafika/TextureViewCanvasActivity.java) | 使用软件 Canvas 将动画场景绘制到 `TextureView`。 | 渲染线程同步、`Surface.lockCanvas()`/`unlockCanvasAndPost()`、脏矩形实验、SurfaceTexture 回调，以及与 GL 渲染的对比。 |
| [`TextureFromCameraActivity`](app/src/main/java/com/android/grafika/TextureFromCameraActivity.java) | 将摄像头预览作为 GLES 纹理显示，并支持缩放、旋转、调整尺寸和触摸定位。 | 专用摄像头/渲染线程、外部纹理、`SurfaceView`、`SeekBar` 参数映射、触摸事件、Sprite 几何与矩阵、Handler 消息传递、Surface 暂停/恢复处理。 |
| [`ColorBarActivity`](app/src/main/java/com/android/grafika/ColorBarActivity.java) | 显示带文字标签的 RGB 彩条和 Alpha 覆盖层。 | `SurfaceView`、RGBA8888 缓冲区格式、软件 `Canvas`、`Surface.lockCanvas()`、`Paint`/`Typeface` 文字绘制、Surface 回调。 |
| [`GlesInfoActivity`](app/src/main/java/com/android/grafika/GlesInfoActivity.java) | 显示并可保存 GLES、EGL 和设备信息。 | 1×1 离屏 EGL Pbuffer、`EGL14`/`GLES20` 能力查询、扩展列表格式化、应用私有文件写入。 |
| [`ReadPixelsActivity`](app/src/main/java/com/android/grafika/ReadPixelsActivity.java) | 测量从 GLES 读回 1280×720 RGBA 帧的耗时。 | 离屏 EGL Surface、`glFinish()` + `glReadPixels()`、直接 `ByteBuffer`、`AsyncTask` 进度/取消界面、可选 PNG 输出。 |
| [`TextureUploadActivity`](app/src/main/java/com/android/grafika/TextureUploadActivity.java) | 测量重复上传 512×512 RGBA 纹理的性能。 | 离屏 EGL/GLES、生成的直接像素缓冲区、`glTexImage2D()`、纹理创建/删除、`glFinish()` 计时、`AsyncTask` 和可选 Bitmap 导出。 |
| [`CodecOpenActivity`](app/src/main/java/com/android/grafika/CodecOpenActivity.java) | 尽可能打开多个 H.264 编码器，探索 Codec 和系统资源限制。 | `MediaCodec` Surface 输入配置、重复创建/启动编码器、故意延迟释放、手动 GC/Finalization、进程终止；隐藏诊断实验。 |
| [`SoftInputSurfaceActivity`](app/src/main/java/com/android/grafika/SoftInputSurfaceActivity.java) | 将 Canvas 帧绘制到 Codec 输入 Surface，生成一段短 MP4。 | H.264 `MediaCodec`、`MediaMuxer`、`Surface.lockCanvas()` 输入、输出缓冲区 Drain/EOS、合成呈现时间戳；这是刻意保留的非支持用法，并且故意在 `onCreate()` 中执行。 |
| [`ChorTestActivity`](app/src/main/java/com/android/grafika/ChorTestActivity.java) | 测试线程销毁时 `Choreographer` 帧回调是否继续执行以及能否被移除。 | 专用 `Looper`/`Handler` 线程、重复 `Choreographer.FrameCallback`、`Looper.quit()` 后移除回调；隐藏的框架行为实验。 |

### 公共数据流

这些 Activity 都是围绕少量可复用数据通路构建的实验：

```text
摄像头预览
    -> SurfaceTexture（GLES 外部纹理）
    -> FullFrameRect / Shader 渲染
    -> 显示 Surface 和/或 MediaCodec 输入 Surface
    -> H.264 基本码流
    -> MediaMuxer -> MP4

MP4 文件
    -> MediaExtractor
    -> MediaCodec 解码器
    -> Surface / SurfaceTexture / TextureView
```

重要的公共类包括：负责 Extractor/Decoder 播放的 [`MoviePlayer`](app/src/main/java/com/android/grafika/MoviePlayer.java)，
负责基于 Surface 编码的 [`TextureMovieEncoder`](app/src/main/java/com/android/grafika/TextureMovieEncoder.java)
和 [`TextureMovieEncoder2`](app/src/main/java/com/android/grafika/TextureMovieEncoder2.java)，负责编码环形缓冲录制的
[`CircularEncoder`](app/src/main/java/com/android/grafika/CircularEncoder.java)，以及提供 EGL/Surface/纹理辅助类的
[`gles`](app/src/main/java/com/android/grafika/gles/) 包。

整个项目最值得关注的设计点包括：区分 `Surface`、`SurfaceTexture` 和 `EGLSurface`；明确摄像头、Codec、GL
对象的线程归属；通过 `Handler` 或 `GLSurfaceView.queueEvent()` 转发状态变化；以及在正确的 Activity/Surface
生命周期节点释放摄像头、Codec、EGL 和 Surface 资源。

所有代码均使用 Java 编写，不使用 NDK。

Grafika 第一次启动时会自动生成两个测试视频：`gen-eight-rects` 和 `gen-sliders`。
如果修改了测试视频生成代码，可以在主界面的菜单中选择“重新生成内容（Regenerate content）”
重新生成它们。

## 功能列表

### TextureView 视频播放

[`PlayMovieActivity`](app/src/main/java/com/android/grafika/PlayMovieActivity.java) 将 MP4 文件的
视频轨道播放到 `TextureView`。

- 只读取 `/data/data/com.android.grafika/files/` 中的文件；所有生成视频的 Activity 都会把文件
  保存到这里，目录中还包含自动生成的 `gen-eight-rects.mp4` 和 `gen-sliders.mp4`。
- 默认按录制时的速度播放一次，可通过复选框开启循环播放，或固定为 60 FPS 播放。
- Activity 名称带 `*`，因此会排在 Activity 列表顶部。

### 环形缓冲区连续录制

[`ContinuousCaptureActivity`](app/src/main/java/com/android/grafika/ContinuousCaptureActivity.java)
持续把摄像头视频写入环形缓冲区，点击“捕获（Capture）”后保存最近的一段视频（旧名称为
“连续捕获（Constant capture）”）。

- 默认尝试以 6 MB/s、约 15 FPS、720p 录制 7 秒，需要约 5 MB 缓冲区。
- 界面显示当前缓冲区中帧的时间跨度；实际保存的时长会略短，因为输出必须从同步帧开始，
  同步帧配置为每秒出现一次。
- 输出为仅包含视频的 `constant-capture.mp4`，分辨率固定为 1280x720；如果摄像头输出尺寸不同，
  视频宽高比可能不正确。

### 双路视频解码

[`DoubleDecodeActivity`](app/src/main/java/com/android/grafika/DoubleDecodeActivity.java) 同时解码
两路视频，并通过两个 `TextureView` 并排显示。

- 播放两个自动生成的视频，它们的播放速度不同。
- 屏幕旋转时不停止解码器，而是保留 `SurfaceTexture` 并重新连接到新的 `TextureView`，避免昂贵的
  Codec 重配置；离开 Activity 后会停止解码，避免长期占用硬件 Codec 资源。
- 横竖屏使用不同布局，视频会缩放到适合显示区域的大小。

### 硬件缩放器实验

[`HardwareScalerActivity`](app/src/main/java/com/android/grafika/HardwareScalerActivity.java) 在渲染
GL 动画的同时动态改变 Surface 尺寸，相关背景可参考
[Android 硬件缩放器性能文章](http://android-developers.blogspot.com/2013/09/using-hardware-scaler-for-performance.html)。

- 改变尺寸时可能会看到一帧绘制异常：渲染尺寸在 `surfaceChanged` 回调中更新，但底层 Surface
  要到下一个 Buffer 被 latch 后才真正改变尺寸。

### 摄像头预览与多 Surface 实验

[`LiveCameraActivity`](app/src/main/java/com/android/grafika/LiveCameraActivity.java) 将默认后置摄像头
预览输出到 `TextureView`，实现方式基本参考
[TextureView 文档](http://developer.android.com/reference/android/view/TextureView.html)。如果设备
没有默认摄像头（例如 Nexus 7 2012），Activity 会崩溃。

[`MultiSurfaceActivity`](app/src/main/java/com/android/grafika/MultiSurfaceActivity.java) 创建三个重叠的
`SurfaceView`，其中一个标记为 secure，用于观察 HWC 多图层合成以及 secure Surface 对截屏/录屏的影响。
点击“bounce”后，非 secure 图层上的圆形会尽可能快地用软件动画，帧率会输出到 logcat。

### SurfaceView 视频播放

[`PlayMovieSurfaceActivity`](app/src/main/java/com/android/grafika/PlayMovieSurfaceActivity.java) 将 MP4
视频播放到 `SurfaceView`，整体流程与 `TextureView` 播放器相似，但功能较少。使用 SurfaceView 的
优缺点请参考该类的源码注释。

### OpenGL ES 同时显示与录制

[`RecordFBOActivity`](app/src/main/java/com/android/grafika/RecordFBOActivity.java) 使用 OpenGL ES
同时渲染到屏幕和视频编码器，并通过 FBO 避免重复渲染。

- 支持三种输出策略：重复绘制两次、离屏绘制后 Blit 两次、先绘制到屏幕再 Blit framebuffer；
  第三种方式目前尚未正常工作。
- `Choreographer` 按 VSYNC 驱动渲染；落后过多时会跳过渲染帧，并通过丢帧计数器和边框闪烁提示。
  物体运动不会跳过，因此动画通常仍然比较平滑。
- 通常每隔一帧向编码器提交一次，所以典型设备上的输出约为 30 FPS，而不是 60 FPS。
- 录制结果会通过加黑边或加侧边的方式匹配屏幕宽高比，横屏和竖屏录制结果可能不同。
- 输出为仅包含视频的 `fbo-gl-recording.mp4`。

### 屏幕录制

[`ScreenRecordActivity`](app/src/main/java/com/android/grafika/ScreenRecordActivity.java) 使用
`MediaProjectionManager` 将设备屏幕录制为视频。该 API 要求 API 23（Android 6.0）或更高版本。

### 定时交换缓冲区

[`ScheduledSwapActivity`](app/src/main/java/com/android/grafika/ScheduledSwapActivity.java) 实验
SurfaceFlinger 的定时呈现能力，可以指定缓冲区的未来显示时间。

- 要实现预期效果需要 API 19（Android 4.4）；在 API 18 上肉眼可能看不出明显区别。
- 可以配置帧交付时序（例如 24 FPS 使用 3-2 模式）以及提前多少时间提交帧；选择“ASAP”会关闭调度。
- 可以使用带有 `sched gfx view --app=com.android.grafika` 标签的 systrace 观察效果。
- 当时间调度出现问题时，移动方块会改变颜色。

### 摄像头预览与录制

[`CameraCaptureActivity`](app/src/main/java/com/android/grafika/CameraCaptureActivity.java) 尝试从
前置摄像头以 720p 录制，同时显示预览。

- 通过录制按钮切换开始和停止。
- 录制会持续到手动停止；离开后再次进入会重新开始，并在视频中产生实时空档。录制期间播放输出文件
  可能读到不完整内容，甚至导致播放 Activity 崩溃。
- 录制视频缩放为 640x480，可能出现画面变形；真实应用应匹配摄像头输入尺寸，或在输出时通过加黑边
  保持宽高比。
- 可以为预览选择滤镜，但滤镜不会应用到录制结果。滤镜 Shader 未针对性能进行充分优化，在大多数设备
  上表现尚可，但旧版 Nexus 7 2012 可能较慢。演示视频见
  [YouTube](http://www.youtube.com/watch?v=kH9kCP2T5Gg)。
- 输出为仅包含视频的 `camera-test.mp4`。

### TextureView Canvas 与 GLES 对比

[`TextureViewCanvasActivity`](app/src/main/java/com/android/grafika/TextureViewCanvasActivity.java) 使用
软件 Canvas 将动画绘制到 `TextureView`。它会尽可能快地渲染，但通常比 GLES 版本慢；每 64 帧切换一次
脏矩形实验，启用时脏矩形横跨屏幕宽度。

[`TextureViewGLActivity`](app/src/main/java/com/android/grafika/TextureViewGLActivity.java) 演示不依赖
`GLSurfaceView`、直接在 `TextureView` 中使用 GLES。它会尽可能快地渲染，在多数设备上可能超过 60 FPS
并出现明显闪烁；Android 4.4 上还存在系统不丢帧的相关问题。

### 摄像头纹理与参数交互

[`TextureFromCameraActivity`](app/src/main/java/com/android/grafika/TextureFromCameraActivity.java) 将
摄像头预览作为 GLES 纹理绘制。可以通过滑块调整尺寸、旋转和缩放，在其他位置触摸可将矩形移动到触摸点。

### 其他诊断实验

- [`ColorBarActivity`](app/src/main/java/com/android/grafika/ColorBarActivity.java)：显示 RGB 彩条。
- [`GlesInfoActivity`](app/src/main/java/com/android/grafika/GlesInfoActivity.java)：输出 GLES 版本和扩展列表，
  “保存（Save）”按钮可以把结果保存到应用私有目录。
- [`TextureUploadActivity`](app/src/main/java/com/android/grafika/TextureUploadActivity.java)：粗略测量使用
  `glTexImage2D()` 上传 512x512 RGBA 纹理所需的时间。
- [`ReadPixelsActivity`](app/src/main/java/com/android/grafika/ReadPixelsActivity.java)：粗略测量
  `glReadPixels()` 读取 720p 帧所需的时间。


## 已知问题

- 在运行 Android 4.3（JWR67E）的 Nexus 4 上，`CameraCaptureActivity` 选择某些滤镜模式时会崩溃，
  可能是 Adreno 驱动的 “Internal compiler error”。

## 后续功能与修复建议

以下事项没有按优先级排序：

- 对需要性能或低延迟的场景停止使用 `AsyncTask`。
- 为摄像头增加“放大像素”查看器：使用一个 `SurfaceView`，左半边显示实时摄像头和可平移矩形，
  右半边显示放大 8 倍后的像素。
- 修改 `TextureViewGLActivity` 的动画，或为高频闪烁增加光敏性癫痫风险提示。
- 支持两个视频之间的交叉淡化并录制结果，同时允许指定 QVGA、720p、1080p 等输出分辨率。
- 为视频播放器增加随机定位滑块、单帧前进和单帧后退。实现时需要跳转到最近同步帧，再逐帧解码到目标位置。
- 支持将一组 PNG 图片转换为视频。
- 支持连续播放具有不同编码参数的多个 MP4 文件，可能需要提前加载下一个视频以保证无缝切换。
- 实验 `glReadPixels()` 的替代方案并增加 PBO 速度测试；目前 Java 层似乎无法方便地实验
  `eglCreateImageKHR`。
- 增加对 `ImageReader` 的实验（需要 API 19）。
- 调查“双路解码”播放偶尔卡顿的原因。
- 为 `TextureViewGLActivity` 增加 FPS 指示器。
- 采集麦克风音频并与视频一起录制、封装。
- 同时预览前置和后置摄像头并排显示；该功能在部分设备上可能无法实现。
- 增加单个渲染线程使用不同 EGLContext 同时渲染到两个 `TextureView` 的测试。
