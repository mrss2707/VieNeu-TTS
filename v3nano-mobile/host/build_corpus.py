"""Xây v3nano-mobile/eval/corpus/ — corpus MVP cho validation protocol 002 (§3).

Không phải corpus production-scale (300-1000 câu regression, 5/15/30 phút
audiobook thật theo §3.1E) — quy mô nhỏ có chủ đích để đủ chạy validation
không-cần-device-thật trong hợp lý thời gian. Xem manifest.json cho ghi chú
giới hạn quy mô.

Nội dung câu trong smoke/stress/audiobook_short là văn bản tự viết cho mục
đích test (trừ phần lấy từ examples/audio_ref, assets/samples, examples/srt
đã có sẵn trong repo và được ghi rõ nguồn trong manifest).

Chạy: uv run --no-sync python v3nano-mobile/host/build_corpus.py
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS_DIR = ROOT / "v3nano-mobile" / "eval" / "corpus"


# ─────────────────────────── smoke.jsonl ───────────────────────────
# Câu ngắn/vừa/dài, đủ 4 loại dấu câu, 1-2 đoạn kể chuyện ngắn — §3.1.A/B.
_SMOKE_NEW = [
    {"id": "s01", "category": "cau_ngan", "text": "Xin chào."},
    {"id": "s02", "category": "cau_ngan", "text": "Trời hôm nay đẹp quá."},
    {"id": "s03", "category": "cau_hoi", "text": "Bạn đã ăn cơm chưa?"},
    {"id": "s04", "category": "cau_cam_than", "text": "Ôi, thật tuyệt vời!"},
    {"id": "s05", "category": "cau_phay", "text": "Sau khi ăn xong, chúng tôi đi dạo một vòng quanh hồ."},
    {
        "id": "s06", "category": "cau_dai",
        "text": "Buổi sáng ở làng chài yên tĩnh, những chiếc thuyền nhỏ lặng lẽ rời bến, "
                "mang theo hy vọng về một ngày đánh bắt bội thu cho cả gia đình.",
    },
    {
        "id": "s07", "category": "doan_ke_chuyen",
        "text": "Ngày xưa, ở một ngôi làng nhỏ ven sông, có một cụ già sống một mình trong "
                "căn nhà tranh cũ kỹ. Mỗi buổi chiều, cụ thường ngồi trước hiên nhà, ngắm "
                "hoàng hôn buông xuống mặt nước và kể chuyện cho lũ trẻ trong xóm nghe.",
    },
    {
        "id": "s08", "category": "doan_hoi_thoai",
        "text": '"Anh có chắc là đường này đúng không?", cô hỏi. "Chắc chắn rồi", anh đáp, '
                '"mình đã đi qua đây nhiều lần."',
    },
    {"id": "s09", "category": "cau_ngan", "text": "Được rồi."},
    {"id": "s10", "category": "cau_hoi", "text": "Sao lại như vậy?"},
]

# 4 câu tái sử dụng từ examples/audio_ref/ (đã có sẵn trong repo).
_AUDIO_REF_FILES = [
    "examples/audio_ref/example.txt",
    "examples/audio_ref/example_2.txt",
    "examples/audio_ref/example_3.txt",
    "examples/audio_ref/example_4.txt",
]

# 6 câu tái sử dụng từ src/vieneu/assets/samples/ (đã có sẵn trong repo).
_SAMPLE_VOICE_DIR = ROOT / "src" / "vieneu" / "assets" / "samples"


def _load_audio_ref_sentences() -> list[dict]:
    out = []
    for i, rel in enumerate(_AUDIO_REF_FILES, start=1):
        text = (ROOT / rel).read_text(encoding="utf-8").strip()
        out.append({"id": f"ref{i:02d}", "category": "audio_ref_reused", "text": text, "source": rel})
    return out


def _load_sample_voice_sentences() -> list[dict]:
    out = []
    for i, path in enumerate(sorted(_SAMPLE_VOICE_DIR.glob("*.txt")), start=1):
        text = path.read_text(encoding="utf-8").strip()
        out.append({
            "id": f"voice{i:02d}", "category": "preset_voice_caption_reused",
            "text": text, "source": str(path.relative_to(ROOT)),
        })
    return out


# ─────────────────────────── stress.jsonl ───────────────────────────
# Số, ngày tháng, tiền tệ, phần trăm, tên riêng/địa danh, Hán Việt, code-switching,
# robustness (rỗng/whitespace/dấu lặp/dài) — §3.1.C/D. Lấy cảm hứng từ ví dụ số/ngày
# tháng thật trong tests/test_utils.py (không copy nguyên văn).
_STRESS = [
    {"id": "st01", "category": "so_dem", "text": "Tôi có ba trăm hai mươi lăm nghìn đồng trong ví."},
    {"id": "st02", "category": "ngay_thang", "text": "Cuộc họp sẽ diễn ra vào ngày mười lăm tháng ba năm hai nghìn không trăm hai mươi sáu."},
    {"id": "st03", "category": "tien_te", "text": "Sản phẩm này giá một triệu hai trăm nghìn đồng, giảm hai mươi phần trăm so với tuần trước."},
    {"id": "st04", "category": "phan_tram", "text": "Tỷ lệ tăng trưởng đạt bảy phẩy năm phần trăm trong quý vừa qua."},
    {"id": "st05", "category": "ten_rieng_dia_danh", "text": "Nguyễn Thị Minh Khai sinh ra tại Vinh, Nghệ An, sau đó chuyển đến Sài Gòn."},
    {"id": "st06", "category": "han_viet", "text": "Sự kiện này mang tính lịch sử và có ý nghĩa vô cùng trọng đại đối với quốc gia."},
    {"id": "st07", "category": "code_switching", "text": "Chúng tôi vừa deploy xong bản update mới lên production server."},
    {"id": "st08", "category": "code_switching", "text": "Anh ấy nói rằng meeting hôm nay sẽ bắt đầu lúc chín giờ sáng."},
    {"id": "st09", "category": "vien_tat", "text": "Ông làm việc tại UBND TP.HCM từ năm hai nghìn lẻ chín."},
    {"id": "st10", "category": "so_thap_phan_gio", "text": "Cuộc họp bắt đầu lúc tám giờ ba mươi sáng, giá vé là ba phẩy năm triệu đồng."},
    {"id": "st11", "category": "trich_dan_long", "text": 'Cô hỏi: "Anh có chắc là, kiểu như \\"đến giờ chưa?\\", đúng không?"'},
    {"id": "st12", "category": "dau_cau_lap", "text": "Thật à... Không thể nào?! Chuyện gì đã xảy ra vậy???"},
    {"id": "st13", "category": "robustness_rong", "text": ""},
    {"id": "st14", "category": "robustness_whitespace", "text": "   "},
    {"id": "st15", "category": "robustness_cau_cuc_ngan", "text": "À."},
    {"id": "st16", "category": "robustness_emoji", "text": "Hôm nay vui quá 😄🎉, mình rất thích buổi tiệc này!"},
    {
        "id": "st17", "category": "robustness_cau_dai_vuot_chunk",
        "text": (
            "Trong suốt hơn ba mươi năm qua, kể từ khi thành phố bắt đầu công cuộc đổi mới "
            "và mở cửa nền kinh tế, đời sống của người dân đã có những thay đổi to lớn, từ "
            "cách họ làm việc, sinh hoạt hàng ngày, cho đến cách họ tiếp cận với công nghệ "
            "thông tin, giáo dục, y tế, và các dịch vụ công cộng khác mà trước đây từng là "
            "điều xa xỉ đối với phần lớn các gia đình lao động bình thường trong khu vực này."
        ),
    },
    {"id": "st18", "category": "oov_tu_hiem", "text": "Bức tranh sơn mài ấy được vẽ theo lối truyền thần rất công phu."},
    {"id": "st19", "category": "ten_rieng_nuoc_ngoai", "text": "Albert Einstein và Isaac Newton đều là những nhà khoa học vĩ đại."},
    {"id": "st20", "category": "url_email", "text": "Vui lòng liên hệ qua email support@example.com hoặc truy cập trang web để biết thêm chi tiết."},
]


# ─────────────────────────── audiobook_short.jsonl ───────────────────────────
_SRT_PATH = ROOT / "examples" / "srt" / "sample_vi.srt"

_AUDIOBOOK_EXTRA = [
    "Câu chuyện về những công trình cổ đại này vẫn còn khiến các nhà khảo cổ học "
    "ngày nay phải kinh ngạc, bởi lẽ với công nghệ thời bấy giờ, việc xây dựng những "
    "cấu trúc đồ sộ như vậy đòi hỏi một tổ chức lao động vô cùng quy mô và kỷ luật.",
    '"Làm sao người xưa có thể tính toán chính xác đến vậy?", một nhà nghiên cứu trẻ '
    'thốt lên khi lần đầu đứng trước công trình. "Đó vẫn là một bí ẩn chưa có lời giải '
    'đáp đầy đủ", vị giáo sư hướng dẫn ông trả lời, ánh mắt đầy suy tư.',
]


def _srt_to_paragraph(srt_path: Path) -> str:
    """Ghép các dòng thoại trong .srt thành 1 đoạn văn liên tục, bỏ index/timestamp."""
    raw = srt_path.read_text(encoding="utf-8")
    blocks = re.split(r"\n\s*\n", raw.strip())
    lines: list[str] = []
    for block in blocks:
        block_lines = [ln for ln in block.splitlines() if ln.strip()]
        if not block_lines:
            continue
        # bỏ dòng số thứ tự (dòng đầu, chỉ toàn số) và dòng timestamp (chứa "-->")
        text_lines = [
            ln for ln in block_lines
            if not ln.strip().isdigit() and "-->" not in ln
        ]
        if text_lines:
            lines.append(" ".join(text_lines))
    return " ".join(lines)


def _estimate_seconds_from_srt(srt_path: Path) -> float:
    raw = srt_path.read_text(encoding="utf-8")
    timestamps = re.findall(r"(\d{2}):(\d{2}):(\d{2}),(\d{3})\s*-->", raw)
    if not timestamps:
        return 0.0
    h, m, s, ms = (int(x) for x in timestamps[-1])
    return h * 3600 + m * 60 + s + ms / 1000.0


def build() -> dict:
    CORPUS_DIR.mkdir(parents=True, exist_ok=True)

    smoke_entries = _SMOKE_NEW + _load_audio_ref_sentences() + _load_sample_voice_sentences()
    smoke_path = CORPUS_DIR / "smoke.jsonl"
    with smoke_path.open("w", encoding="utf-8") as f:
        for e in smoke_entries:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")

    stress_path = CORPUS_DIR / "stress.jsonl"
    with stress_path.open("w", encoding="utf-8") as f:
        for e in _STRESS:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")

    srt_paragraph = _srt_to_paragraph(_SRT_PATH)
    srt_seconds_estimate = _estimate_seconds_from_srt(_SRT_PATH)
    audiobook_text = srt_paragraph + " " + " ".join(_AUDIOBOOK_EXTRA)
    audiobook_entry = {
        "id": "audiobook_short_01",
        "category": "audiobook_short",
        "text": audiobook_text,
        "char_count": len(audiobook_text),
        "source_srt": "examples/srt/sample_vi.srt",
        "source_srt_estimated_seconds": srt_seconds_estimate,
        "note": (
            "Không đạt yêu cầu 5/15/30 phút của protocol 002 §3.1.E — chỉ đủ cho "
            "boundary/continuity smoke, không phải audiobook chính thức."
        ),
    }
    audiobook_path = CORPUS_DIR / "audiobook_short.jsonl"
    with audiobook_path.open("w", encoding="utf-8") as f:
        f.write(json.dumps(audiobook_entry, ensure_ascii=False) + "\n")

    voices_json = json.loads(
        (ROOT / "src" / "vieneu" / "assets" / "voices_v3_nano.json").read_text(encoding="utf-8")
    )
    presets = voices_json["presets"]
    # Chọn 5 voice đa dạng gender/character theo mô tả có sẵn trong voices_v3_nano.json — §3.3.
    chosen = {
        "Minh Quân": "default_voice_giong_doc_chuan",
        "Ái Hân": "phong_cach_tin_tuc_nu",
        "Mỹ Duyên": "phong_cach_doc_truyen_nu",
        "Đức Trí": "giong_nam_khac_biet",
        "Xuân Tiên": "kiem_tra_speaker_consistency",
    }
    voice_matrix = {
        "note": (
            "5/11 preset voice có sẵn trong src/vieneu/assets/voices_v3_nano.json, chọn theo "
            "protocol 002 §3.3 (đa dạng gender/character, 1 voice tự nhiên, 1 khác biệt, "
            "1 dùng kiểm tra speaker consistency)."
        ),
        "voices": [
            {
                "voice_id": name,
                "purpose": purpose,
                "description": presets[name].get("description", ""),
                "gender": presets[name].get("gender", ""),
            }
            for name, purpose in chosen.items()
        ],
    }
    voice_matrix_path = CORPUS_DIR / "voice_matrix.json"
    voice_matrix_path.write_text(json.dumps(voice_matrix, ensure_ascii=False, indent=2), encoding="utf-8")

    def sha256_of(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    manifest = {
        "created": "2026-09-28",
        "author": "validation session (Claude Code), nội dung tự viết cho mục đích test",
        "license": "Nội dung mới viết (smoke/stress/audiobook_extra): tương đương CC0, chỉ dùng nội bộ "
                   "cho validation. Phần tái sử dụng (audio_ref/*, assets/samples/*, examples/srt/*) "
                   "giữ nguyên license của repo gốc (xem LICENSE ở root).",
        "scale_note": (
            "Corpus MVP cho vòng validation không-cần-device (protocol 002 §3). KHÔNG đạt quy mô "
            "production protocol 002 đề xuất (regression 300-1000 câu, audiobook 5/15/30 phút thật, "
            "corpus riêng cho calibration/quantization). Đủ dùng để: (a) đo RTF median/p95/stddev "
            "trên >1 câu, (b) xác nhận không NaN/Inf trên edge case cơ bản, (c) có voice matrix tối "
            "thiểu. Không đủ để mở bất kỳ quality/quantization gate nào."
        ),
        "files": {},
    }
    for name in ["smoke.jsonl", "stress.jsonl", "audiobook_short.jsonl", "voice_matrix.json"]:
        p = CORPUS_DIR / name
        manifest["files"][name] = {
            "sha256": sha256_of(p),
            "size_bytes": p.stat().st_size,
        }
    manifest["counts"] = {
        "smoke": len(smoke_entries),
        "stress": len(_STRESS),
        "audiobook_short": 1,
        "audiobook_short_char_count": len(audiobook_text),
        "audiobook_short_estimated_seconds_from_srt_source": srt_seconds_estimate,
        "voice_matrix": len(chosen),
    }
    manifest_path = CORPUS_DIR / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    return manifest


if __name__ == "__main__":
    m = build()
    print(json.dumps(m, ensure_ascii=False, indent=2))
