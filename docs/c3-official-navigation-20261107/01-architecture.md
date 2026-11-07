# 官方新导航模型与现有链路

## 官方依据

- 2026-10-12 合入的 [commaai/openpilot #39066 nav alpha](https://github.com/commaai/openpilot/pull/39066) 是测试导航模型的 alpha 基础；其中 Chestnut 模型回放和回归通过不等于实车转弯成功。
- 本项目历史固定参考：[RL 导航驾驶模型提交 a03bb1d4](https://github.com/commaai/openpilot/commit/a03bb1d4e80050cae0007780ecb8eb7cf06d0cb9)。研究用 ONNX 文件 SHA-256 为 `1d18c42ea4cd95c8bffab4635844b78b8e2d4e25b9de44a9c5ac2e0961371a2e`，与该参考的 LFS 指针一致；历史研究文件哈希不是现在设备加载版本的证明。
- 该参考模型的 `nav_map` 为 FLOAT `[1,1,256,256]`，`navigation` 为 FLOAT `[1,47]`；另外有图像、desire、交通方向、action 和递归状态。47 维的前 46 项是动作槽，最后一项为距离米数除以 100。模型输入类型与形状须以实际加载文件为准。
- [sunnypilot #2057](https://github.com/sunnypilot/sunnypilot/pull/2057) 是导航模型适配草稿；其中提醒按模型输入的 dtype 计算缓冲区布局，不说明 C3 存在该错误。

## 两条路线与模型输入

```text
TesNav Android 高德 SDK → 导航快照 + 完整路线及步骤（候选）
                                  ↓ UDP 分片 / 验证 / 接收确认（候选）
C3 tesnav → 路线选择、定位匹配、动作 → 地图渲染 + 47 维向量
          → navigation.model.v1 → nav_model / AMD GPU → 模型轨迹与灯光预测
          → 现有横纵向控制、变道/转向意图和灯光链 → 车辆反馈
```

2026-11-06 当时手机旧版本只导出 points，车端要求完整上传路线含 steps 才使用；条件不满足时车端以目的地独立调用 MineData 规划。手机与车端因此可能走不同路线。手机完整路线分片和车端接收是隔离候选，不应据此宣称已上车。路线传输需核对所有者、会话、版本、时序、步骤和确认；ACK 是接收反馈，并非证明模型消费或车辆动作。

历史 C3 渲染快照使用 256×256 地图，1 米/像素，车位于 (128,192)，道路和路线分别为灰度 255、128；地图与向量一起通过带有效期的数据包交给模型。实际相机、地图、坐标系统、时间戳、步进、模型 dtype 和输出解析都必须对齐。Chipmunk 手机模型源文件公开哈希与本次 C3 参考 ONNX 哈希不同；输入维数相似不证明权重或行为等价。

导航指令、模型 turn/lane-change desire、模型灯光预测、车端灯光请求和 Panda/车辆实际反馈是不同环节。历史普通 C3 用 Tesla `0x3E9` 相关链路；不要把未经证实的 `0x249` 方案归到该车型。单次灯光输出不证明路线、转弯或关灯闭环通过。
