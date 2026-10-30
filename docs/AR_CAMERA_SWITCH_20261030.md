# C3 高德镜头切换实现

基于此前上传的 `juyun86/TesNav` / `amap-lane-engine-20261013` 当前提交 `53a98ff` 开发。未覆盖共享开发目录，也未合入其他对话的 JPEG 回退改动。

## 本次行为

- 原生 H264 发送端按新鲜 C3 车速和 experimentalMode 选择镜头：低于 10 m/s 广角，高于 15 m/s 窄角，中间保持；关闭 experimentalMode 使用窄角。不改变 C3 自身的相机或驾驶控制。
- 同一 HTTP 流内停止旧编码 worker，发送新镜头的 SPS/PPS 与帧；只停止本服务创建的 worker。使用此前实测过的 NEON 广角裁剪缩放实现。
- 解码器将镜头身份纳入配置变化，切换时丢弃旧排队帧，即使两路都是 1280×720 或 SPS/PPS 相同。
- 接收端将广角裁剪内参换算为输出像素内参。镜头变化立即写入相机参数，跳过周期刷新限频；暂停向高德交付，发送 12112 / exitAR=1，收到对应成功回复后发送 exitAR=0，再等待真实相机打开回调。
- 镜头打开回调只说明相机会话重建，不是 native 配置已消费或铺路正确的证明。该协议退出/重入在当前 7.1.5 上的完整行为仍需实测。

## 验证及当前限制

特会开构建成功。ArCameraCalibrationTest 5 项和 ArH264PacketTest 6 项通过；Python 3 项通过，覆盖选择滞回、过期速度，以及真实发送函数在一个模拟 HTTP 会话内依次发送广/窄/广配置与帧、释放旧 worker。模拟编码包不代表实机高德验收。

本轮读到 C3 health 为 started=false、ready=false、cameradRunning=false；平板没有运行 C3ArCameraService。按用户要求不再恢复环境。本轮没有安装、启动相机、修改车控或部署自动恢复。

远程源快照含此前未验收的原车定位发送候选；全量 APK 仅用于构建验证，不能作为当前已恢复版本的等价安装包。现场试验需要仅移植本次相机类并配套发送端，保留当前其他行为。

tools/c3_ar_source.py 同步了本地已验证 H264/相机标定实现后加入选择器。tools/ar_h264_stream.py 与 tools/ar_h264_worker_neon.cc 为配套发送端；设备的 h264-prototype/ar_h264_worker 需使用该 NEON 实现，不能拿旧只裁剪编码器发送广角缩放参数。

## 后续安装与平板实测

用户要求继续后，以已恢复特会开 APK 36843003 为基线，仅替换四组相机类及其内部/合成类，其他 DEX、资源和 Manifest 保留。相机专用 APK `665c6d876212f6840b4626384e9839cc3a619cc58dc0552644e1e300df65ff60` 已保留数据安装；并非上文全量构建 APK。高德安装包未改。

平板实际 OMX.hisi.video.decoder.avc 回放此前保存的三段真实相机录像：2 次切换，186 帧输入，178 帧解码，8 帧在切换点由旧队列主动丢弃；逐输入时间戳核对后无未归属输出，3 帧独立共享内存写入比对通过。旧探针要求所有输入输出相等，首次因此报告 178/186 失败；新探针明确统计切换时尚在队列中的输入，非忽略任意丢帧。该测试不连接高德，不证明 AR 重读或道路贴合。两个临时测试包已卸载。

C3 已安装配套 c3_ar_source.py、ar_h264_stream.py 及此前验证的 NEON worker，仅重载独立 tenengkai-ar-source 用户服务，服务 active。备份 `/data/tenengkai-ar-source/backups/camera-switch-20261030-061848`；安装证据位于主工作区 `.codex-tmp/ar-auto-camera-switch-20261029/camera-switch-source-installation.json`，相机包范围见 camera-reinit-manifest.json。

收尾 C3 started=false、ready=false、cameradRunning=false，不能取得实时高德退出/重入及内参重读结果。未恢复环境、强制启动相机、重启 manager、修改车控或部署 JPEG 自动恢复。真实速度跨阈值、高德重新读取内参、广角姿态/铺路仍未验收。
