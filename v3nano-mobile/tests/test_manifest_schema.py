"""Test schema/checksum/probe-load của gói mobile P1 (Sprint 1, kế hoạch 001-mvp).

Tái dùng TOÀN BỘ logic validate từ v3nano-mobile/host/validate_mobile_package.py —
không viết lại bản sao. Package sinh cục bộ (v3nano-mobile/package/pkg) không
nằm trong repo → skip khi chưa build (CI); schema contract luôn được kiểm.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "v3nano-mobile" / "host"))

PKG_DIR = ROOT / "v3nano-mobile" / "package" / "pkg"
HAVE_PKG = (PKG_DIR / "manifest.json").is_file()

have_pkg = pytest.mark.skipif(not HAVE_PKG, reason="chưa build package — chạy v3nano-mobile/host/build_mobile_package.py")


def test_manifest_schema_v1_contract():
    """Schema v1 là hợp đồng đóng băng (03-model-package.md §3.2/§3.6) — mọi field sai
    type là lỗi được MÔ TẢ, không thể sửa âm thầm."""
    if not HAVE_PKG:
        pytest.skip("chưa build package")
    from validate_mobile_package import check_manifest
    errors = check_manifest(PKG_DIR)
    assert errors == [], errors


@have_pkg
def test_manifest_field_values():
    m = json.loads((PKG_DIR / "manifest.json").read_text(encoding="utf-8"))
    assert m["schema_version"] == "1"
    assert m["model_id"] == "v3-nano-mobile"
    assert m["model_revision"] == "0.1.0"
    assert m["base_model_revision"] == "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
    assert m["precision"] == "fp16"
    assert m["sample_rate"] == 24000
    assert m["generation_defaults"] == {"steps": 8, "sway": -1.0, "cfg": 3.0, "max_chars_per_chunk": 140}
    assert "NNAPI" in m["release"]["known_limitations"][0]
    assert "CoreML" in m["release"]["known_limitations"][1]
    for name in ("text_encoder", "duration_predictor", "vector_estimator", "codec_decoder"):
        g = m["graphs"][name]
        assert g["file"] == f"graphs/{name}.onnx"
        assert len(g["sha256"]) == 64


@have_pkg
def test_voice_index_11_voices_default_duc_tri():
    v = json.loads((PKG_DIR / "assets" / "voices" / "voices_v3_nano_mobile.json").read_text(encoding="utf-8"))
    assert v["schema_version"] == "1"
    assert v["default_voice_id"] == "duc_tri"
    assert len(v["voices"]) == 11
    ids = [x["voice_id"] for x in v["voices"]]
    assert len(set(ids)) == 11 and "duc_tri" in ids
    duc_tri = next(x for x in v["voices"] if x["voice_id"] == "duc_tri")
    assert duc_tri["display_name"] == "Đức Trí"
    assert duc_tri["quality_tier"] == 1 and duc_tri["wer_reference"] == 0.0312
    for x in v["voices"]:
        assert x["quality_tier"] in (1, 2, 3)
        assert x["files"]["speaker_emb"] == f"voices/{x['voice_id']}/speaker_emb.bin"
    # minh_quan (giọng cũ WER 19.63%) phải GIỮ trong index nhưng tier 3, không là default
    minh_quan = next(x for x in v["voices"] if x["voice_id"] == "minh_quan")
    assert minh_quan["quality_tier"] == 3


@have_pkg
def test_checksums_all_files():
    from validate_mobile_package import check_checksums
    m = json.loads((PKG_DIR / "manifest.json").read_text(encoding="utf-8"))
    errors = check_checksums(PKG_DIR, m)
    assert errors == [], errors


@have_pkg
def test_probe_load_4_sessions():
    from validate_mobile_package import probe_load
    ok, logs = probe_load(PKG_DIR)
    assert ok, logs


@have_pkg
def test_gzip_size_within_target():
    rep = json.loads((PKG_DIR / "build_report.json").read_text(encoding="utf-8"))
    assert rep["gzip_within_150mb_target"] is True
    assert rep["size_tar_gz_bytes"] <= 150 * 1024 * 1024