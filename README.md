# WxGlass

微信聊天「液态玻璃」气泡 —— 一个 LSPosed（libxposed API 102）模块。

把微信聊天气泡原本的绿色/白色 9-patch 背景换成**全透明 + 圆角描边 + 半透明磨砂填充**，并可选地在有壁纸的聊天里做边缘折射。不抓屏、不录屏、不替换气泡 View，只改背景与一次画笔颜色。

> 效果截图未随仓库发布：原始截图包含真实聊天内容与联系人信息。

---

## 功能

- **透明气泡**：移除微信原生气泡背景，改由一层覆盖层绘制圆角边框与填充
- **磨砂填充**：深色 `0x5E1A1A1C`、浅色 `0x5EFFFFFF`（约 37%），叠一层低 alpha 噪点
- **边框居中**：按每个气泡自身 9-patch 的左右 padding 求平均，边框正对文字框；左右不对称、带尾巴的气泡分别计算
- **深色右侧白字**：只在系统深色模式、只在右侧气泡，把 `NeatTextView` 的文本颜色字段临时改成白色，画完即还原
- **边缘折射**（可选）：用 SDF 圆角矩形 + `RuntimeShader` 对壁纸位图做边缘弯折和轻微色散；**仅当壁纸有内容时启用**（亮度标准差 > 8），纯色/纯黑背景自动关闭
- **窄气泡兼容**：宽度 ≥ 16dp 的文字气泡都会接管（例如只发一个"1"）

## 环境

| 项 | 值 |
|---|---|
| 设备 | OnePlus，Android 17 |
| 框架 | LSPosed 2.2.1（libxposed **API 102**） |
| 目标 | 微信 8.0.79（`com.tencent.mm`） |
| 编译 | JDK 17、Android SDK build-tools 34、`android.jar`（API 34） |

其他微信版本未验证：气泡查找依赖类名 `MMNeat7extView` / `ScrollControlRecyclerView` / `ChattingImageBGView`。

## 构建

```bash
# 依赖（不在仓库里，需自备）
#   tools/classes.jar   ← libxposed api 102 的 classes.jar
#   ../tools/jdk-17*    ← JDK 17
#   ../sdk              ← Android SDK（build-tools/android-14、platforms/android-34-ext12）

./build.sh          # 产出 WxGlass-<version>.apk（debug keystore 自动生成）
```

`build.sh` 流程：`javac → d8 → aapt2 link → 注入 classes.dex 与 META-INF/xposed → zipalign → apksigner`。

## 安装

```bash
adb install -r WxGlass-0.0.1-diag.apk
```

1. 在 LSPosed 管理器里启用模块，作用域勾选**微信**（只改数据库不触发重载，必须手动开关一次）
2. `adb shell am force-stop com.tencent.mm`，再打开微信
3. 进入任意聊天即可看到效果

日志（`adb logcat -s WxGlass`）会打印 `loaded` / `hooked` / `wrapped … bg=StateListDrawable` 等关键节点。

## 结构

```
src/dev/wxglass/
  GlassModule.java     入口：ViewRootImpl.setView / onResume / 生命周期 / onDraw 文本 hook
  GlassInstaller.java  每帧 tick：定位聊天页、包裹气泡、替换背景、应用右侧白字
  GlassOverlay.java    唯一渲染层：圆角路径、填充、描边、噪点、SDF 折射
  ClearDrawable.java   透明气泡背景
  Backdrop.java        深浅色判断与按类名查找 View
  Watchdog.java        兜底扫描（WindowManagerGlobal.mViews）
  Probe.java/TreeDump.java  调试工具（默认不启用）
```

渲染位置（关键）：

```
ChattingContent
  FrameLayout
    ChattingImageBGView        ← 壁纸
    GlassOverlay               ← 边框/填充/折射画在这里（消息列表之下）
  MMWeUIBounceView
    MMChattingListView         ← 气泡文字（在覆盖层之上，所以文字始终可见）
```

## 为什么文字颜色要那样改

反汇编 `classes16.dex` 里 `NeatTextView.onDraw`：

```
iget        v1, v0, NeatTextView.o:I            # 颜色存在 int 字段
iget-object v2, v0, NeatTextView.h:TextPaint
invoke-virtual {v2, v1}, Paint.setColor(I)      # 每帧重新写进画笔
```

结论：`setTextColor` 是空实现、改 Paint 会被下一行覆盖，**只有改颜色字段本身**才生效。因此右侧白字的做法是：进入 `onDraw` 前把等于当前文字色的 int 字段置白，`proceed()` 后立即还原（每帧一次，`hit=1` 精确命中）。

## 已知限制

- 纯表情气泡、红包/卡片类气泡走不同的 item 视图，不接管，保持微信原背景
- 边缘折射需要壁纸有内容；纯色背景自动跳过
- 覆盖层与列表是两个兄弟节点，快速滚动时边框可能落后一帧
- 只验证了微信 8.0.79；升级微信后类名或字段布局可能变化

## 回退

- 关掉填充：`GlassOverlay.ENABLE_FILL = false` → 回到纯描边
- 关掉折射：`GlassOverlay` 里 `mLens` 置空
- 覆盖层创建失败时 installer 不会改动任何气泡背景（微信原样显示）

## 声明

个人自用/学习项目。修改第三方应用界面存在账号风险，使用者自行承担；请勿用于分发或商业用途。
