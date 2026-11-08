# ProcessCameraProvider API 使用详解

本文按本项目 CameraX `1.4.2` 的 `camera-lifecycle` 源码整理，介绍 `ProcessCameraProvider` 的初始化、设备查询、UseCase 绑定与解绑、UseCaseGroup、并发相机和配置 API。不同 CameraX 版本的实验性标注和方法签名可能变化，升级依赖后应以当前版本文档为准。

## 1. ProcessCameraProvider 的职责

`ProcessCameraProvider` 是 CameraX 面向应用的进程级相机 Provider，负责：

- 初始化 CameraX 并获取当前进程可用的相机。
- 将 `Preview`、`ImageCapture`、`ImageAnalysis`、`VideoCapture` 等 UseCase 绑定到 `LifecycleOwner`。
- 按生命周期自动启动、停止相机。
- 查询设备上可用的相机及支持的并发相机组合。
- 解除部分或全部 UseCase 的绑定。

每个应用进程只有一个 `ProcessCameraProvider` 实例，通过静态 `getInstance()` 获取。它不是每个 Activity 都创建一个的普通对象。相机设备等重量级资源由绑定的生命周期控制；部分相机静态信息可能缓存到进程结束。

如果希望减少 Provider、UseCase 创建和绑定代码，可考虑 `LifecycleCameraController`。当需要直接控制 UseCase 组合、ViewPort 或并发相机时，`ProcessCameraProvider` 提供更灵活的底层流程。

## 2. 获取 Provider

### 2.1 `getInstance(Context)`

```java
static ListenableFuture<ProcessCameraProvider> getInstance(Context context)
```

异步获取并初始化当前进程的 Provider。一般传入 `applicationContext`，避免把 Activity 长期持有在全局相机初始化过程中。Future 完成后再执行查询和绑定。

```kotlin
private lateinit var cameraProvider: ProcessCameraProvider

private fun initializeCamera() {
    val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
    providerFuture.addListener({
        try {
            cameraProvider = providerFuture.get()
            bindCameraUseCases()
        } catch (e: ExecutionException) {
            // 初始化失败，检查 cause，可能是 CameraUnavailableException 等原因。
            Log.e(TAG, "CameraX 初始化失败", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "等待 CameraX 初始化时被中断", e)
        }
    }, ContextCompat.getMainExecutor(this))
}
```

`ListenableFuture.get()` 只应在 Future 完成之后调用；上面的 listener 会在 Future 完成时执行。绑定与大多数 Provider 操作要求主线程，因此示例用 `ContextCompat.getMainExecutor()`。

初始化失败时，Future 会以 `InitializationException` 失败，其 `cause` 可提供更具体原因，例如无法访问相机服务的 `CameraUnavailableException`。应用应将失败反馈到 UI 或日志，不要假设设备必定有可用相机。

### 2.2 Kotlin 协程扩展 `awaitInstance`

CameraX 1.4.2 的 lifecycle 模块还提供 suspend 扩展：

```kotlin
val cameraProvider = ProcessCameraProvider.awaitInstance(applicationContext)
```

它以挂起函数等待 `getInstance()` 的 Future 完成，适用于已接入 CameraX 对应协程扩展依赖的项目。该扩展不是 Java API；未解析时检查 `camera-lifecycle` 版本及协程相关依赖。

## 3. 基础绑定流程

### 3.1 准备 Preview 并绑定 UseCase

`bindToLifecycle()` 的普通重载接收 LifecycleOwner、CameraSelector 和一个或多个 UseCase，返回 Camera：

```java
Camera bindToLifecycle(
    LifecycleOwner lifecycleOwner,
    CameraSelector cameraSelector,
    UseCase... useCases)
```

Kotlin 常见用法：

```kotlin
private lateinit var cameraProvider: ProcessCameraProvider
private lateinit var imageCapture: ImageCapture

private fun bindCameraUseCases() {
    val preview = Preview.Builder().build().also {
        it.setSurfaceProvider(binding.previewView.surfaceProvider)
    }

    imageCapture = ImageCapture.Builder().build()

    val camera = cameraProvider.bindToLifecycle(
        this,
        CameraSelector.DEFAULT_BACK_CAMERA,
        preview,
        imageCapture
    )

    // camera 提供 CameraInfo、CameraControl 等控制入口。
}
```

这里的 `this` 应是 `LifecycleOwner`，例如 Activity 或 Fragment 的 `viewLifecycleOwner`。如果在 Fragment 中绑定到 View，通常选择 `viewLifecycleOwner`，使相机生命周期与 Fragment 的 View 一致。

Preview、拍照、分析等 UseCase 最好在同一次 `bindToLifecycle()` 调用中绑定。CameraX 可以基于整个 UseCase 组合协商分辨率；分多次绑定时，分辨率选择会受绑定顺序影响，也可能触发相机重新初始化。

Preview 的 SurfaceProvider 必须正确设置，否则相机可能已绑定但没有画面显示。绑定并启动相机前应申请并获得 `CAMERA` 运行时权限；获取 Provider 本身可以放在权限申请前。

### 3.2 绑定到 UseCaseGroup

另一个重载将 UseCaseGroup 作为整体绑定：

```java
Camera bindToLifecycle(
    LifecycleOwner lifecycleOwner,
    CameraSelector cameraSelector,
    UseCaseGroup useCaseGroup)
```

UseCaseGroup 可将多个 UseCase 及共享配置（例如 `ViewPort`、CameraEffect）组合起来：

```kotlin
val group = UseCaseGroup.Builder()
    .addUseCase(preview)
    .addUseCase(imageCapture)
    .addUseCase(imageAnalysis)
    // 如需统一裁切区域，可在 PreviewView 完成布局后设置有效 ViewPort：
    // .setViewPort(viewPort)
    .build()

val camera = cameraProvider.bindToLifecycle(
    this,
    CameraSelector.DEFAULT_BACK_CAMERA,
    group
)
```

同一个 UseCase 如果出现在多个 UseCaseGroup 中，最近一次绑定调用中的组配置会生效。`UseCaseGroup.Builder.build()` 要求至少包含一个 UseCase；不支持的 CameraEffect 组合也可能导致构建失败。

### 3.3 返回的 `Camera`

绑定返回的 `Camera` 是当前 CameraX 会话的控制入口，常用内容包括：

- `camera.cameraInfo`：查询 zoom、torch、曝光状态及相机能力。
- `camera.cameraControl`：控制缩放、torch、对焦/测光等。

CameraSelector 与最终 Camera 可能因 UseCase 配置或设备要求有所不同。若绑定失败，常见原因是 Selector 无法解析、UseCase 已绑定到其他生命周期，或设备不支持该 UseCase 组合。

## 4. UseCase 绑定规则与限制

1. **每个 UseCase 同一时刻只能绑定到一个 Lifecycle 和一个相机选择器。** 把已绑定到其他生命周期的 UseCase 再次绑定会报错。
2. **一个 `bindToLifecycle()` 调用可以接收多个 UseCase。** 通常优先一次性绑定当前页面所需的全部 UseCase。
3. **设备能力限制组合。** CameraX 1.4.2 源码说明当前最多允许同时绑定三个 UseCase，但具体可用组合仍由设备硬件和相机实现决定；超出设备能力会抛出 `IllegalArgumentException`。
4. **Lifecycle 已处于 STARTED 或更高状态时，绑定会开始相机数据流。** 若相机已在运行，重绑定可能临时中断数据流。
5. **生命周期会自动管理活跃状态。** 当 Lifecycle 停止/销毁时，相机数据流相应停止；生命周期状态不能替代显式解绑来管理跨页面复用的 UseCase。
6. **相同 Lifecycle 上绑定不同相机。** 绑定到不同 CameraSelector 的 UseCase 可能只有一个相机处于活动状态；不要用普通多次绑定来代替并发相机 API。

## 5. 相机查询 API

### 5.1 `hasCamera(CameraSelector)`

```java
boolean hasCamera(CameraSelector cameraSelector)
```

检查设备上是否存在能匹配指定选择器的相机。可用于决定是否显示前置摄像头切换按钮：

```kotlin
val hasFrontCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
binding.switchCamera.isVisible = hasFrontCamera
```

若相机信息不可用，Provider 接口允许抛出 `CameraInfoUnavailableException`；实际错误处理应按应用场景选择回退 UI 或记录错误。

### 5.2 `getAvailableCameraInfos()`

```java
List<CameraInfo> getAvailableCameraInfos()
```

返回 CameraX 当前可用相机的 `CameraInfo` 列表。它可能受应用的 `CameraXConfig` 相机限制器影响，因此不一定涵盖设备物理层面的所有摄像头。

```kotlin
val cameraInfos = cameraProvider.availableCameraInfos
cameraInfos.forEach { info ->
    val facing = info.lensFacing
    // 结合 facing、zoomState、flashUnit 等信息展示相机能力
}
```

### 5.3 `getCameraInfo(CameraSelector)`

```java
CameraInfo getCameraInfo(CameraSelector cameraSelector)
```

根据选择器取对应相机的 `CameraInfo`。CameraX 1.4.2 中该方法标记为 `@ExperimentalCameraInfo`，Kotlin 调用需要 opt-in：

```kotlin
@OptIn(ExperimentalCameraInfo::class)
private fun queryCameraInfo(selector: CameraSelector): CameraInfo {
    return cameraProvider.getCameraInfo(selector)
}
```

Selector 无法匹配相机时会失败。此方法用于查询某个候选相机的信息；如果已经通过 `bindToLifecycle()` 得到 `Camera`，也可以从该对象的 `cameraInfo` 获取当前已绑定相机信息。

### 5.4 CameraInfo 的相机选择器

对于枚举得到的 `CameraInfo`，CameraX 可提供能唯一标识对应相机的 `CameraSelector`。这在构建相机列表或并发相机配置时有用。获取 selector 的具体 opt-in 注解按当前 CameraX 版本处理。

## 6. 解绑 API

### 6.1 `unbind(UseCase...)`

```java
void unbind(UseCase... useCases)
```

仅解除传入的 UseCase。未绑定的 UseCase 会被忽略。解除后可以把 UseCase 重新绑定到另一个 Lifecycle，但应用应检查并重置与 UseCase 关联的监听器、配置和状态。

```kotlin
cameraProvider.unbind(preview, imageCapture)
```

解绑参数应是此前绑定的同一批 UseCase 实例。普通单摄像头模式下该 API 在主线程调用；并发相机模式下不支持单独解绑，需先整体 `unbindAll()`。

### 6.2 `unbindAll()`

```java
void unbindAll()
```

解除当前 Provider 管理的全部 UseCase，并关闭没有剩余 UseCase 的打开相机。适合页面切换或需要整体重建相机配置时使用。

```kotlin
cameraProvider.unbindAll()
```

**重要：** `ProcessCameraProvider` 是进程级共享对象，`unbindAll()` 影响的是该 Provider 当前管理的全部绑定，不只当前 Activity/Fragment 的 UseCase。多页面或多组件共享 Provider 时，应由明确的相机所有者统一协调，避免一个页面误关另一个页面正在使用的相机。

生命周期结束会停止相机数据流，但在需要释放/替换 UseCase、切换单摄像头与并发模式或明确结束相机所有权时，仍应按设计显式调用 `unbind()`/`unbindAll()`。

### 6.3 `isBound(UseCase)`

```java
boolean isBound(UseCase useCase)
```

检查 UseCase 是否当前绑定到该 Provider。它可用于诊断或避免重复绑定；不要仅依赖该检查解决多线程竞态，绑定操作本身应遵循主线程规则。

```kotlin
if (!cameraProvider.isBound(preview)) {
    cameraProvider.bindToLifecycle(this, selector, preview)
}
```

## 7. UseCaseGroup 与 ViewPort

ViewPort 用于表示用户实际看到的相机传感器区域。把它放进 UseCaseGroup 后，Preview 和拍照/分析等 UseCase 可以共享一致的视口裁切，减少预览框与分析坐标、保存图片裁切不一致的问题。

当 ViewPort 来自 PreviewView 时，通常要等 PreviewView 完成布局并得到非空 ViewPort 后再构造 UseCaseGroup。用视口绑定时：

```kotlin
val viewPort = binding.previewView.viewPort
if (viewPort != null) {
    val group = UseCaseGroup.Builder()
        .setViewPort(viewPort)
        .addUseCase(preview)
        .addUseCase(imageCapture)
        .build()

    cameraProvider.bindToLifecycle(this, selector, group)
}
```

若使用的 View 或 CameraX 版本没有可用 ViewPort，可先用普通 vararg 重载绑定；不要传入尚未确定或与预览方向不一致的视口。

## 8. 并发相机 API

CameraX 1.4.2 支持受限的双摄像头并发绑定。该能力需由设备和 CameraX 实现支持，并非仅凭存在前后置摄像头即可保证。

### 8.1 查询支持的组合

```kotlin
val combinations = cameraProvider.availableConcurrentCameraInfos
```

该属性返回 `List<List<CameraInfo>>`：每个内层列表表示一组可以同时工作的相机组合。它标记为 `@ExperimentalCameraInfo`，使用前需 opt-in。应从该集合选择组合，而不是自行假设任意前后置摄像头都能同时打开。

系统功能标志 `PackageManager.FEATURE_CAMERA_CONCURRENT` 可作为设备能力参考，但仍应以 CameraX 返回的可用组合为准。

### 8.2 构造两个 `SingleCameraConfig`

并发重载签名：

```java
ConcurrentCamera bindToLifecycle(
    List<ConcurrentCamera.SingleCameraConfig> singleCameraConfigs)
```

列表必须恰好包含两个配置，每个配置提供 CameraSelector、UseCaseGroup 和 LifecycleOwner：

```kotlin
@OptIn(ExperimentalCameraInfo::class)
private fun bindFrontAndBackConcurrently(
    frontSelector: CameraSelector,
    backSelector: CameraSelector
) {
    val frontPreview = Preview.Builder().build().also {
        it.setSurfaceProvider(binding.frontPreview.surfaceProvider)
    }
    val backPreview = Preview.Builder().build().also {
        it.setSurfaceProvider(binding.backPreview.surfaceProvider)
    }

    val frontConfig = ConcurrentCamera.SingleCameraConfig(
        frontSelector,
        UseCaseGroup.Builder().addUseCase(frontPreview).build(),
        this
    )
    val backConfig = ConcurrentCamera.SingleCameraConfig(
        backSelector,
        UseCaseGroup.Builder().addUseCase(backPreview).build(),
        this
    )

    val concurrentCamera = cameraProvider.bindToLifecycle(
        listOf(frontConfig, backConfig)
    )
}
```

调用前应确认 Selector 确实对应 `availableConcurrentCameraInfos` 中同一组合。并发模式有以下限制：

- 目前该接口最多支持两路 CameraConfig。
- 如果其中一台相机已由其他 UseCase 占用，绑定可能以 `UnsupportedOperationException` 失败。
- 并发模式下 `unbind(vararg)` 不可用；需要通过 `unbindAll()` 结束并发绑定。
- 前后摄像头并发和同一逻辑相机下的多个物理摄像头是不同场景，设备支持条件也不同。
- ConcurrentCamera 虽然返回 Camera 对象集合，但相机的 UseCase 能力和输出组合仍受硬件限制。CameraX 1.4.2 对并发录像场景还有额外的 UseCase 组合要求。

## 9. 运行模式查询

```java
boolean isConcurrentCameraModeOn
```

Kotlin 中是属性 `cameraProvider.isConcurrentCameraModeOn`，用于检查 Provider 当前是否已绑定并发相机。该属性要求主线程调用。

## 10. Provider 全局配置

### 10.1 `CameraXConfig.Provider`

默认情况下，CameraX 使用默认配置。应用可让自定义 `Application` 实现 `CameraXConfig.Provider`，在应用层集中指定 CameraX 配置，例如 Camera2 实现和执行器：

```kotlin
class GrafikaApplication : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder
            .fromConfig(Camera2Config.defaultConfig())
            .setCameraExecutor(cameraExecutor)
            .build()
    }
}
```

要使用该方式，需要在 Manifest 中注册 Application 子类。Provider 的配置应在第一次调用 `getInstance()` 前准备好。

### 10.2 `configureInstance(CameraXConfig)`

```java
static void configureInstance(CameraXConfig cameraXConfig)
```

显式配置进程级 Provider 的另一种方式。CameraX 1.4.2 中此 API 标记为 `@ExperimentalCameraProviderConfiguration`，并且只能在 Provider 尚未配置、也未首次调用 `getInstance()` 前调用一次。配置之后 Application 的 `CameraXConfig.Provider` 会被忽略。

```kotlin
@OptIn(ExperimentalCameraProviderConfiguration::class)
fun configureCameraX(executor: Executor) {
    val config = CameraXConfig.Builder
        .fromConfig(Camera2Config.defaultConfig())
        .setCameraExecutor(executor)
        .build()

    ProcessCameraProvider.configureInstance(config)
}
```

不要在普通 Activity 中反复调用，也不要由共享库擅自调用：进程级单例配置应由应用所有者统一管理。多数应用无需自定义配置，直接使用默认配置即可。

## 11. 测试专用 API：`shutdownAsync()`

```java
ListenableFuture<Void> shutdownAsync()
```

源码中提供此方法用于测试：解除绑定、清理 Provider 并允许后续重新初始化，便于不同测试用不同 CameraX 配置。它标记为 `@VisibleForTesting`，不应用于生产环境常规页面退出或相机切换。生产代码使用 `unbind()` / `unbindAll()` 管理绑定和资源即可。

## 12. 生命周期、线程和错误处理建议

- 获取 Provider 可以在初始化流程中异步完成；Future listener 使用主线程 Executor 时，可直接构建并绑定 UseCase。
- `bindToLifecycle()`、`unbind()`、`unbindAll()` 及运行模式查询应在主线程执行。
- Activity 使用自身作为 LifecycleOwner；Fragment 的 View 持有预览时通常使用 `viewLifecycleOwner`。
- 绑定前先申请 `CAMERA` 权限。录音权限由启用音频的 VideoCapture 场景额外要求。
- 绑定异常不要吞掉：`IllegalArgumentException` 常提示相机/UseCase 组合不受支持，`IllegalStateException` 常提示 UseCase 已绑定或线程不正确。
- 不要为每个页面重复创建“自己的 Provider”；复用进程单例，并明确由谁负责解绑。
- 切换配置时优先解绑需要替换的 UseCase；只有确实要重建全部相机状态时才使用 `unbindAll()`。
- 不要在每次页面销毁时调用 `shutdownAsync()`。

## 13. 与 Provider 关联的相机控制 API

`ProcessCameraProvider.bindToLifecycle()` 返回 `Camera`。这是连接 Provider 和单个已绑定相机控制能力的入口：

```kotlin
val camera = cameraProvider.bindToLifecycle(
    this,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview,
    imageCapture
)

val cameraControl = camera.cameraControl
val cameraInfo = camera.cameraInfo
```

`Camera` 公开的主要方法是 `getCameraControl()` 和 `getCameraInfo()`。`getExtendedConfig()` 受 `@RestrictTo(LIBRARY_GROUP)` 限制，普通应用不应依赖它。相机还没有绑定时没有 `Camera` 可供控制；生命周期进入停止状态或所有 UseCase 被解绑后，CameraControl 会拒绝新的操作。

### 13.1 CameraControl 操作模型

`CameraControl` 提供会影响当前相机所有已绑定 UseCase 的异步操作：

| API | 作用 | 结果 |
| --- | --- | --- |
| `enableTorch(boolean)` | 开/关持续手电筒 | `ListenableFuture<Void>` |
| `startFocusAndMetering(FocusMeteringAction)` | 启动 AF/AE/AWB 对焦与测光区域 | `ListenableFuture<FocusMeteringResult>` |
| `cancelFocusAndMetering()` | 清除当前测光区域并取消自动对焦动作 | `ListenableFuture<Void>` |
| `setZoomRatio(float)` | 按倍率缩放 | `ListenableFuture<Void>` |
| `setLinearZoom(float)` | 按 0..1 线性值缩放 | `ListenableFuture<Void>` |
| `setExposureCompensationIndex(int)` | 设置曝光补偿档位 | `ListenableFuture<Integer>` |

Future 完成代表 CameraX 已应用或拒绝操作，并非可以同步读写的普通属性。相机关闭或生命周期停止时，Future 可能以 `CameraControl.OperationCanceledException` 失败；缩放、对焦、曝光等可被后续请求覆盖的操作，也可能取消旧请求。超出范围会以 `IllegalArgumentException` 失败。CameraX 定义的 `OperationCanceledException` 与开发者主动调用 `Future.cancel()` 不是一回事。

可用 listener 在主线程处理异步结果：

```kotlin
val zoomFuture = camera.cameraControl.setLinearZoom(0.4f)
zoomFuture.addListener({
    try {
        zoomFuture.get()
        // 缩放操作已应用
    } catch (e: ExecutionException) {
        // 检查 e.cause，处理操作取消或设备不支持
        Log.w(TAG, "缩放未能应用", e.cause ?: e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}, ContextCompat.getMainExecutor(this))
```

### 13.2 手电筒：`enableTorch()`

```java
ListenableFuture<Void> enableTorch(boolean torch)
```

控制相机的持续补光灯。它不同于 `ImageCapture` 的 `setFlashMode()`：拍照闪光灯只参与拍照曝光，torch 则会持续点亮；torch 开启时，即使 ImageCapture 闪光模式为 OFF，补光灯也会继续亮。

设备没有闪光灯单元时，调用不会打开灯，Future 会失败，`CameraInfo.torchState` 保持 OFF。调用前可用 `cameraInfo.hasFlashUnit()` 检查：

```kotlin
if (cameraInfo.hasFlashUnit()) {
    camera.cameraControl.enableTorch(true)
}

cameraInfo.torchState.observe(this) { state ->
    when (state) {
        TorchState.ON -> { /* 更新为已开启状态 */ }
        TorchState.OFF -> { /* 更新为已关闭状态 */ }
    }
}
```

运行时以 TorchState 为准，不要仅根据按钮点击推断灯已成功开启。切换摄像头后也应重新检查新相机是否带有闪光灯。

### 13.3 缩放：倍率与线性值

```java
ListenableFuture<Void> setZoomRatio(float ratio)
ListenableFuture<Void> setLinearZoom(float linearZoom)
```

- `setZoomRatio(ratio)` 使用相机倍率，范围为当前 `ZoomState.minZoomRatio..maxZoomRatio`。通常 `1.0f` 表示最小/原生视角倍率，但多摄融合或超广角设备的实际范围由 ZoomState 给出。
- `setLinearZoom(value)` 要求 `[0.0, 1.0]`，`0` 是最小缩放、`1` 是最大缩放。它使视场角近似线性变化，更适合滑块；倍率控制则更适合捏合缩放。
- 两种接口会同步更新 `ZoomState` 中的 `zoomRatio` 与 `linearZoom`。
- 请求值超出范围不会自动截断，Future 会失败。应用应先读取状态并 clamp。
- 连续发起新缩放时，CameraX 可能取消旧的未完成请求；界面应以最新 `ZoomState` 为准。

```kotlin
cameraInfo.zoomState.observe(this) { zoomState ->
    // 用 zoomRatio 更新倍率文字，用 linearZoom 更新滑块进度
}

val zoomState = cameraInfo.zoomState.value
if (zoomState != null) {
    val requestedRatio = 2.0f.coerceIn(
        zoomState.minZoomRatio,
        zoomState.maxZoomRatio
    )
    camera.cameraControl.setZoomRatio(requestedRatio)
}

// 滑块通常使用线性缩放接口，先限制到合法区间。
camera.cameraControl.setLinearZoom(sliderValue.coerceIn(0f, 1f))
```

当前相机是否支持缩放及实际倍率范围应从 `ZoomState` 读取，不要硬编码某个最大倍率。

### 13.4 对焦与测光：`startFocusAndMetering()`

```java
ListenableFuture<FocusMeteringResult> startFocusAndMetering(
    FocusMeteringAction action)
ListenableFuture<Void> cancelFocusAndMetering()
```

`FocusMeteringAction` 可设置一个或多个 MeteringPoint，并选择 AF（自动对焦）、AE（自动曝光）、AWB（自动白平衡）区域。单点构造器默认同时指定 AF/AE/AWB；默认自动取消时间为 5 秒。若只需要自动对焦，可只设置 `FLAG_AF`。

点按预览时应使用 PreviewView 的 `meteringPointFactory`，它负责将 View 坐标映射到相机区域；不要把屏幕像素直接当作传感器坐标：

```kotlin
private fun focusAt(x: Float, y: Float) {
    val point = binding.previewView.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
    )
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()

    if (!cameraInfo.isFocusMeteringSupported(action)) {
        return
    }

    val focusFuture = camera.cameraControl.startFocusAndMetering(action)
    focusFuture.addListener({
        try {
            val result = focusFuture.get()
            // result.isFocusSuccessful 可判断自动对焦是否成功
        } catch (e: ExecutionException) {
            Log.w(TAG, "对焦/测光操作失败", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }, ContextCompat.getMainExecutor(this))
}
```

`isFocusMeteringSupported(action)` 返回 false 时，该相机无法执行此动作；未提供设备支持的区域或点超出有效区域时，Future 也可能失败。新的 FocusMeteringAction 会取代旧动作。调用 `cancelFocusAndMetering()` 会清除 AF/AE/AWB 区域，并在支持时恢复连续自动对焦。

CameraControl 提供的是 AF/AE/AWB 区域动作，不提供通用的手动镜头焦距/焦点距离设置接口。若要控制 Camera2 的手动焦距，需要使用 Camera2 interop 等更底层方案，并承担设备兼容性处理。

### 13.5 曝光补偿

```java
ListenableFuture<Integer> setExposureCompensationIndex(int value)
```

`value` 是离散补偿索引，不是 EV 数值。支持范围和步长通过 `CameraInfo.exposureState` 获取：

```kotlin
val exposureState = cameraInfo.exposureState
if (exposureState.isExposureCompensationSupported) {
    val range = exposureState.exposureCompensationRange
    val requestedIndex = 2.coerceIn(range.lower, range.upper)

    camera.cameraControl.setExposureCompensationIndex(requestedIndex)
}
```

EV 补偿量约等于 `index * exposureCompensationStep`。例如步长为 1/3 EV，索引 `+3` 约为 `+1 EV`。不支持时范围是 `[0, 0]`、步长为 0，当前索引为 0。

该方法返回 `ListenableFuture<Integer>`；新曝光补偿请求会替代尚未完成的旧请求。建议在滑块拖动时合并或节流更新，避免大量过期请求。

### 13.6 CameraInfo 状态与基本属性

`camera.cameraInfo` 用于读取当前已绑定相机的静态属性和可观察运行状态。重点 API 如下：

| API / 属性 | 类型与含义 |
| --- | --- |
| `getTorchState()` | `LiveData<Int>`，`TorchState.ON/OFF` |
| `getZoomState()` | `LiveData<ZoomState>`，包含倍率、最小/最大倍率和线性缩放值 |
| `getExposureState()` | 当前曝光补偿索引、范围、步长和是否支持补偿 |
| `getCameraState()` | `LiveData<CameraState>`，监控相机打开/关闭及错误状态 |
| `hasFlashUnit()` | 相机是否有闪光灯单元 |
| `getLensFacing()` | 前置、后置、外接或未知镜头方向 |
| `getCameraSelector()` | 唯一匹配该相机的 CameraSelector |
| `getSensorRotationDegrees()` | 传感器相对设备自然方向的旋转角度 |
| `getSensorRotationDegrees(rotation)` | 相对指定 Surface rotation 的传感器旋转角度 |

LiveData 应使用具有生命周期的 Observer，避免在页面销毁后继续持有回调：

```kotlin
cameraInfo.cameraState.observe(this) { state ->
    when (state.type) {
        CameraState.Type.OPEN -> {
            // 相机已打开
        }
        CameraState.Type.PENDING_OPEN -> {
            // 相机暂时被更高优先级客户端占用，CameraX 等待重试
        }
        CameraState.Type.CLOSED -> {
            // 相机当前已关闭，可结合 state.error 判断原因
        }
        else -> {
            // OPENING 或 CLOSING
        }
    }
}
```

CameraState 还可能处于 `OPENING`、`CLOSING`。`state.error` 非空时可查看错误码；错误包括相机占用、UseCase 流配置失败、系统策略禁用或相机设备故障等。LiveData 可能合并非常短暂的状态变化，因此不能要求每个中间状态都一定被观察到。

### 13.7 CameraInfo 相机能力查询

CameraInfo 还提供以下能力查询：

| API | 用途与注意事项 |
| --- | --- |
| `getIntrinsicZoomRatio()` | 多摄像头系统中当前镜头相对默认镜头的近似视角倍率；属于近似信息，不等同于实时 ZoomState |
| `getSupportedFrameRateRanges()` | AE 算法支持的 FPS 范围；不保证每个分辨率/UseCase 组合都能使用 |
| `querySupportedDynamicRanges(candidates)` | 从候选动态范围中查询相机支持项；候选包含 SDR 时结果包含 SDR，Recorder 录像需另外考虑编码器能力 |
| `isLogicalMultiCameraSupported()` | 是否支持逻辑多摄像头 |
| `getPhysicalCameraInfos()` | 支持逻辑多摄像头时列出其物理相机信息；不支持时可能为空 |
| `isFocusMeteringSupported(action)` | 检查给定 AF/AE/AWB 测光动作是否至少有一个区域可用 |
| `isZslSupported()` | 是否支持零快门延迟拍照；CameraX 1.4.2 中标记为 `@ExperimentalZeroShutterLag` |
| `CameraInfo.mustPlayShutterSound()` | 查询系统是否要求应用播放快门音；静态辅助 API |

示例：查询设备支持的帧率与动态范围时，把它们视为能力提示，不要绕过 UseCase 对实际并发组合的协商：

```kotlin
val fpsRanges = cameraInfo.supportedFrameRateRanges
val supportedRanges = cameraInfo.querySupportedDynamicRanges(candidateRanges)
```

查询 ZSL 能力时需 opt-in，并且该能力成立还取决于 Android 版本和 PRIVATE 格式重处理支持：

```kotlin
@OptIn(ExperimentalZeroShutterLag::class)
val canUseZsl = cameraInfo.isZslSupported()
```

`CameraInfo.getImplementationType()` 和私有重处理能力查询在本项目所用版本标记为库组受限 API，不建议应用调用。部分能力也可能因 CameraX 实现类型、设备或系统版本而返回默认值或空集合。

### 13.8 Provider 与相机控制的使用边界

- `ProcessCameraProvider` 负责获取、绑定与解绑相机；已绑定相机的运行时控制走 `Camera.cameraControl`，能力和状态查询走 `Camera.cameraInfo`。
- Provider 的 `getCameraInfo(selector)` 可查询候选相机；绑定后使用返回的 `Camera.cameraInfo` 可确保观察的是当前会话对应相机。
- `CameraControl` 的命令异步执行。控制 Future 失败要处理；不要只更新 UI 而不等待状态反馈。
- 解绑全部 UseCase 后，之前拿到的 CameraControl 会停止接受操作。相机切换或重绑定后，重新从新返回的 Camera 取 CameraControl/CameraInfo。
- 开启 torch 与拍照闪光模式是两种设置；曝光补偿也不是手动快门速度或 ISO 的通用接口。

## 14. Preview、拍照与录像 API

本节介绍通过 `ProcessCameraProvider` 绑定的三类常用 UseCase。一般先创建 UseCase 并配置 Builder，再一次性传给 `bindToLifecycle()`。如需同时绑定 Preview、ImageCapture、ImageAnalysis、VideoCapture，是否能成功取决于设备支持的流组合，不能仅凭 UseCase 数量判断。

### 14.1 Preview：预览用例

#### 创建与连接显示目标

`Preview` 从相机获取连续图像流，本身不负责显示。创建后必须通过 `setSurfaceProvider()` 提供显示 Surface：

```kotlin
val preview = Preview.Builder()
    .setTargetRotation(binding.previewView.display.rotation)
    .build()
    .also {
        it.setSurfaceProvider(binding.previewView.surfaceProvider)
    }

val camera = cameraProvider.bindToLifecycle(
    viewLifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview
)
```

在 Activity 中把 LifecycleOwner 换成 Activity；在 Fragment 页面中通常使用 `viewLifecycleOwner`。`PreviewView.surfaceProvider` 处理 Surface 请求、预览裁切、旋转和显示变换，是普通预览的首选方式。

Preview 的核心运行时 API：

| API | 用途 |
| --- | --- |
| `Preview.Builder().build()` | 创建 Preview 用例 |
| `Preview.setSurfaceProvider(surfaceProvider)` | 设置/替换预览输出目标 |
| `Preview.setSurfaceProvider(executor, surfaceProvider)` | 指定 Surface 请求回调在哪个 Executor 执行 |
| `Preview.setTargetRotation(rotation)` | 动态更新预览目标方向，无需重建 UseCase |
| `Preview.getTargetRotation()` | 获取当前目标方向 |
| `Preview.getResolutionInfo()` | 绑定后读取实际选定分辨率与裁切信息；未绑定时可能为 null |
| `Preview.getTargetFrameRate()` | 查询期望帧率范围 |

调用 `setSurfaceProvider(null)` 会移除当前 SurfaceProvider，使 Preview 暂时不再提供有效输出。若更换 SurfaceProvider，CameraX 会将新的 Provider 作为该 Preview 的输出目标。

#### 分辨率、比例、动态范围与帧率

Preview 的主要创建参数都在 `Preview.Builder` 上：

```kotlin
val previewResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(
        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    )
    .build()

val preview = Preview.Builder()
    .setResolutionSelector(previewResolutionSelector)
    .setTargetRotation(binding.previewView.display.rotation)
    .build()
```

- `setResolutionSelector()` 是推荐的尺寸/比例选择入口。Preview 默认根据设备屏幕匹配分辨率，并受 CameraX 预览尺寸策略（通常不高于 1080p）约束；具体输出会随其他 UseCase 和硬件流组合变化。
- 旧的 `setTargetAspectRatio()`、`setTargetResolution()` 已废弃，且不可与 `setResolutionSelector()` 混用。新代码用 `ResolutionSelector` 配合 `AspectRatioStrategy`、`ResolutionStrategy` 表达偏好。
- `setDynamicRange()` 用于指定预览动态范围。默认 `DynamicRange.UNSPECIFIED`，让 CameraX 根据同一相机下其他 UseCase 协商；自定义 SurfaceProvider 时要正确处理 `SurfaceRequest.dynamicRange`。HDR 支持取决于相机、输出 Surface 和设备显示能力。
- `setTargetFrameRate(Range<Int>)` 指定目标帧率范围，不是强制帧率；设备会尽量满足，并可能降低分辨率。未设置时由 CameraX/相机选择。
- `setPreviewStabilizationEnabled(true)` 请求预览防抖。它只配置 Preview 流，不能保证录像也启用防抖；VideoCapture 有独立的防抖设置。
- Preview 镜像默认是仅前置镜头镜像。`setMirrorMode()` 在 CameraX 1.4.2 标记为 `@ExperimentalMirrorMode`，并且系统行为与 Android API 级别有关：API 33 及以上会设置输出镜像模式，API 32 及以下为 no-op。

Preview 和其他 UseCase 一起绑定时，应尽量让默认尺寸策略参与统一协商。强制很高的预览分辨率或固定帧率可能让拍照、分析或录像流组合无法建立。

镜像模式是本版本的实验性 API；Kotlin 调用处需使用 `@OptIn(ExperimentalMirrorMode::class)`。预览 UI 若使用 PreviewView，通常保留默认的前置镜头显示行为即可，不必额外设置镜像模式。

#### 自定义 SurfaceProvider

需要把预览交给 OpenGL、`SurfaceTexture` 或自定义渲染器时，可实现 `Preview.SurfaceProvider`。CameraX 通过 `onSurfaceRequested(SurfaceRequest)` 请求 Surface：

1. 准备好 Surface 后调用 `request.provideSurface(surface, resultExecutor, resultListener)`。
2. 暂时不能提供时调用 `request.willNotProvideSurface()`，不要让请求一直悬而未决。
3. CameraX 返回 Surface 使用结果后再按生命周期释放资源；同一 Surface 可能会被后续新的请求替换。
4. 需要正确处理 `SurfaceRequest` 的分辨率、变换信息、旋转和裁切；直接消费相机 Surface 时不能假定方向就是屏幕方向。

除非要接入自定义渲染管线，否则使用 `PreviewView.surfaceProvider` 可以避免自行管理这些 Surface 生命周期细节。

### 14.2 ImageCapture：拍照用例

#### 创建与拍照配置

```kotlin
val imageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
    .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
    .setJpegQuality(95)
    .setTargetRotation(binding.previewView.display.rotation)
    .build()
```

常用配置如下：

| Builder API | 用途与默认行为 |
| --- | --- |
| `setCaptureMode(mode)` | `CAPTURE_MODE_MINIMIZE_LATENCY` 默认，快门延迟优先；`CAPTURE_MODE_MAXIMIZE_QUALITY` 画质优先 |
| `setFlashMode(mode)` | `FLASH_MODE_OFF` 默认；也支持 `ON` / `AUTO`。无闪光灯时物理闪光模式按 OFF 行为处理 |
| `setTargetRotation(rotation)` | 设置图像目标方向；绑定后也可调用 `ImageCapture.setTargetRotation()` 动态更新 |
| `setResolutionSelector(selector)` | 选择拍照尺寸。默认优先 4:3，并使用可用的最高分辨率策略 |
| `setJpegQuality(1..100)` | 设置 JPEG 压缩质量；默认随模式变化，低延迟模式为 95，画质优先为 100 |
| `setScreenFlash(screenFlash)` | 提供屏幕补光实现；与 `FLASH_MODE_SCREEN` 配合，仅适用于前置镜头 |
| `setCropAspectRatio(Rational)` | 运行时设置输出裁切比例；可能引入裁切处理延迟 |
| `setIoExecutor(executor)` | 设置 ImageCapture 内部 IO Executor；与拍照回调 Executor 不同 |
| `setPostviewEnabled(true)` | 请求在最终图像处理完成前提供较低质量预览图，需设备支持 |

`CAPTURE_MODE_ZERO_SHUTTER_LAG` 在本版本为 `@ExperimentalZeroShutterLag`。可通过 `CameraInfo.isZslSupported()` 检查能力；即使设备支持，绑定 VideoCapture、闪光模式不是 OFF 或启用 OEM Extension 时，CameraX 也可能自动关闭 ZSL。

图片分辨率用 `ResolutionSelector` 配置。不要同时调用已废弃的 `setTargetAspectRatio()` / `setTargetResolution()`；它们与 ResolutionSelector 组合会在构建时抛出异常。绑定后可读取：

```kotlin
val resolutionInfo = imageCapture.resolutionInfo
// resolutionInfo 包含实际选定分辨率、裁切矩形和旋转角度等信息。
```

分辨率信息可能在重新绑定、改变方向或裁切比例后变化，使用前重新获取。设备上实际拍到的图像尺寸还可能因旋转、ViewPort 裁切或输出格式处理而与传感器方向的分辨率不同。

#### 保存拍照结果

`takePicture(OutputFileOptions, Executor, OnImageSavedCallback)` 将图像写入文件、MediaStore 或 OutputStream：

```kotlin
val photoFile = File(filesDir, "photo.jpg")
val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

imageCapture.takePicture(
    outputOptions,
    ContextCompat.getMainExecutor(this),
    object : ImageCapture.OnImageSavedCallback {
        override fun onCaptureStarted() {
            // 更新快门动画或拍照状态
        }

        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
            // 文件/MediaStore 写入成功；MediaStore 输出时通常可用 savedUri
        }

        override fun onError(exception: ImageCaptureException) {
            // 根据 imageCaptureError、message 和 cause 处理错误
        }
    }
)
```

常见输出选项构造方式：

```kotlin
// 保存到 MediaStore
val values = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, "photo.jpg")
    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
}
val mediaStoreOptions = ImageCapture.OutputFileOptions.Builder(
    contentResolver,
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    values
).build()

// 保存到 OutputStream
val streamOptions = ImageCapture.OutputFileOptions.Builder(outputStream).build()
```

如果目标是 OutputStream，`OutputFileResults.savedUri` 为 null。`OutputFileOptions.Builder.setMetadata()` 可附加 EXIF 元数据，例如地理位置和方向/镜像信息；写入位置元数据前应确保应用拥有合适的位置权限并符合隐私需求。

同一 ImageCapture 上连续调用 `takePicture()` 是串行执行的，后一次会排在前一次之后。拍照请求应在 `onImageSaved()` 或 `onError()` 中收尾，不要把方法返回当作图像已经写入。

#### 获取内存图像

`takePicture(Executor, OnImageCapturedCallback)` 不负责存盘，图像通过 `ImageProxy` 返回：

```kotlin
imageCapture.takePicture(
    imageExecutor,
    object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                val rotationDegrees = image.imageInfo.rotationDegrees
                // 处理图像；buffer 本身不保证已按该角度旋转。
            } finally {
                image.close()
            }
        }

        override fun onError(exception: ImageCaptureException) {
            // 处理拍照失败
        }
    }
)
```

必须在所有成功处理路径中关闭 `ImageProxy`，否则会耗尽图像缓冲区并影响后续拍照。`rotationDegrees` 表示应用图像数据以匹配目标方向所需的顺时针角度，不应假定像素缓冲区已旋转。回调 Executor 由调用者提供；耗时图像处理不要放主线程。

#### 拍照回调、错误和高级能力

两个拍照 Callback 均可能包含：

- `onCaptureStarted()`：曝光开始，可触发快门动画/声音。保存到文件时它先于对应 `onImageSaved()`。
- `onCaptureSuccess(image)` 或 `onImageSaved(results)`：对应的内存图像或存盘结果。
- `onError(ImageCaptureException)`：拍照、相机关闭或文件 IO 等失败。
- `onCaptureProcessProgressed(progress)`：设备支持时报告 0..100 的图像处理进度。
- `onPostviewBitmapAvailable(bitmap)`：已启用且设备支持 Postview 时，较早提供临时预览 Bitmap；它不是最终图像。

可通过 `ImageCapture.getImageCaptureCapabilities(cameraInfo)` 查询 Postview、处理进度和输出格式支持情况。CameraX 1.4.2 中 `setOutputFormat()`、`getSupportedOutputFormats()` 等接口受 `@ExperimentalImageCaptureOutputFormat` 标记。默认输出为 JPEG；Ultra HDR 输出格式 `OUTPUT_FORMAT_JPEG_ULTRA_HDR` 需先确认相机能力并 opt-in。

`ImageCaptureException` 的常见错误码包括 `ERROR_FILE_IO`、`ERROR_CAPTURE_FAILED`、`ERROR_CAMERA_CLOSED` 和 `ERROR_INVALID_CAMERA`。错误回调中同时记录 `message` 和 `cause`，便于区分保存路径错误、相机关闭和拍摄失败。

保存到文件时，通常让 CameraX 把目标旋转写入 EXIF；内存回调则根据 `image.imageInfo.rotationDegrees` 处理旋转。`ImageCapture.setTargetRotation()` 在拍照请求实际开始时生效，排队中的请求可能在前一个请求完成后才读取方向设置。

#### 屏幕闪光

在无物理闪光灯的前置镜头上，ImageCapture 可以通过 `ScreenFlash` 接口请求应用暂时提亮屏幕：

```kotlin
imageCapture.setScreenFlash(object : ImageCapture.ScreenFlash {
    override fun apply(
        expirationTimeMillis: Long,
        screenFlashListener: ImageCapture.ScreenFlashListener
    ) {
        // 在主线程显示白色补光层并提高屏幕亮度。
        showWhiteFlashOverlay()
        screenFlashListener.onCompleted()
    }

    override fun clear() {
        // 恢复原亮度并移除白色补光层。
        restoreScreenBrightness()
        hideWhiteFlashOverlay()
    }
})
imageCapture.flashMode = ImageCapture.FLASH_MODE_SCREEN
```

使用 `FLASH_MODE_SCREEN` 前必须已设置非空 ScreenFlash 且当前为前置相机，否则会抛出 `IllegalArgumentException`。CameraX 在每次屏幕闪光拍照时调用 `apply()`，应用完成 UI/亮度修改后应及时调用 `onCompleted()`，拍照结束后 CameraX 会调用 `clear()`。Activity/Window 销毁或切换相机时，更新或清除持有旧 UI 引用的 ScreenFlash。

### 14.3 VideoCapture 与 Recorder：录像用例

#### 分工与创建

`VideoCapture<T>` 是绑定到相机的录像 UseCase；`Recorder` 是通常使用的 `VideoOutput`，负责编码和写文件。创建并绑定时：

```kotlin
val recorder = Recorder.Builder()
    .setQualitySelector(Recorder.DEFAULT_QUALITY_SELECTOR)
    .build()

val videoCapture = VideoCapture.Builder(recorder)
    .setTargetRotation(binding.previewView.display.rotation)
    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
    // 仅在设备支持且产品需要时启用录像防抖。
    .setVideoStabilizationEnabled(true)
    .build()

cameraProvider.bindToLifecycle(
    this,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview,
    videoCapture
)
```

也可以将 `ImageCapture` 一起绑定，但录像、拍照和分析流的并发组合受设备限制。切换页面生命周期或相机前后摄像头时，若 VideoCapture 被解绑，非持久录像会自动结束。

若不需要设置 VideoCapture 的额外参数，也可简写为 `VideoCapture.withOutput(recorder)`；它会用默认 VideoCapture 配置创建相同的 UseCase。

#### 录像画质和 VideoCapture 配置

录像尺寸通过 `Recorder.Builder.setQualitySelector()` 选择，而不是给 VideoCapture 设置任意 `Size`：

| 配置 | 说明 |
| --- | --- |
| `Quality.UHD` / `FHD` / `HD` / `SD` | 约定的录像质量级别；设备可能只支持其中一部分 |
| `QualitySelector.from(...)` | 请求单个目标质量，可指定 fallback strategy |
| `QualitySelector.fromOrderedList(...)` | 按优先级尝试质量列表，可附加回退策略 |
| `Recorder.DEFAULT_QUALITY_SELECTOR` | 默认优先 FHD、HD、SD，并带有可回退策略 |
| `Recorder.Builder.setAspectRatio(AspectRatio.RATIO_4_3 / RATIO_16_9)` | 限定录制画面比例；与 QualitySelector 合并决定输出尺寸 |

例如，如果没有严格画质要求，默认选择器比硬性请求 UHD 更稳妥。若画质与比例的组合在相机或编码器上不存在，绑定 VideoCapture 会失败。可用 `QualitySelector.isQualitySupported(cameraInfo, quality)` 做能力预检，但最终还受并发 UseCase、动态范围和编码器影响。

`VideoCapture.Builder` 常用设置：

- `setTargetRotation(rotation)`：设置录像目标方向；绑定后也可用 `videoCapture.setTargetRotation()` 动态更新。更新不会改变正在进行的录制，只影响后续录像。输出可能通过视频旋转元数据、直接旋转像素或两者组合实现方向。
- `setMirrorMode(mode)`：配置输出镜像，默认为 OFF；常用 `MIRROR_MODE_ON_FRONT_ONLY` 只镜像前置录像。
- `setTargetFrameRate(Range<Int>)`：请求目标帧率范围，不保证硬件一定可以满足；可参考 `CameraInfo.supportedFrameRateRanges`。
- `setDynamicRange(dynamicRange)`：请求 SDR/HDR 视频动态范围；默认 SDR，必须同时满足相机和编码器支持。
- `setVideoStabilizationEnabled(true)`：请求录像防抖；Preview 防抖需单独设置，不能认为二者自动同步。
- `setResolutionSelector()` / `setTargetResolution()` 不应作为录像尺寸配置入口；VideoCapture 的分辨率由 Recorder 的 QualitySelector 与比例决定。

绑定后，`videoCapture.getOutput()` 可取回创建它时关联的 Recorder；`getTargetRotation()`、`getTargetFrameRate()`、`getMirrorMode()` 和 `isVideoStabilizationEnabled()` 可读取当前配置。目标帧率是与同一相机其他 UseCase 协商后的期望值，不一定等于设备实际录制帧率。

`Recorder.Builder` 还提供 `setExecutor()` 和 `setTargetVideoEncodingBitRate()` 等高级设置。Recorder Executor 同时运行视频/音频编码任务，官方建议可并行执行至少两个任务；一般用默认 Executor 即可。自定义目标码率以 bit/s 为单位，只约束视频编码码率且仍由平台按场景调整，不应当作音视频总码率保证。`setAspectRatio()` 可选 4:3 或 16:9；不指定时默认由 QualitySelector 决定。音频采集源等部分设置受库组限制，不应调用。

#### 输出位置与限制

`Recorder.prepareRecording(context, outputOptions)` 会创建 `PendingRecording`。CameraX 1.4.2 支持：

| OutputOptions | 用途 |
| --- | --- |
| `FileOutputOptions` | 保存到应用指定的 File |
| `MediaStoreOutputOptions` | 保存到 MediaStore，适合系统相册可见的视频 |
| `FileDescriptorOutputOptions` | 保存到 ParcelFileDescriptor；要求 Android API 26+ |

各 Builder 还可设置 `setFileSizeLimit(bytes)`、`setDurationLimitMillis(milliseconds)` 和 `setLocation(location)`。达到大小/时长限制会发出带对应错误码的 `Finalize` 事件；这类 Finalize 仍可能包含可播放的部分文件。

```kotlin
val contentValues = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, "video.mp4")
    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
}
val outputOptions = MediaStoreOutputOptions.Builder(
    contentResolver,
    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
)
    .setContentValues(contentValues)
    .setDurationLimitMillis(60_000)
    .build()
```

应用需按输出位置选择合适存储方式。MediaStore 的输出 URI 从最终的 `VideoRecordEvent.Finalize.outputResults.outputUri` 获取；不要在收到 `Start` 时就把文件视作完整成品。

#### 开始录像、音频与事件

原生 Recorder 流程是 `prepareRecording()` → 可选音频 → `start()`：

```kotlin
val pendingRecording = recorder.prepareRecording(this, outputOptions)
val hasAudioPermission =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

// shouldRecordAudio 表示用户明确选择了录音。
val configuredRecording = if (shouldRecordAudio && hasAudioPermission) {
    pendingRecording.withAudioEnabled()
} else {
    pendingRecording
}

recording = configuredRecording.start(
    ContextCompat.getMainExecutor(this)
) { event ->
    when (event) {
        is VideoRecordEvent.Start -> {
            // 录像已开始
        }
        is VideoRecordEvent.Status -> {
            val stats = event.recordingStats
            val durationNanos = stats.recordedDurationNanos
            val bytes = stats.numBytesRecorded
            // 更新计时和文件大小显示
        }
        is VideoRecordEvent.Pause -> {
            // 录像已暂停
        }
        is VideoRecordEvent.Resume -> {
            // 录像已恢复
        }
        is VideoRecordEvent.Finalize -> {
            if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                val uri = event.outputResults.outputUri
                // 录像文件已完成，可保存 URI 或通知媒体库
            } else {
                // 检查 event.error、event.cause；部分错误仍可能留下可用文件
            }
            recording = null
        }
    }
}
```

音频默认关闭。只有明确需要音轨且拿到 `RECORD_AUDIO` 运行时权限后才调用 `withAudioEnabled()`；权限未授予会导致安全异常/录像启动失败，Recorder 不支持音频也会失败。若要静音但保留音轨，可在录制中调用 `recording.mute(true)`；需要完全没有音轨则不要启用音频。

录像事件含义：

- `Start`：成功启动后的第一个事件。
- `Status`：周期性状态，可读已录时长、字节数和 AudioStats。
- `Pause` / `Resume`：暂停/继续操作生效后通知。
- `Finalize`：唯一最终完成/失败事件；检查 `error`、`cause` 和 `outputResults`。

若开始时就失败，监听器可能首先收到 `Finalize`，返回的 Recording 已结束。Finalize 的错误不总意味着文件不存在，例如文件大小或时长限制可能留下可播放内容；应根据错误类型和输出目标检查文件/URI。

#### Recording 控制与生命周期

```kotlin
recording?.pause()
recording?.resume()
recording?.mute(true)
recording?.stop()
```

- `pause()` / `resume()` 控制当前录制；实际切换以相应 Pause/Resume 事件为准。
- `stop()` 与 `close()` 等价，停止录制并最终收到 `Finalize`。不要在 `stop()` 返回后立即假设文件已经封装完成。
- Recorder 同一时刻只允许一个未完成的 Recording。
- 必须持有 `Recording` 强引用直到停止，否则对象被回收时录像会自动停止并触发 `ERROR_RECORDING_GARBAGE_COLLECTED`。
- 默认情况下，VideoCapture 从相机解绑会自动结束该录像。`PendingRecording.asPersistentRecording()` 可让录像跨 VideoCapture 解绑继续，但标记为实验性持久录像能力，应只在确有相机切换连续录像需求时使用。

### 14.4 三类 UseCase 的参数职责

| 需求 | 应配置的位置 |
| --- | --- |
| 预览画面 Surface、比例/旋转、Preview 防抖 | `Preview` |
| 拍照速度/画质、闪光灯、JPEG 质量、拍照结果 | `ImageCapture` |
| 视频编码画质、比例、音频、录制目标和运行状态 | `Recorder` + `PendingRecording` / `Recording` |
| 录像方向、镜像、目标 FPS、Video 防抖 | `VideoCapture` |
| 选择相机并绑定所有 UseCase | `ProcessCameraProvider.bindToLifecycle()` |
| 运行时通用变焦、torch、对焦测光、曝光补偿 | 绑定后返回的 `Camera.cameraControl` |

## 15. 用 CameraEffect 将 GLES 滤镜应用到输出

### 15.1 处理管线与目标

把 GLSurfaceView、TextureView 或普通 View 叠加到 `PreviewView` 上，只能改变屏幕显示，不会进入 ImageCapture 保存的照片或 VideoCapture 写入的录像。若要求预览、拍照和录像使用同一滤镜，应把 GPU 处理接入 CameraX 的 UseCase 输出管线：

```text
相机帧 -> CameraEffect / SurfaceProcessor -> GLES shader
                                      ├── Preview 输出
                                      ├── ImageCapture 输出
                                      └── VideoCapture / Recorder 输出
```

CameraX 1.4.2 关键接口：

| API | 职责 |
| --- | --- |
| `CameraEffect` | 声明处理器、目标 UseCase 集合和错误回调 |
| `CameraEffect.PREVIEW` | 将效果应用到预览 |
| `CameraEffect.IMAGE_CAPTURE` | 将效果应用到拍照输出 |
| `CameraEffect.VIDEO_CAPTURE` | 将效果应用到录像输出 |
| `SurfaceProcessor` | 以 Surface 为输入/输出的 GPU 后处理接口，适合 OpenGL ES / Vulkan |
| `SurfaceRequest` | CameraX 请求处理器提供相机帧输入 Surface |
| `SurfaceOutput` | CameraX 提供处理结果的输出 Surface 和关闭通知 |
| `ImageProcessor` | 对 ImageCapture 的 ImageProxy 图像做后处理；不是连续 Surface 的 GLES 入口 |

`SurfaceProcessor` 在本版本允许的目标组合包括 `PREVIEW`、`VIDEO_CAPTURE`、`PREVIEW | VIDEO_CAPTURE`、`PREVIEW | VIDEO_CAPTURE | IMAGE_CAPTURE`。若 Preview 和 ImageCapture 都要经过同一 SurfaceProcessor，使用三目标 mask；不绑定的 UseCase 不会产生输出。`PREVIEW | IMAGE_CAPTURE` 不是本版本允许的 SurfaceProcessor 目标组合。`ImageProcessor` 只支持 `IMAGE_CAPTURE`。

相同 CameraEffect 的默认行为是提供一个处理后的输出 Surface 给所有目标，由 CameraX 将结果分发到各 UseCase；这适合所有输出共享同一滤镜。预览、拍照、录像实际分辨率和编码格式不同，因此视觉效果可以相同，但像素结果不会逐点相同。`IMAGE_ANALYSIS` 不属于 CameraEffect 目标，分析器继续收到原始相机帧。

### 15.2 创建 GLES CameraEffect

`CameraEffect` 是抽象类，应用继承它并传入目标位掩码、执行器、SurfaceProcessor 和错误回调：

```kotlin
private class GlesCameraEffect(
    targets: Int,
    executor: Executor,
    processor: SurfaceProcessor,
    errorListener: Consumer<Throwable>
) : CameraEffect(targets, executor, processor, errorListener)
```

然后创建单线程 GL 执行器和自己的 `SurfaceProcessor` 实现：

```kotlin
val glExecutor = Executors.newSingleThreadExecutor()
val glesProcessor = GlesSurfaceProcessor(glExecutor)

val allTargets =
    CameraEffect.PREVIEW or
        CameraEffect.IMAGE_CAPTURE or
        CameraEffect.VIDEO_CAPTURE

val glesEffect = GlesCameraEffect(
    allTargets,
    glExecutor,
    glesProcessor,
    Consumer { error ->
        Log.e(TAG, "GLES 滤镜处理失败", error)
    }
)
```

`GlesSurfaceProcessor` 是应用的实现，不是 CameraX 提供的现成滤镜。GL Context、纹理、FBO、EGL Window Surface、shader、帧同步和资源回收都由应用管理。单线程 GL Executor 使 CameraX 的 Processor 回调、SurfaceTexture 帧处理与 EGL Context 尽量保持在线程一致性上。

### 15.3 通过 UseCaseGroup.addEffect() 绑定

Provider 的效果需加入 `UseCaseGroup`。普通 `bindToLifecycle(owner, selector, useCases...)` 重载无法附加 CameraEffect：

```kotlin
val preview = Preview.Builder().build().also {
    it.setSurfaceProvider(previewView.surfaceProvider)
}
val imageCapture = ImageCapture.Builder().build()
val recorder = Recorder.Builder()
    .setQualitySelector(Recorder.DEFAULT_QUALITY_SELECTOR)
    .build()
val videoCapture = VideoCapture.withOutput(recorder)

val group = UseCaseGroup.Builder()
    .addUseCase(preview)
    .addUseCase(imageCapture)
    .addUseCase(videoCapture)
    .addEffect(glesEffect)
    .build()

val camera = cameraProvider.bindToLifecycle(
    lifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    group
)
```

只有实际加入 UseCaseGroup 的目标会产生相应输出。若设备不支持 Preview、ImageCapture、VideoCapture 同时绑定，减少 UseCase 数量或按产品需求拆分拍照/录像流程。使用 `ViewPort` 时可同时在同一个 Group 设置 ViewPort，使拍照与预览裁切范围保持一致。

`addEffect()` 允许多个目标互不重叠的效果，但不能有两个效果同时声明同一目标。例如 `PREVIEW | VIDEO_CAPTURE` 和 `IMAGE_CAPTURE` 可分别由两个 CameraEffect 处理；两个都包含 `PREVIEW` 的效果会在 `build()` 时抛出 `IllegalArgumentException`。需要叠加多个视觉滤镜时，通常将多个 shader 组合在同一个 SurfaceProcessor 内，而不是为同一 UseCase 添加多个 CameraEffect。

### 15.4 SurfaceProcessor 的输入和输出生命周期

`SurfaceProcessor` 有两个由 CameraX 调用的入口：

```kotlin
class GlesSurfaceProcessor(
    private val glExecutor: Executor
) : SurfaceProcessor {
    override fun onInputSurface(request: SurfaceRequest) {
        // 在 GL 线程创建输入 SurfaceTexture/Surface，并调用 request.provideSurface(...)
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        // 获取 surfaceOutput.getSurface(...)，创建 EGL 输出并绘制处理后的帧
    }
}
```

上述是结构示意；`SurfaceTexture`、EGL 和错误处理应按应用的渲染框架实现。

#### 输入：`SurfaceRequest`

CameraX 调用 `onInputSurface(request)` 后，处理器要按 `request.getResolution()` 准备输入 Surface：

1. 在 GL 线程创建 OES 纹理和 `SurfaceTexture`，调用 `setDefaultBufferSize(width, height)`，再由它创建 Android `Surface`。
2. 调用 `request.provideSurface(surface, executor, resultListener)` 将 Surface 提供给 CameraX。
3. 注册 `SurfaceTexture.OnFrameAvailableListener`；帧到达时在 GL 线程调用 `updateTexImage()`，再运行滤镜 shader。
4. 只有 `provideSurface()` 的结果回调返回，CameraX 确认不再使用输入 Surface 后，才能释放 Surface、SurfaceTexture 和输入纹理。

一个正在服务的 SurfaceRequest 可能因为解绑、分辨率或动态范围改变、相机重建而取消。初始化失败时应尽快调用 `willNotProvideSurface()`；要更换仍在使用的输入 Surface 时可调用 `invalidate()` 请求新的输入。CameraX 可能在旧请求释放前发送新请求，因此处理器要能短暂同时管理新旧资源。

`request.getDynamicRange()` 可用于确认所请求的动态范围；若配置 HDR，输入/输出 Surface 格式及 GLES 纹理处理必须能保留对应位深，不能把 HDR 帧无意降成 8-bit SDR。

#### 输出：`SurfaceOutput`

在 `onOutputSurface(surfaceOutput)` 中调用：

```kotlin
val outputSurface = surfaceOutput.getSurface(glExecutor, Consumer { event ->
    if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
        glExecutor.execute {
            // 停止绘制并销毁该 Surface 对应的 EGL Window Surface。
            destroyEglWindowSurface(surfaceOutput)
            surfaceOutput.close()
        }
    }
})
```

返回的 `Surface` 作为 EGL Window Surface 的 native window。每帧将 CameraX 输出变换应用到输入纹理矩阵后，用 GLES 绘制到该 EGL Surface。`SurfaceOutput.getTargets()` 可查询它服务的目标。

CameraX 通过 `EVENT_REQUEST_CLOSE` 通知处理器某个输出不再需要。收到事件后应停止对该 Surface 的读写并调用 `surfaceOutput.close()`，CameraX 才会通知 Surface 所有者可以安全释放资源。示例中的销毁函数可用对应的 `SurfaceOutput` 实例查找并释放 EGL Window Surface。新输出可能在旧输出收到 close 请求前到达，必须逐个追踪 SurfaceOutput 和对应 EGLSurface，不能用一个全局对象覆盖后立即释放旧对象。

### 15.5 正确处理旋转、裁切和镜像

CameraX 的 SurfaceProcessor 效果默认需要处理任意输入到输出变换，包括相机方向、目标方向、ViewPort 裁切、缩放和前置镜头镜像。仅使用 `SurfaceTexture.getTransformMatrix()` 常会造成预览旋转、拍照方向或前置镜头镜像不一致。

每帧应取得 SurfaceTexture 的输入矩阵，并调用：

```kotlin
surfaceOutput.updateTransformMatrix(
    updatedTransform,
    surfaceTextureTransform
)
```

然后把 `updatedTransform` 提供给顶点 shader。CameraX 用该 API 追加 SurfaceOutput 所需的变换。若自行处理 `SurfaceRequest.TransformationInfo`，可观察 `setTransformationInfoListener()` 获取 crop rect、rotationDegrees、sensor-to-buffer 矩阵和镜像信息；镜像应在旋转后应用。不要把传感器坐标、输入纹理坐标、输出窗口坐标混为一套。

如果直接管理 SurfaceRequest，建议同时关注请求取消和 Surface 使用结果；不要在输入或输出仍被 CameraX 使用时销毁底层 BufferQueue/EGLSurface。

### 15.6 CameraEffect 与 ImageProcessor 的取舍

| 需求 | 推荐接口 |
| --- | --- |
| GLES 滤镜覆盖预览、拍照和录像 | 一个 SurfaceProcessor，目标为三类 UseCase |
| GLES 滤镜覆盖预览和录像 | SurfaceProcessor，目标为 `PREVIEW | VIDEO_CAPTURE` |
| 只对拍照图像做 CPU 后处理 | ImageProcessor，目标只能是 `IMAGE_CAPTURE` |
| Preview 上显示 UI 装饰，但照片/录像不需要该装饰 | 普通 View overlay，不使用 CameraEffect |

`ImageProcessor.process(Request)` 接收包含 ImageProxy 的请求，并需同步返回实现了 `ImageProxy` 的 `Response`；该调用运行在 CameraEffect 提供的 Executor 上，处理器必须在方法返回前完成输出。它适合拍照后处理，不是高帧率预览的推荐路径。若把 OpenGL 集成到 ImageProcessor，应用还要自行处理图像上传、像素格式转换、同步和输出缓冲区实现。

对 CameraEffect 输入帧处理失败时抛出 `ProcessingException` 并通过错误回调通知应用。仅目标为 Preview 时，CameraX 不一定将 SurfaceProcessor 错误转发给应用，处理器本身应主动报告。不要吞掉 GL/EGL 错误后继续输出未定义帧。

### 15.7 Controller 与 Provider 的接入差异

| CameraController | ProcessCameraProvider |
| --- | --- |
| 使用 `cameraController.setEffects(setOf(effect))` | 使用 `UseCaseGroup.Builder.addEffect(effect)` |
| Controller 内部负责创建 UseCaseGroup | 应用自己创建 UseCase、UseCaseGroup 并绑定 |
| `clearEffects()` 清除效果 | 新建不带该效果的 UseCaseGroup 并重新绑定 |
| 设置/清除效果会重新绑定；1.4.2 实现调用 `unbindAll()` | 更换 Group/Effect 时应用负责解绑和重新绑定 |

GLES SurfaceProcessor、CameraEffect 目标掩码、输入输出 Surface 生命周期和变换处理规则相同。Controller 的 `setEffects()` 是主线程 API；由于实现会调用 Provider 的 `unbindAll()`，共享进程相机的应用应集中管理效果变更。Provider 路径下不要把带效果的组与普通 vararg 绑定混用，否则效果不会加入该次绑定。

## 16. API 速查

| API | 作用 | 常见限制 |
| --- | --- | --- |
| `getInstance(context)` | 获取并初始化进程单例 | 异步 Future；应等待完成 |
| `bindToLifecycle(owner, selector, useCases...)` | 绑定一组 UseCase，返回 Camera | 主线程；UseCase 组合受设备限制 |
| `bindToLifecycle(owner, selector, useCaseGroup)` | 绑定共享 ViewPort/effects 的组 | 主线程；组至少有一个 UseCase |
| `bindToLifecycle(singleCameraConfigs)` | 绑定并发相机 | 实验性相机信息能力；恰好两个配置 |
| `hasCamera(selector)` | 检查选择器是否可用 | 可能抛出 CameraInfoUnavailableException |
| `getAvailableCameraInfos()` | 列出当前可用相机信息 | 受 CameraXConfig 相机限制影响 |
| `getCameraInfo(selector)` | 获取指定相机信息 | 1.4.2 标记 ExperimentalCameraInfo |
| `availableConcurrentCameraInfos` | 查询并发相机组合 | 设备支持有限；ExperimentalCameraInfo |
| `unbind(useCases...)` | 解绑部分 UseCase | 并发模式不支持单独解绑 |
| `unbindAll()` | 解绑 Provider 管理的全部 UseCase | 进程共享影响；主线程 |
| `isBound(useCase)` | 查询 UseCase 是否绑定 | 适合状态检查，不代替线程约束 |
| `isConcurrentCameraModeOn` | 查询并发模式是否启用 | 主线程 |
| `configureInstance(config)` | 显式配置进程级 CameraX | 实验性；必须在初始化前只调用一次 |
| `shutdownAsync()` | 关闭 Provider 并允许重初始化 | 测试专用 |
| `Camera.cameraControl` | 获取已绑定相机的异步控制入口 | 解绑/关闭后会拒绝操作 |
| `Camera.cameraInfo` | 获取已绑定相机的信息与状态 | 切换相机后应重新获取 |
| `CameraControl.setZoomRatio()` / `setLinearZoom()` | 按倍率/线性值缩放 | 范围从 ZoomState 读取 |
| `CameraControl.startFocusAndMetering()` | 启动对焦与测光区域动作 | 用 MeteringPointFactory 转换 UI 坐标 |
| `CameraControl.enableTorch()` | 开/关持续补光灯 | 需相机有闪光灯单元 |
| `CameraControl.setExposureCompensationIndex()` | 曝光补偿 | 索引范围从 ExposureState 读取 |
| `CameraInfo.getCameraState()` | 观察打开/关闭/错误状态 | 使用生命周期 Observer |
| `Preview.setSurfaceProvider()` | 为预览流提供 Surface | 常规 View 使用 PreviewView.surfaceProvider |
| `Preview.Builder.setResolutionSelector()` | 选择预览尺寸/比例策略 | 与旧 targetResolution/targetAspectRatio 不混用 |
| `VideoCapture.withOutput(recorder)` | 用默认配置创建录像 UseCase | 需要额外参数时使用 `VideoCapture.Builder(recorder)` |
| `ImageCapture.takePicture(OutputFileOptions, ...)` | 拍照并保存到文件/MediaStore/流 | 结果在回调中异步返回 |
| `ImageCapture.takePicture(Executor, ...)` | 拍照并返回 ImageProxy | 处理完成必须关闭 ImageProxy |
| `Recorder.prepareRecording(...).start(...)` | 创建并启动录像 | 音频默认关闭；保留 Recording 引用 |
| `Recording.pause/resume/stop/mute` | 控制当前录像 | 最终结果以 Finalize 事件为准 |

## 17. 项目关联信息

- CameraX 依赖版本定义：`app/build.gradle` 中的 `cameraxVersion = '1.4.2'`
- CameraX lifecycle 依赖：`androidx.camera:camera-lifecycle`
- 项目已有 Provider 导入位置：`app/src/main/java/com/android/grafika/day09/Day09Activity.kt`
- 常规拍摄若不需要直接管理 UseCase，项目也可使用 `LifecycleCameraController` 简化绑定流程。

