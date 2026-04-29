# GrayMusic - 灰度音乐软件

## 页面流程

1. **MainActivity（权限页）**：白底 + 两个黑色圆角按钮（白字）
   - "允许扫描音频文件" / "允许通知"
   - 授予后按钮变**深灰 `#3D3D3D`**，文字保持白色
   - 两个都同意后自动跳转
2. **ScanActivity（扫描页）**：扫 Music 文件夹，显示 `X / X 首歌曲` 进度
3. **SongListActivity（主页）**：🎉 恭喜你成功进入软件 + 所有歌曲列表

## 歌曲列表每一项显示

| 内容 | 说明 |
|------|------|
| 封面 | MediaMetadataRetriever 异步提取，有 LRU 内存缓存；取不到时用音符占位图 |
| 歌名 | MediaStore title，缺省回退到文件名 |
| 歌手 | MediaStore artist + 格式标签（如 `Coldplay · FLAC`） |
| 时长 | `mm:ss` 格式化 |

## 支持格式
MP3 / FLAC / M4A / OGG / WAV（按扩展名白名单）

**封面提取覆盖情况**（Android 系统 API 限制）：
- ✅ MP3 / FLAC / M4A：基本都能提到嵌入封面
- ⚠️ OGG：部分设备能提，部分不能
- ❌ WAV：规范不嵌封面，一律显示音符占位

## App Icon
- 白底居中黑色音符
- `res/drawable/music_note_24.xml`（可直接用 `<ImageView android:src="@drawable/music_note_24" />`）
- Launcher 图标走 adaptive icon（API 26+）+ 5 种密度 PNG fallback

## 技术配置

| 项目             | 版本 |
|------------------|------|
| AGP              | 8.5.2 |
| Gradle           | 8.7 |
| Kotlin           | 1.9.24 |
| compileSdk       | 34 |
| minSdk           | 24 (Android 7.0+) |
| Java / jvmTarget | 17 |

依赖：`androidx.core-ktx` / `appcompat` / `material` / `recyclerview` / `kotlinx-coroutines-android`

## 导入步骤

1. 解压 zip
2. Android Studio → **File → Open** → 选 `GrayMusic` 文件夹
3. 首次打开会提示 **gradle-wrapper.jar is missing** → 点 **"OK, auto-fix it"** / **"Create Gradle wrapper"**
4. 等 Sync 完成（首次下载 Gradle 8.7，~100MB，需要网络）
5. 运行到设备或模拟器

## 测试

把几个 `.mp3` / `.flac` / `.m4a` / `.ogg` / `.wav` 文件放到**内部存储 / Music / ** 目录再启动 app。

## ⚠️ 如果 Sync 失败（保底方案）

Android Studio → **File → New → New Project → Empty Views Activity**，包名 `com.example.localmusicapp`，Kotlin，minSdk 24。等新项目 Sync 成功后，把本 zip 里的源文件贴过去覆盖：

- `AndroidManifest.xml`
- `java/com/example/localmusicapp/*.kt`（6 个 Kotlin 文件）
- `res/layout/*.xml`、`res/drawable/*.xml`、`res/color/*.xml`
- `res/mipmap-*/` 下所有图标
- `res/values/themes.xml` 和 `res/values-night/themes.xml`
- `app/build.gradle` 的 `dependencies` 块合并过去

## 项目结构

```
GrayMusic/
├── build.gradle / settings.gradle / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/localmusicapp/
        │   ├── MainActivity.kt            # 权限页
        │   ├── ScanActivity.kt            # 扫描页
        │   ├── SongListActivity.kt        # 主页 - 歌曲列表
        │   ├── SongAdapter.kt             # 列表适配器
        │   ├── MusicScanner.kt            # 扫描核心
        │   ├── CoverLoader.kt             # 封面异步加载 + 缓存
        │   └── ScanResultHolder.kt        # 扫描结果单例
        └── res/
            ├── layout/                    # 4 个布局 (白底黑按钮)
            ├── drawable/                  # 音符图标、按钮背景、icon 前景
            ├── color/                     # 按钮文字 selector
            ├── values/ + values-night/    # 强制亮色主题
            └── mipmap-*/                  # launcher icon 全套
```

## 当前安装版本

V1.2.0-Alpha
