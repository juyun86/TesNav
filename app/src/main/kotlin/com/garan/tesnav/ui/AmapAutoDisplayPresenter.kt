package com.garan.tesnav.ui

import com.garan.tesnav.service.AmapAutoBroadcastSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display-only: known fields get labels; unrecognized interfaces and fields remain visible. */
internal object AmapAutoDisplayPresenter {
    private val labels = mapOf(
        "CUR_SPEED" to "车机速度(km/h)", "LIMITED_SPEED" to "道路限速(km/h)",
        "CAR_DIRECTION" to "车头方向(度)", "ROAD_TYPE" to "道路类别原值",
        "CUR_ROAD_NAME" to "当前道路", "NEXT_ROAD_NAME" to "下一道路",
        "NEXT_NEXT_ROAD_NAME" to "再下一道路", "ICON" to "转向图标原值", "NEW_ICON" to "新版图标原值",
        "ROUTE_ALL_DIS" to "路线总长(米)", "ROUTE_REMAIN_DIS" to "剩余距离(米)",
        "ROUTE_REMAIN_DIS_AUTO" to "剩余距离文案", "ROUTE_REMAIN_TIME" to "剩余时间(秒)",
        "ROUTE_REMAIN_TIME_AUTO" to "剩余时间文案", "ETA_TEXT" to "预计到达文案",
        "SEG_REMAIN_DIS" to "下一步距离(米)", "SEG_REMAIN_DIS_AUTO" to "下一步距离文案",
        "SEG_REMAIN_TIME" to "下一步时间(秒)", "CAMERA_TYPE" to "电子眼/道路提醒类型原值",
        "CAMERA_DIST" to "电子眼距离(米)", "CAMERA_SPEED" to "电子眼限速(km/h)", "cameraID" to "电子眼ID",
        "TRAFFIC_LIGHT_NUM" to "红绿灯数量原值", "routeRemainTrafficLightNum" to "剩余红绿灯数量",
        "CAR_LATITUDE" to "车机纬度", "CAR_LONGITUDE" to "车机经度", "CAR_ACCURACY" to "车机定位精度(米)",
        "GPS_ACCURACY" to "GPS精度(米)", "ACCURACY" to "定位精度原值",
        "trafficLightStatus" to "灯色状态原值", "dir" to "信号灯方向原值",
        "redLightCountDownSeconds" to "信号灯倒计时观测值(秒)", "EXTRA_DRIVE_WAY" to "车道级引导原始数据",
        "ROUTE_REMAIN_TIME_STRING" to "剩余时间文案", "NEXT_SEG_REMAIN_DIS_AUTO" to "再下一步距离文案",
        "SAPA_NAME" to "服务区名称", "SAPA_DIST_AUTO" to "服务区距离文案", "NEXT_SAPA_DIST_AUTO" to "下一服务区距离文案",
        "EXIT_DIRECTION_INFO" to "出口方向信息", "EXIT_NAME_INFO" to "出口名称信息",
        "endPOIName" to "目的地名称", "endPOIAddr" to "目的地地址", "DESTINATION_NAME" to "目的地名称",
        "LATITUDE" to "定位纬度原值", "LONGITUDE" to "定位经度原值", "SPEED" to "速度原值", "BEARING" to "方向角原值",
        "TURN_DISTANCE" to "转向距离原值", "TURN_TYPE" to "转向类型原值", "TURN_INSTRUCTION" to "转向指引文案",
        "NEXT_TURN_DISTANCE" to "下一转向距离原值", "NEXT_TURN_TYPE" to "下一转向类型原值",
        "NAVI_STATUS" to "导航状态原值", "ROUTE_DISTANCE" to "路线距离原值", "ROUTE_TIME" to "路线时间原值",
        "ROUTE_TYPE" to "路线类型原值", "ROAD_NAME" to "道路名称", "SPEED_LIMIT_TYPE" to "限速类型原值",
        "TRAFFIC_DESCRIPTION" to "路况描述", "SITUATION_DESCRIPTION" to "导航态势描述",
    )

    fun compact(snapshots: Map<String, AmapAutoBroadcastSnapshot>, nowMs: Long): String {
        val nav = snapshots.values.filter { it.action == "AUTONAVI_STANDARD_BROADCAST_SEND" && it.keyType == 10001 }
            .maxByOrNull { it.observedAtMs } ?: return ""
        if (nowMs - nav.observedAtMs !in 0..3_000) return ""
        val fields = nav.extras
        fun positive(key: String): String? = fields[key]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { fields[key] }
        return listOfNotNull(
            fields["CUR_SPEED"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { "车机速度 ${fields["CUR_SPEED"]} km/h" },
            positive("LIMITED_SPEED")?.let { "限速 $it km/h" },
            positive("CAMERA_DIST")?.let { "前方 $it 米道路提醒（类型 ${fields["CAMERA_TYPE"] ?: "未知"}）" },
            positive("CAMERA_SPEED")?.let { "电子眼限速 $it km/h" },
            fields["NEXT_NEXT_ROAD_NAME"]?.takeIf { it.isNotBlank() && it != "null" }?.let { "再下一道路：$it" },
            fields["SAPA_NAME"]?.takeIf { it.isNotBlank() && it != "null" }?.let { name ->
                listOfNotNull("服务区：$name", fields["SAPA_DIST_AUTO"]?.takeIf { it.isNotBlank() && it != "null" }).joinToString(" ")
            },
            fields["EXIT_NAME_INFO"]?.takeIf { it.isNotBlank() && it != "null" }?.let { "出口：$it" },
            fields["routeRemainTrafficLightNum"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { "剩余红绿灯 $it 个" },
        ).joinToString(" · ")
    }

    fun details(snapshots: Map<String, AmapAutoBroadcastSnapshot>): String {
        if (snapshots.isEmpty()) return "车机广播数据\n尚未收到车机广播"
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        return "车机广播数据（最近观测，不代表仍然有效；长字段缩略）\n" + snapshots.values
            .sortedWith(compareBy<AmapAutoBroadcastSnapshot> { it.keyType ?: Int.MAX_VALUE }.thenBy { it.action })
            .joinToString("\n\n") { snapshot ->
                val type = when (snapshot.keyType) { 10001 -> "导航引导"; 13012 -> "车道级引导"; 60073 -> "红绿灯"; else -> "接口" }
                val direction = when (snapshot.action) {
                    "AUTONAVI_STANDARD_BROADCAST_SEND" -> "车机输出"
                    "AUTONAVI_STANDARD_BROADCAST_RECV" -> "车机输入/请求（不是导航输出）"
                    else -> snapshot.action ?: "未知方向"
                }
                "$type ${snapshot.keyType ?: "未知"} · $direction · 接收 ${time.format(Date(snapshot.observedAtMs))}\n" +
                    snapshot.extras.toSortedMap().entries.joinToString("\n") { (key, value) ->
                        val text = value?.replace("\n", " ")?.replace("\r", " ") ?: "未提供"
                        "${labels[key]?.let { "$it [$key]" } ?: key}：${text.take(1_024)}" +
                            if (text.length > 1_024) "…（长字段仅显示前1024字符）" else ""
                    }
            }
    }
}
