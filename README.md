# TrafficSign Mobile Benchmark

Repository này chứa Android app và kết quả benchmark cho bài toán nhận dạng biển báo giao thông bằng YOLO11 + LiteRT/TFLite.

## Mục tiêu
Benchmark 4 model trên nhiều thiết bị Android bằng cùng bộ 100 ảnh:
- YOLO11n-320
- YOLO11n-640
- YOLO11s-320
- YOLO11s-640

Các chỉ số Android gồm: detection rate, mean confidence, preprocess latency, inference latency, total latency, estimated FPS, CPU usage, RAM PSS/RSS, model size và device metadata.

`gpu_usage_percent` để trống vì Android không có API công khai đơn giản để đo % sử dụng GPU theo tiến trình; cột `delegate` mới là nơi phân biệt CPU/GPU/NNAPI cho từng dòng.

## Quantization + Delegate sweep

Ngoài baseline 4-model FP32/CPU, app còn có nút **RUN QUANT+DELEGATE SWEEP** để khảo sát:
- Precision: FP32 (gốc) vs FP16 vs INT8 — xem `AI/notebooks/08_quantization_export.ipynb` (export) và `09_quantization_comparison.ipynb` (so sánh accuracy/size trên PC).
- Backend suy luận: CPU 1 luồng, CPU 4 luồng, GPU Delegate, NNAPI (AI accelerator nếu thiết bị hỗ trợ).

Chỉ chạy sweep trên 2 model đại diện (`yolo11n_640`, `yolo11s_640`, mỗi model 3 precision) để giữ thời gian benchmark hợp lý — không lặp lại toàn bộ 320/640 × n/s như bài test baseline. GPU Delegate được bỏ qua với bản INT8 (full-integer) vì GPU delegate không hỗ trợ tốt loại này; nếu thiết bị không hỗ trợ GPU/NNAPI, app tự fallback về CPU và ghi rõ trong cột `delegate` (vd `CPU-1T (GPU fallback)`).

Kết quả lưu 3 CSV riêng, tiền tố `android_quant_sweep_*` (cùng thư mục `Downloads/TrafficSignApp/`, cùng cấu trúc cột với bộ `android_4model_100_*`).

## Cấu trúc chính
```text
TrafficSign-Mobile-Benchmark/
├── AI/
│   ├── benchmark/
│   ├── models/
│   └── notebooks/
├── android/
│   └── TrafficSignApp/
├── Results/
│   └── Samsung_Galaxy_A05s/
├── .gitignore
└── README.md
```

## Chạy benchmark Android
1. Clone repository.
2. Mở Android Studio.
3. Open `android/TrafficSignApp`.
4. Chờ Gradle Sync.
5. Kết nối điện thoại Android.
6. Run app.
7. Nhấn `RUN 4-MODEL TEST`.
8. Chờ benchmark hoàn tất.

App chạy tuần tự n320 → n640 → s320 → s640. Mỗi model load riêng, warm-up, chạy cùng 100 ảnh, ghi metric rồi đóng trước khi sang model kế tiếp.

## Assets bắt buộc
```text
android/TrafficSignApp/app/src/main/assets/
├── labels.txt
├── models/
│   ├── yolo11n_320.tflite
│   ├── yolo11n_640.tflite
│   ├── yolo11s_320.tflite
│   └── yolo11s_640.tflite
└── benchmark_images/
    └── 100 ảnh cố định
```

Không thay đổi bộ 100 ảnh nếu mục tiêu là so sánh công bằng giữa nhiều thiết bị.

## File kết quả
App lưu 3 CSV trong `Downloads/TrafficSignApp/`:
- `android_4model_100_summary_<device>_<timestamp>.csv`
- `android_4model_100_detail_<device>_<timestamp>.csv`
- `android_4model_100_detections_<device>_<timestamp>.csv`

`summary`: 4 dòng, một dòng/model.  
`detail`: khoảng 400 dòng = 4 model × 100 ảnh.  
`detections`: một dòng cho mỗi bounding box.

## Device metadata
Mỗi CSV tự ghi:
- manufacturer
- device_model
- android_version
- sdk_int
- hardware
- cpu_cores

## Kết quả PC
Notebook trong `AI/` chủ yếu dùng để xem lại quy trình và kết quả. Một số notebook có thể chứa đường dẫn cục bộ của máy đã chạy ban đầu.

Accuracy chính thức nên ưu tiên full test set. Bộ 100 ảnh là controlled subset để so sánh nhất quán PC ↔ Android và giữa nhiều thiết bị.

## Gửi kết quả thiết bị mới
Tạo folder:
```text
Results/<Manufacturer>_<DeviceModel>/
```
và đặt 3 CSV của thiết bị vào đó.
