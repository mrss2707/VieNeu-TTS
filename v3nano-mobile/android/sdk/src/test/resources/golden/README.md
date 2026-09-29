# Golden fixtures for JVM unit tests

Bản sao TỰ ĐỘNG của v3nano-mobile/eval/golden/*.json (sinh bởi
v3nano-mobile/host/export_golden_contract.py) + tokenizer.json từ package P1
(v3nano-mobile/package/pkg — sinh bởi v3nano-mobile/host/build_mobile_package.py).

Nguồn chân lý (source of truth) là exporter; khi golden đổi, chạy lại exporter
rồi copy lại vào đây. Không sửa tay các file JSON trong thư mục này.
