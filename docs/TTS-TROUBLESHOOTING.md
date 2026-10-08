# 中文语音合成失败排查

## 2026-10-08 模拟器故障

环境：Android 10 / API 29，Google TTS `3.17.5.247520352`。

语音引擎进程在本次请求中记录了完整的失败路径：

```text
11:15:06.940 TTS.LorryVoiceDataDownl: cmn-cn (1) has status 1, waiting for download
11:15:06.940 TTS.Dispatcher: Local voice not installed, we use the network voice
11:15:08.943 TTS.NetworkSynthesizer: We timed out and used all of our retries, failing synthesis request
11:15:08.945 TTS.GoogleTTSServiceImp: Synthesis failure with error status code: -7
```

中文离线语音数据尚未安装，引擎自动尝试联网合成，但请求重试后仍超时。该日志证明本次联网请求失败，不能仅据此判定整台模拟器断网或具体哪个网络环节出了问题。

应用进程中的 `SPAN_EXCLUSIVE_EXCLUSIVE spans cannot have a zero length` 来自 Android 的文本 span 处理。此次 TTS 已收到 `zho-CHN` / `zh-CN-language` 的合成请求，随后在引擎联网合成阶段失败；没有证据表明文本 span 日志导致本次合成失败。

## 应用修正

- `setLanguage()` 成功只说明引擎声明支持该语言，不代表语音数据已经可用。现在还检查实际中文音色，排除带 `notInstalled` 标记的音色。
- 没有可用中文音色时，界面提示安装中文语音包，并禁用朗读和导出。
- 开始任务前重新检查所选音色，避免语音包状态改变后回退到未安装的默认音色。
- 对提供默认音色但不提供完整列表的引擎，仍可使用已就绪的中文默认音色。
- 使用 `ShengJianTTS` 日志标签记录引擎、音色状态和真实回调错误码；不记录用户输入的文字。

## 恢复方法

打开应用中的“语音引擎与语音包” → “安装语音数据”，下载中文（中国大陆）语音数据，并等待安装完成。返回应用时会重新连接引擎、检查音色。

如果 Google TTS 的中文数据始终处于等待下载状态，需先解决该模拟器访问引擎下载服务的问题，或在系统设置中选择另一款已安装且带中文语音数据的引擎。手机联网并不一定表示它能连接到该语音引擎的服务。

在 Android Studio 的 Logcat 中，可以搜索 `ShengJianTTS` 查看应用的就绪检查；排查引擎下载或网络错误时还需查看 `com.google.android.tts` 进程，不能只过滤应用自身的包名。

```sh
adb -s DEVICE_SERIAL logcat -s ShengJianTTS
```
