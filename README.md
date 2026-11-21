# AnimeVietsub PipePipe Plugin

Plugin xem anime Vietsub từ **AnimeVietsub** dành cho ứng dụng [PipePipe](https://github.com/InfinityLoop1308/PipePipe) (v5.6.0+), được port từ extension CloudStream (`Animevietsub.cs3`).

## Cách cài đặt vào PipePipe

1. Mở ứng dụng **PipePipe** → vào **Settings (Cài đặt)** → **Plugins**.
2. Bật (Enable) tính năng **Plugins**.
3. Nhấn **Add repository (Thêm kho lưu trữ)** và dán đường dẫn dưới đây vào:

```text
https://raw.githubusercontent.com/0xTOHO/AnimeVietsubPipePipePlugin/main/index.json
```

4. Trong danh sách **Available (Có sẵn)**, chọn **AnimeVietsub** và nhấn **Install (Cài đặt)**.
5. **Khởi động lại PipePipe** để kích hoạt dịch vụ **AnimeVietsub**.

## Tính năng

- **Tự động cập nhật tên miền:** Tự động phân giải `https://bit.ly/animevietsubtv` sang domain mới nhất của AnimeVietsub và vượt qua trang chuyển hướng JS (`window.location.href`).
- **3 Danh mục Trang chủ (Kiosks):**
  - *Mới Cập Nhật* (`/anime-moi/`)
  - *Xem Nhiều Trong Ngày* (`/bang-xep-hang/day.html`)
  - *Anime Sắp Chiếu* (`/anime-sap-chieu/`)
- **Tìm kiếm nhanh & đầy đủ:** Tích hợp API gợi ý nhanh `/ajax/suggest` và trang tìm kiếm `/tim-kiem/`.
- **Danh sách tập & Phát trực tiếp:** Hiển thị đầy đủ danh sách tập trong trang Kênh (Channel) và mục Video liên quan (Related Items), hỗ trợ giải mã luồng HLS CDN (`googleapiscdn.com`).

## Tự biên dịch từ mã nguồn

```bash
./gradlew :tools:genKey      # Tạo khóa ký signing.key (chỉ chạy 1 lần)
./gradlew :tools:index       # Biên dịch gói .ppp và tạo index.json trong build/repo/
```
