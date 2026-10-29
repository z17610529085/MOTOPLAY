# MOTOPLAY 🏍️
把闲置安卓手机变成摩托车行车记录仪。
![Build](https://github.com/z17610529085/MOTOPLAY/actions/workflows/build-apk.yml/badge.svg)
## 功能特性
| 功能 | 说明 |
| --- | --- |
| 🔒 后台录像 | 前台服务（Foreground Service），锁屏 / 切后台持续录制 |
| 📹 双摄并发 | CameraX ConcurrentCamera，不支持时自动降级单后置（不崩溃） |
| ✂️ 自动分段 | 默认每 5 分钟切一个文件，防断电丢数据 |
| 🔄 循环录像 | 存储上限默认 100GB，超限自动删除最旧未锁定文件 |
| 📌 一键锁定 | 当前视频移入 `Locked` 目录，不被自动清理 |
| 📍 GPS 水印 | 画面叠加实时速度与经纬度 |
| 🎨 分屏 UI | 强制横屏、全屏、常亮；左侧时间，右侧摄像头 + 天气 + 功能条 |
| 🖼️ 自定义壁纸 | 全局壁纸置于所有窗口下方，带暗化遮罩保证可读性 |
| 🎬 视频管理 | 列表播放 / 分享 / 删除 |
| 🚀 开机自启 | BOOT_COMPLETED 广播（受系统限制时静默降级） |
## 快速安装
1. 打开 [Releases](../../releases) 页面
2. 下载最新 `MOTOPLAY-release.apk`
3. 手机安装（需允许未知来源）
4. 首次启动授予：相机、麦克风、定位、通知
## 本地构建
```bash
# 需要 JDK 17 + Android SDK 35
./gradlew :app:assembleDebug
```
产物：`app/build/outputs/apk/debug/app-debug.apk`
## 自动构建
推送到 `main` 分支即触发 GitHub Actions：
- 自动编译 Debug + Release APK
- 上传到 Actions Artifacts
- 自动创建 Release 并附 APK 下载
## 权限说明
| 权限 | 用途 |
| --- | --- |
| CAMERA | 录像 |
| RECORD_AUDIO | 录音（默认开启） |
| ACCESS_FINE_LOCATION | GPS 水印 |
| POST_NOTIFICATIONS | Android 13+ 前台服务通知 |
| FOREGROUND_SERVICE_CAMERA / MICROPHONE | Android 14+ 前台服务类型 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
## Android 14/15/16 适配要点
1. `foregroundServiceType="camera|microphone|location"` + 对应 `FOREGROUND_SERVICE_*` 权限
2. Android 13+ 动态申请 `POST_NOTIFICATIONS`
3. `FLAG_KEEP_SCREEN_ON` 保持屏幕常亮
4. 双摄 API 全程 try-catch，失败立即降级单摄
5. 录像通过 MediaStore 同步到系统相册（Android 10+）
## 目录结构
```
app/src/main/java/com/motoplay/dashcam/
├── MotoPlayApp.kt          应用入口 / 通知渠道
├── data/
│   ├── GpsTracker.kt       GPS 采集
│   ├── StorageManager.kt   分段存储 / 循环清理 / 相册同步
│   └── WeatherRepository.kt 天气数据
├── receiver/BootReceiver.kt 开机自启
├── service/RecordingService.kt 前台服务 + CameraX + 双摄降级
├── ui/
│   ├── MainActivity.kt     主界面（分屏）
│   ├── MainViewModel.kt    时钟 / 天气
│   ├── VideoListActivity.kt 视频列表
│   └── VideoPlayerActivity.kt 播放器
└── util/Prefs.kt           配置存储
```
## 许可证
MIT