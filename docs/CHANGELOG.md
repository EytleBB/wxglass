# WxGlass — 微信聊天气泡液态玻璃（进行中）

基于 LSPosed（libxposed API 102）的独立模块，宿主 `com.tencent.mm` 8.0.79。
当前版本已能在聊天页把文字气泡替换成"壁纸取样 + 半透明染色 + 原 9-patch 形状"的玻璃层。

## 现状

- 模块可编译、可安装、已启用，微信进程内生效（`WxGlass` tag）。
- 聊天页识别：`ChattingUILayout`
- 消息列表：`MMChattingListView` → `ScrollControlRecyclerView` → item `kh`
- 气泡视图：`com.tencent.mm.ui.widget.MMNeat7extView`，背景是 `StateListDrawable(170x144)`
- 壁纸视图：`com.tencent.mm.ui.chatting.ChattingImageBGView`（1440x2568）
- 玻璃层（v5，透镜模型）：9-patch 距离场 → 高度场 `h=sqrt(1-(1-d)²)` → 法线 → 折射 `N.xy/N.z` + 菲涅尔轮缘 + Blinn-Phong 高光；按压时高度场加高斯鼓包，形变走法线而不是贴亮斑
- 玻璃层（v3，真·实时）：每帧把壁纸 View 录进气泡自己的 `RenderNode`（按屏幕坐标对齐）→ GPU `RenderEffect` 链：`blur(6dp)` → `RuntimeShaderEffect(lens, "content")` → 折射/色散/rim → 画到气泡位置 → 9-patch alpha 遮罩
- 不是缓存图：`RecordingCanvas` + `RenderNode` 每帧重录，页面滑动/转场时玻璃里的内容跟着动（已实测）
- 回退路径：API<33 或壁纸视图不可用时，退回"预模糊位图 + 平涂"，不崩
- 距离场：`Sdf.java` 对气泡 alpha 做两遍 chamfer 距离变换，按 16dp 归一化；每个尺寸算一次并缓存
- 着色：从 9-patch 中心像素取原气泡色，作为 shader 的 tint（46%~52%），自己绿/对方白自动跟随，不需要写死颜色

## 已验证

- 文字气泡成功接管（日志 `wrapped com.tencent.mm.ui.widget.MMNeat7extView ...`）
- 背景取样位置正确（`ChattingImageBGView at 0,333`）
- 深色模式下文字仍可读（保留了原气泡底色）

## v15 磨砂填充（当前）

- 圆角矩形内填充：同一条 Path，`canvas.drawPath`，不会越过描边；overlay 在消息列表之下，文字仍绘制在最上层
- 颜色：深色主题 `0xB81A1A1C`，浅色主题 `0xB8FFFFFF`；若读不到文字颜色则退回按主题判断
- 取色规则改为**按文字亮度**：文字偏白就用深填充，文字偏深就用白填充（微信在某些浅色聊天里仍用白字，纯白填充会看不见）
- 磨砂颗粒：96x96 噪点 `BitmapShader` REPEAT，alpha 深色 0x20 / 浅色 0x18
- 描边颜色随填充反转（深填充→白边，浅填充→深边）
- 回退：`GlassOverlay.ENABLE_FILL = false` 回到纯边框；overlay 创建失败时 installer 不改气泡
- 全程无背景抓取，因此不存在"旧颜色跟着走"的路径

## v14 边框偏移（旧）

- 气泡边框不再贴 View 边界，按文字实际内边距居中：`shift = (padLeft - padRight) / 2`
- 再往"远离头像"方向多推 2dp：左气泡 +2dp，右气泡 -2dp
- 文字在气泡里左右对称了，边框和头像之间也多出一点空隙

## v13 右侧白字（旧）

- 气泡仍然全透明、只留边框
- 右侧（自己发的）气泡：反射调用气泡 View 的 `setTextColor(0xFFFFFFFF)`，文字改白
- 判定：气泡屏幕中心 x > 屏宽一半；每 wrap 一次打一次标记，回收重绑后重新应用

## v12 全透明 + 只留边框（旧）

- 气泡背景 = 完全透明（`ClearDrawable`），不抓取、不模糊、不折射、不染色
- 只剩一圈圆角描边：1.1dp，上亮下暗的白渐变（0x99 → 0x33），外加 1px 深色发丝线
- 半径 `min(h*0.46, 26dp)`；所有渲染逻辑收进 `GlassOverlay` 一个文件
- 同时删掉并行实现：GlassDrawable / GlassShader / Sdf / BeadLensDrawable / BeadDrawable / BarBackdrop / StackBlur

## v11 overlay 逐气泡对齐（旧）

- 修 v10 的 bug：overlay 里用的是 `BarBackdrop.draw`（按父容器坐标裁剪），每个气泡拿到的偏移是错的 → 所有气泡同一块颜色、右半透明
- 改成逐气泡直译：`rc.translate(wallpaperPos - (bubblePos - pad)); wallpaper.draw(rc)`，每个气泡抓自己脚下的那块壁纸
- overlay 仍然每帧比较气泡坐标，变了就重绘

## v10 屏幕空间 overlay（旧）

- 根因：玻璃画在气泡自己的 display list 里，列表滚动时该 list 被平移复用，`draw()` 不重跑 → 内容跟着 View 走
- 改法：把玻璃从气泡背景里拿出来，放到**壁纸容器里的一个全屏 overlay**（在壁纸之上、消息列表之下）。气泡背景改成透明 `ClearDrawable`，文字仍然画在玻璃之上
- overlay 每帧比较所有气泡的屏幕坐标，变了就 `postInvalidateOnAnimation()`；绘制时按当前坐标重新抓取，所以不可能再用到旧画面
- 代价：滚动时每帧 N 个气泡各重录一次 RenderNode（18dp 模糊），可能掉帧

## v9 滚动跟随（旧）

- 问题：抓取是屏幕锚定的，View 的 display list 在滚动时被复用，`draw()` 不重跑 → 气泡里冻在首次截图的内容
- 修复：每帧检查 `getLocationOnScreen` 与上次抓取位置；变了就 `invalidate()`；列表首个 child 的 top 变化时强制全部重录
- 代价：滚动时每个可见气泡每帧重录一次 RenderNode

## v8 透明磨砂（旧）

- 底栏管线不动，只调材质：blur 4dp → 18dp、saturation 1.5 → 1.08、surface wash 0x2C → 0x1A、底色 0x22 → 0x14、描边 1.1dp → 0.8dp
- 加磨砂颗粒：96x96 随机噪点，REPEAT 平铺，alpha 0x18/0x1C
- pad 14dp → 30dp（模糊半径变大后取样余量）

## v7 底栏同款透镜（旧）

- 直接搬运底栏模块：`Backdrop`（`ViewGroup.drawChild` 回放已录制的 display list）→ `BarBackdrop`；`LiquidGlassPanel` 的 `LENS_SHADER`（圆角矩形 SDF 折射）+ `blur 4dp + saturation 1.5` 链 + `surface wash + 1dp highlight stroke` → `BeadLensDrawable`
- 抓取目标改成聊天壁纸容器（`ChattingImageBGView` 的父 FrameLayout），气泡不会折射到自己
- 形状自己画：`radius = min(h*0.46, 26dp)`，无 9-patch、无尾巴
- 参数：pad 14dp、refraction 8dp、blur 4dp、底色 0x22

## v6 水珠（旧）

- 不再截背景、不再用 9-patch 形状：`BeadDrawable` 自己画圆角胶囊（radius = min(h*0.46, 26dp)），没有尖角/尾巴
- 材质全部由 canvas 渐变合成：水体渐变 + 22%~28% 原气泡底色 + 上下内阴影 + 左上主高光 + 右下小反光 + 底部焦散 + 菲涅尔描边
- 按压：高光随弹簧放大（260/16），松开回弹
- 旧路径（GlassDrawable / Backdrop 采样）保留但不再挂载

## 水滴按压（v4）

- 触摸：hook `View.dispatchTouchEvent`，只读不消费，长按菜单/点击不受影响；另以 `isPressed()` 兜底（即使触控被宿主吃掉也能起效）
- 弹簧：stiffness 210、damping 13（欠阻尼），按下 `0 → 1` 有过冲，松手回弹到 0 并轻微"吸"一下
- 着色器：触点处高斯鼓包 `bulge * exp(-|q|²)`，同时叠加到折射偏移、rim 和镜面高光；松手后的负值表现为向内吸的凹陷
- 每帧仅在按压值变化时重建 `RenderEffect` 链，静止时不重建

## 待做

1. ~~静态取样~~ 已改为每帧实时录屏；~~AGSL 折射~~ 已接。
2. 水滴参数（半径=短边 46%、强度 11dp、高光 0.42）还需按真机手感调
3. 观感微调：高光 + 底色薄层让气泡略偏白，可按壁纸亮度自适应；文字对比度在亮壁纸下仍需兜底。
3. 交互态：长按/多选/语音播放时 `StateListDrawable` 状态变化要重新生成遮罩。
4. 性能：现在每帧扫描整棵树 + 每气泡 `saveLayer`；需要改成脏标记 + 只在滚动/换页时重绘，并去掉诊断日志。
5. 收集遗漏形态：引用、语音、图片、卡片是否要接管（目前只接管 `MMNeat7extView`）。
6. 版本适配：类名换成 `ChattingUILayout`/`MMChattingListView`/`MMNeat7extView` 三处锚点，升级后需回归。

## 构建

```bash
cd wxglass
JAVA_HOME=../tools/jdk-17.0.21+9 ./build.sh   # 输出 WxGlass-<version>.apk
adb install -r WxGlass-0.0.1-diag.apk
```

依赖：`../sdk`（build-tools 34 + android-34-ext12）、`tools/classes.jar`（libxposed api 102）。

## 关键源文件

| 文件 | 作用 |
|---|---|
| `GlassModule.java` | 入口：`ViewRootImpl.setView` / `Activity.onResume` / 生命周期回调 + Watchdog |
| `GlassInstaller.java` | 聊天页识别、列表定位、逐条包装、诊断日志 |
| `GlassDrawable.java` | 玻璃绘制：取样、染色、高光、形状遮罩 |
| `Backdrop.java` | 壁纸抓取 + 降采样 + 模糊 |
| `Watchdog.java` | 兜底：每 4s 从 WindowManagerGlobal 取根视图并触发扫描 |
| `StackBlur.java` | MIT，来自 liuran001/WeChat-LiquidGlass |
