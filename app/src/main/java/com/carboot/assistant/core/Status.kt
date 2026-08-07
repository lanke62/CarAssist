package com.carboot.assistant.core

/**
 * 全局运行态快照，UI 直接读，服务负责写。
 */
object Status {

    enum class Level { IDLE, OK, WARN, FAIL }

    data class Item(
        var level: Level = Level.IDLE,
        var detail: String = "等待中"
    )

    val bluetooth = Item()
    val wifi = Item()
    val volume = Item()
    val killAmap = Item()
    val launch = Item()

    /** 每个蓝牙适配器独立状态，Key 为适配器 index */
    val btItems = mutableMapOf<Int, Item>()

    @Volatile var serviceRunning = false
    @Volatile var loopCount = 0L
    @Volatile var startupDone = false
    @Volatile var lastTickAt = 0L

    fun set(item: Item, level: Level, detail: String) {
        item.level = level
        item.detail = detail
    }
}
