package com.vieneu.mobile.ort

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * Bốn session ONNX của v3 Nano — port nguyên `NanoBenchmarkCore.Graphs`
 * (android/device-validation). Giữ đúng chữ ký `(env, so, base)` để harness
 * delegate mà không phải đổi call-site.
 *
 * `base(name)` trả ĐƯỜNG DẪN FILE — luôn dùng `createSession(path)`, KHÔNG `byte[]`
 * (graph vector_estimator ~155MB OOM heap Dalvik).
 *
 * `open` để `NanoBenchmarkCore` giữ được lớp con `Graphs` tương thích ngược.
 */
open class OrtGraphs(
    env: OrtEnvironment,
    so: OrtSession.SessionOptions,
    base: (String) -> String,
) {
    val text = env.createSession(base("nano_graphs/text_encoder.onnx"), so)
    val dur = env.createSession(base("nano_graphs/duration_predictor.onnx"), so)
    val ve = env.createSession(base("nano_graphs/vector_estimator.onnx"), so)
    val dec = env.createSession(base("nano_graphs/codec_decoder.onnx"), so)

    fun close() {
        text.close(); dur.close(); ve.close(); dec.close()
    }
}
