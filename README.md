# text2sound · 声笺

安卓中文文字转语音应用，仓库：<https://github.com/itsxt/text2sound>。

使用 Android 系统 `TextToSpeech` 的原生应用，Java + XML，无第三方运行时依赖。无需登录、API Key 或自建后端，支持 Android 8.0 及以上。

## 功能

- 输入、粘贴文字，示例文本，最多 20,000 个 UTF-16 字符。
- 专注中文普通话，无英文适配或语言切换入口。
- 枚举引擎提供的音色，显示离线 / 联网标记，记住中文音色选择。
- 0.5–2.0 倍语速和音调，开始 / 停止朗读。
- 长文本按标点分段，避免截断 emoji 的 UTF-16 代理对。
- 导出 WAV，通过 Android 文件选择器保存，无需存储权限。
- 自动保存草稿和声音参数，保存最近 10 段朗读文字，可清除历史。
- 处理语音引擎不可用、语音包缺失、合成失败、音频焦点丢失和导出超时。

## 安装和使用

调试安装包：`dist/shengjian-debug.apk`。将 APK 传到安卓手机并安装即可试用。

1. 打开应用，点击“试试示例”或输入文字。
2. 向下滚动选择中文音色、语速和音调。
3. 点击“开始朗读”；播放过程中可随时停止。
4. 点击“导出音频”，生成完成后选择保存目录。

若无法发声，点击“语音引擎与语音包”，在系统文字转语音设置中选择可用引擎并下载对应语言。部分引擎会在报告支持某语言后，仍因缺少数据或网络不可用而合成失败；应用会显示错误并提供设置入口。

“离线”来自引擎返回的标记，仍需要事先安装语音包。“推荐音色”优先选择引擎提供的离线中文音色；没有离线音色时使用系统默认音色，可能需要联网。应用自身不申请网络权限，但系统语音引擎可能会联网处理文字；是否离线和音质取决于所选引擎及音色。

## 开发

在 Android Studio 中打开本目录。配置 JDK 17、Android SDK Platform 34，在 `local.properties` 中填写本机 `sdk.dir`（此文件不提交）。工程固定使用 Android Gradle Plugin 7.3.0 和 Gradle 7.6.3，便于兼容当前开发机的本地工具链。

```sh
./gradlew assembleDebug lintDebug
./scripts/test-core.sh
```

首次构建需要下载 Gradle 和 Android 构建依赖。APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。核心测试只依赖 JDK，可使用 `JAVA_HOME` 指定 JDK 路径。

安装到指定测试设备：

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

## 代码结构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 界面、草稿与历史、参数持久化、系统文件保存 |
| `SpeechEngine.java` | TTS 生命周期、任务标识、分段调度、音频焦点、错误处理 |
| `TextChunks.java` | 保留字符和代理对的长文本分段 |
| `WaveFiles.java` | 校验并合并 RIFF/WAV 音频，处理额外元数据块 |
| `tests/CoreTests.java` | 随机 Unicode 分段、WAV 合并和损坏文件回归测试 |

## 当前版本边界

- 这是可安装的第一版调试应用；应用 ID 为 `com.shengjian.tts`，名称可修改。
- 当前为前台朗读。离开应用、锁屏或重建页面会停止进行中的朗读 / 合成；返回后可重新开始。
- 导出格式为 WAV，兼容引擎输出的 PCM / 浮点 WAV，不包含 MP3 编码或声音克隆。
- 草稿和历史存储于应用私有空间，禁用云备份与设备迁移；卸载会清除本地记录。用户导出的文件保留在选择的目录。
- 当前 `targetSdk` 为 34；正式商店发布前应按目标商店的当期要求升级 SDK，并配置正式签名。

## 验证

核心测试覆盖 500 组随机 Unicode 文本、分段重组、代理对边界、WAV 元数据与长度、不同采样率、空音频和截断音频。设备验证记录见 `TESTING.md`。
