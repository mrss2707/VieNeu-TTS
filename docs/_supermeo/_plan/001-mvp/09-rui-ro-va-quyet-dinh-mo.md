# 09 — Rủi ro & các quyết định còn mở

## 9.1. Bảng rủi ro (kèm hành động cụ thể + ai sở hữu)

| # | Rủi ro | Mức | Dấu hiệu sớm | Giảm thiểu (hành động cụ thể) | Nơi thực thi |
|---|---|---|---|---|---|
| R1 | FP16 trên device không đạt như host (RAM/RTF/quality) | Cao | Spike S1 fail | Mixed-precision FP16/FP32 theo graph; INT8 weight-only cho text/duration; quay lại D4 trước khi đóng băng gói | Giai đoạn 0 |
| R2 | Tier B không đạt RTF ≤0.5 | Cao | Spike S3 RTF >0.5 | Mở lại H10 (distill/train mobile-specific); không hạ steps <8 khi chưa có quality gate | Giai đoạn 0 + sau MVP |
| R3 | Thermal vẫn nghiêm trọng khi đọc lâu | Cao | Soak 30 phút CRITICAL | Tầng 3 mạnh hơn (thread=1, nghỉ giữa chunk dài, prefetch tối thiểu); nếu không đủ → xem lại candidate steps/quant hạng nhẹ | Sprint 4 |
| R4 | CoreML/NNAPI re-test vẫn fail sau graph fix | Trung bình | Device test provider | Không chặn: CPU/XNNPACK là default đã chốt; ghi limitation | S6 |
| R5 | Port engine lệch Python (quality regression thầm lặng) | Cao | L1 port-compare lệch tolerance | 40-case golden + ASR pipeline + listening; mọi port phải qua `V3NanoVieNeuTTS.infer()` comparison | Mỗi sprint |
| R6 | Drift tokenizer/G2P khi sync main | Cao | H11 diff | Thủ tục sync §8.6 + contract test + snapshot asset SHA | Mỗi lần sync |
| R7 | Bug chunking tái xuất (nuốt chữ câu dài) | Cao | `st17` ≠ ~21.0s | Regression test `st17`/`s07` cố định trong 3 project test; không ai được gọi engine trực tiếp cho text >140 ký tự | L1/L2/L3 |
| R8 | RAM vượt budget tier B/C (peak >400MB) | Cao | L3 RAM report | Buffer reuse + static shape + FP16 (đã có đường); NNAPI không bật mặc định (tiết kiệm ~250MB) | S3–S4 |
| R9 | OEM Android giết app khi background (OplusHans...) | Trung bình | Device test freeze | Foreground service + sample app UI foreground; tài liệu integrator rõ ràng | S4 |
| R10 | Voice asset/checksum hỏng trên máy user | Trung bình | Beta report | Chu trình §3.4 atomic install + fallback bundled voice + rollback | S5 |
| R11 | License/attribution không đủ để phân phối | Cao | Legal chưa duyệt | Inventory §3.5 hoàn tất trước beta | S5 |
| R12 | Cache/disk tràn low-end | Trung bình | Beta low-storage | LRU + quota + `E_DISK_FULL`; tự dọn temp | S7 |
| R13 | Giọng mặc định kém (bài học Minh Quân 19.6%) | Trung bình | Beta phàn nàn | D8: mặc định "Đức Trí"; kèm UI chọn giọng; quality tier hiển thị (không bán giọng kém làm mặc định) | Gđ0 |

## 9.2. Các quyết định còn mở (từ §12 summary) — chủ sở hữu + thời hạn

| # | Câu hỏi | Đề xuất (để ra quyết định nhanh) | Thời hạn |
|---|---|---|---|
| Q1 | Device thấp nhất hỗ trợ? | Trả lời bằng Spike S3 (Tier B thật) — đề xuất: Android 8+ ARM64 / iOS 15+, 3GB RAM trở lên; ghi thành `min_sdk` + device floor docs | Giai đoạn 0 |
| Q2 | Package bundled hay download sau cài? | **5–8 voice tier 1–2 bundled** (khoảng vài MB asset/voice — conditioning nhỏ, model tách riêng); model đồng hành app qua Play/App Store size limit (check 200MB AAB → dùng Play Feature Delivery nếu vượt); voice còn lại download | Giai đoạn 0 + S8 |
| Q3 | Voice cloning bản đầu? | **Không** (khuyến nghị giữ) | đã theo D5 |
| Q4 | 24 kHz? | **Có** (khuyến nghị giữ) | đã chốt |
| Q5 | Quality gate: listening, ASR hay cả hai? | **Cả hai** — ASR là gate tự động, listening là gate cuối; không dùng riêng ASR | S6 |
| Q6, Q7 | Provider | Đã chốt (D2/D3) | xong |
| Q8 | Telemetry opt-in? | **Có** nhưng default OFF, metadata-only (§8.5) | S7 |
| Q9 | Registry ở đâu/ai ký? | §8.2 — HF repo riêng + MLOps key | S5 |
| Q10 | Merge policy generic fix lên main? | Theo §7.3 summary: bug fix generic có test → PR lên main qua review; mobile-specific giữ ở develop | Liên tục |
| Q11 | Verify-first hay MVP-first? | Đã chốt: MVP-first (§0.13) | xong |

## 9.3. Quy trình xử lý khi gate fail (đã cam kết ở chiến lược)

1. Ghi rõ **gate nào, evidence gì, branch/revision nào** vào report (không im lặng sửa qua).
2. Quay lại **quyết định tương ứng** (§0.13: Nếu spike fail → quay lại trước khi đóng băng public API/package format).
3. Nếu fail lặp lần 3 → dừng retry, báo cáo chẩn đoán đầy đủ + xin quyết định người dùng (không tự vòng lặp vô hạn).
4. Sprint bị block vì thiếu thiết bị → chuyển sang việc không phụ thuộc, ghi `NOT RUN`+ngày. Không bịa số.

## 9.4. Điều khoản hạ cánh an toàn (nếu mọi thứ xấu hơn dự kiến)

- **Kịch bản A (FP16/RAM không đạt):** phát hành FP32 preset-only chậm hơn nhưng đúng + roadmap INT8 rõ — vẫn có sản phẩm.
- **Kịch bản B (Tier B không đạt RTF):** giới hạn device floor lên Tier A-first, công bố rõ; mở training ladder H10.
- **Kịch bản C (thermal không kiểm soát):** chế độ "đọc có nghỉ" mặc định (eco mode) — chấp nhận giảm trải nghiệm nhưng không hư máy.
- Nguyên tắc chung: **tệ hơn về quality ≠ chấp nhận được vì nhanh hơn** (điều cấm của protocol 002 vẫn giữ nguyên).