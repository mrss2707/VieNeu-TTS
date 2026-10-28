"""Golden cho front-end G2P on-device: text -> (chunks, gaps, phones) bằng đúng pipeline Python.

Output: v3nano-mobile/android/sdk-g2p/src/test/resources/g2p_golden.json
Chạy:   .venv/bin/python v3nano-mobile/host/export_g2p_golden.py
Kotlin (SeaG2pParityTest) phải cho ra đúng chuỗi này từ lõi Rust qua JNI + TextSegmenter.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

from vieneu_utils.phonemize_text import (  # noqa: E402
    normalize_to_chunks_v3_with_gaps,
    phonemize_text_with_emotions,
)

MAX_CHARS = 140
OUT = ROOT / "v3nano-mobile/android/sdk-g2p/src/test/resources/g2p_golden.json"

EXTRA = [
    # đoạn demo cũ (nguyên nhân "nuốt chữ")
    "Năm mười lăm tuổi, Tiêu Viêm từng là thiên tài trẻ nhất của gia tộc Tiêu. Thế nhưng, chỉ sau một đêm, "
    "đấu khí trong cơ thể hắn đột nhiên biến mất không dấu vết. Bốn năm sau, thiếu niên năm nào đứng giữa "
    "quảng trường, mặc cho ánh mắt chế giễu của tộc nhân, hắn siết chặt nắm đấm — nhất định phải lấy lại tất cả những gì đã mất.",
    # nhiều đoạn (newline → gap "para")
    "Chương một: Khởi đầu.\n\nTrời hôm nay đẹp quá. Chúng tôi đi dạo quanh hồ Gươm rồi ghé quán cà phê quen thuộc.\n\nHết.",
    # số, ngày, tiền, đơn vị, viết tắt, URL/email, từ mượn
    "Ngày 12/03/2024 anh ấy trả 1.500.000đ cho 3kg gạo, tăng 15% so với năm ngoái.",
    "Hôm nay tôi dùng Facebook và email để gửi deadline cho team marketing. Liên hệ support@vieneu.ai hoặc https://vieneu.ai/docs nhé.",
    # trích dẫn / ngoặc
    "Anh ấy hỏi: \"Em đã ăn cơm chưa?\" Cô gái cười đáp (khá ngượng ngùng): \"Em ăn rồi ạ!\"",
    # emotion cue
    "Xin chào mọi người [cười] hôm nay mình kể một câu chuyện rất vui. [thở dài] Nhưng trước hết, nghỉ chút đã.",
    "[hắng giọng] Thưa quý vị và các bạn.",
    "Thế à... thật không? Không thể tin được!!! Sao lại thế?!",
    # câu rất dài một đoạn, buộc cắt theo dấu phẩy / balanced-cut
    ("Ông ngồi bên hiên nhà nhìn ra cánh đồng lúa chín vàng trải dài đến tận chân trời không một bóng người " * 3).strip() + ".",
    "",
    "   ",
]


def corpus_lines() -> list[str]:
    out = []
    for name in ("smoke", "stress", "audiobook_short"):
        for line in (ROOT / "v3nano-mobile/eval/corpus" / f"{name}.jsonl").read_text(encoding="utf-8").splitlines():
            if line.strip():
                out.append(json.loads(line)["text"])
    return out


def main() -> None:
    cases = []
    for text in corpus_lines() + EXTRA:
        chunks, gaps = normalize_to_chunks_v3_with_gaps(text, max_chars=MAX_CHARS)
        phones = [phonemize_text_with_emotions(c) if c.strip() else "" for c in chunks]
        cases.append({"text": text, "chunks": chunks, "gaps": gaps, "phones": phones})
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps({"max_chars": MAX_CHARS, "source": "vieneu_utils.phonemize_text (sea-g2p)", "cases": cases},
                   ensure_ascii=False, indent=1),
        encoding="utf-8",
    )
    n_chunks = sum(len(c["chunks"]) for c in cases)
    print(f"{len(cases)} cases, {n_chunks} chunks -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
