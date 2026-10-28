# Gói model cho example

Đặt gói P1 vào thư mục này (nội dung bị `.gitignore` chặn — 135 MB+):

```bash
cp -R v3nano-mobile/package/pkg/. \
      v3nano-mobile/flutter/packages/vieneu_tts/example/assets/pkg/
```

`manifest.json` + `checksums.txt` + `graphs/` + `assets/` là đủ để plugin install/load.
