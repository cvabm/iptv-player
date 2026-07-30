# IPTV 播放器

Android 端 IPTV 观看应用，支持多种方式导入播放列表并播放直播流。

## 功能

- **URL 导入**：输入 M3U / M3U8 播放列表地址，自动下载解析
- **本地文件**：选择 `.m3u` / `.m3u8` 文件导入
- **单路流**：直接添加一条 `http(s)` / `.m3u8` 直播地址
- **粘贴导入**：粘贴完整 M3U 文本内容
- **频道列表**：按分组 Tab 筛选、关键词搜索
- **播放器**：Media3 ExoPlayer，支持 HLS，上/下一个频道、全屏
- **本地持久化**：频道与导入来源保存在 SharedPreferences

## 环境

- Android Studio / 命令行 Gradle
- minSdk 26，targetSdk 35
- JDK 17

## 构建

```bash
cd iptv-player
.\gradlew.bat assembleDebug
```

APK 输出：

```
app\build\outputs\apk\debug\app-debug.apk
```

## 使用说明

1. 安装并打开 **IPTV 播放器**
2. 点击右下角 **+** 或「导入播放列表」
3. 选择一种导入方式（URL / 文件 / 单路流 / 粘贴）
4. 在列表中点击频道进入播放
5. 播放页可切换上/下频道、全屏；失败可点重试

> 请自行准备合法可用的 IPTV 源。应用不内置任何直播源。

## 项目结构

```
app/src/main/java/com/example/iptvplayer/
  MainActivity.kt          # 频道列表与导入
  PlayerActivity.kt        # 播放页
  data/
    Channel.kt             # 数据模型
    M3uParser.kt           # M3U 解析
    PlaylistRepository.kt  # 导入 / 存储
  ui/
    ChannelAdapter.kt      # 列表适配器
```
