# 原车定位进入独立高德车机：未验收候选

2026-10-26 实机试验未完成 AR 下游验收，已撤回两个应用的接入包。不能把原生 `PosService.setSignInfo` 调用返回当作 AR 已采用定位，更不能据此宣称直角转弯已修复。源码保留供继续调查；不得将此目录作为已上线功能说明。

特会开候选通过已有导航循环、`VehicleLocationObservation` 和 `NavigationLocationFallback` 发送新鲜原车观测。独立高德的签名权限接收器复用 `vr.a(Location)` GNSS 适配器，三秒内新鲜输入优先，失效恢复原平板输入。没有设置系统模拟定位，没有修改 C3。

输入为 GCJ-02，当前 V3 使用与 C3 `map_coordinates.py` 一致的迭代逆变换送入 WGS-84 GNSS 路径；保留原适配器提交时钟。坐标算法许可证见同目录 `COORDTRANSFORM_LICENSE.txt`。转换不代表已验证高德地图匹配和 AR 输出。

补丁基线是高德 `079ca878cc7c8a82107293fc9cfd44eaa29ad8a99f828267ae2c7dd53c165644`。仅在 `vr.smali` 加接收器初始化、平板源选择、提交前准备和提交后日志四处调用，新增 helper DEX，native 库及资源保持。编译用 stub 不打包。

本轮构建、冻结 APK、测试及日志在 `D:\C3\.codex-tmp\amap-vehicle-location-20261026`：`rebuild_helper.py`、`ValidationTest.java`、`manifest.json`、`REPORT.md`、`rollback-final.json`。37 项 helper 边界/坐标检查及现有 21 项定位测试通过；全套 372 项有 3 失败、1 跳过，不能称全绿。

后续必须在真实实时相机、有效定位和 AR 已打开的同一窗口，比较原车输入、提交和 AR Match 输出，并验证失效回退；只看日志 `submitted-native` 或缓存箭头截图不足以验收。
