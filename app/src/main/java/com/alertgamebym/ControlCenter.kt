package com.alertgamebym

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ControlCenter {
    private val _bubbleRunning = MutableStateFlow(false)
    val bubbleRunning = _bubbleRunning.asStateFlow()

    private val _targetX = MutableStateFlow(-1f)
    val targetX = _targetX.asStateFlow()

    private val _targetY = MutableStateFlow(-1f)
    val targetY = _targetY.asStateFlow()

    // Item ROI: sol-ust (x1,y1) ve sag-alt (x2,y2) kose koordinatlari
    private val _itemRoiX1 = MutableStateFlow(0f)
    val itemRoiX1 = _itemRoiX1.asStateFlow()

    private val _itemRoiY1 = MutableStateFlow(0f)
    val itemRoiY1 = _itemRoiY1.asStateFlow()

    private val _itemRoiX2 = MutableStateFlow(0f)
    val itemRoiX2 = _itemRoiX2.asStateFlow()

    private val _itemRoiY2 = MutableStateFlow(0f)
    val itemRoiY2 = _itemRoiY2.asStateFlow()

    fun setBubbleRunning(v: Boolean) { _bubbleRunning.value = v }

    fun setTarget(x: Float, y: Float) {
        _targetX.value = x
        _targetY.value = y
    }

    fun setItemRoi(x1: Float, y1: Float, x2: Float, y2: Float) {
        _itemRoiX1.value = x1; _itemRoiY1.value = y1
        _itemRoiX2.value = x2; _itemRoiY2.value = y2
    }
}
