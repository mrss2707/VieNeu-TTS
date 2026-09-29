package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp

/** FlowScheduler vs golden sway_schedule.json — dung sai 1e-6 (07-kiem-thu.md §7.4). */
class FlowSchedulerTest {

    private fun golden(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResource("golden/sway_schedule.json")).readText()
        ).jsonObject

    @Test
    fun schedulesMatchGoldenForSteps8_12_16() {
        val schedules = golden()["schedules"]!!.jsonArray
        for (el in schedules) {
            val s = el.jsonObject
            val steps = s["steps"]!!.jsonPrimitive.content.toInt()
            val sway = s["sway"]!!.jsonPrimitive.content.toDouble()
            val expectedTg = s["tg"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()

            val sch = FlowScheduler.schedule(steps, sway)
            assertEquals(steps, sch.steps)
            assertEquals(steps + 1, sch.tg.size)
            for (i in expectedTg.indices) {
                assertEquals("steps=$steps sway=$sway tg[$i]", expectedTg[i], sch.tg[i], 1e-6)
            }

            // t và dt đều từ lịch trình warp (float32) — khớp golden t/dt
            val expectedT = s["t"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
            val expectedDt = s["dt"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
            for (i in 0 until steps) {
                assertEquals("t[$i]", expectedT[i], sch.t[i].toDouble(), 1e-6)
                assertEquals("dt[$i]", expectedDt[i], sch.dt[i].toDouble(), 1e-6)
            }
        }
    }

    @Test
    fun durationCapAndMinFrames() {
        // secs = min(exp(log_s)/speed, 15.0) — cap 15s kể cả log_s cực lớn
        assertEquals(15.0, FlowScheduler.durationSeconds(100.0), 1e-12)
        assertEquals(15.0, FlowScheduler.durationSeconds(10.0), 1e-12)
        // speed rất nhỏ không gây chia 0 (max(speed, 1e-3))
        assertEquals(15.0, FlowScheduler.durationSeconds(0.0, speed = 0.0), 1e-12)
        val exact = FlowScheduler.durationSeconds(1.0)
        assertEquals(exp(1.0), exact, 1e-12)
        // T = max(2, round(secs*fps)); round-half-up (Math.round) giữ hành vi harness
        assertEquals(2, FlowScheduler.frames(0.0, 15.625))
        assertEquals(32, FlowScheduler.frames(2.0, 16.0))
        assertEquals(31, FlowScheduler.frames(2.0, 15.625))  // 31.25 -> round(31.25)=31
    }

    @Test
    fun scheduleEndpoints() {
        // u(0)=0 -> tg(0)=0; u(n)=1 -> tg(n)=1 với MỌI sway (công thức warp nội suy hai đầu)
        for (sway in doubleArrayOf(-1.0, 0.0, 1.0, 2.5)) {
            val sch = FlowScheduler.schedule(8, sway)
            assertEquals(0.0, sch.tg[0], 1e-12)
            assertEquals(1.0, sch.tg[8], 1e-12)
            // tg tăng nghiêm ngặt với sway trong khoảng sản phẩm {-1, 0} (đơn điệu của warp)
            if (sway == -1.0 || sway == 0.0) {
                for (i in 0 until 8) {
                    org.junit.Assert.assertTrue("sway=$sway i=$i", sch.tg[i + 1] > sch.tg[i])
                }
            }
        }
    }
}