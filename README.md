# RawCam

Aplikasi kamera Android (Camera2 API) dengan kontrol manual penuh.

## Video
- Mode: Default (bawaan HP) · RAW · SOOC Plain · SOOC Custom
- RAW: output video HEVC kecil (minim olahan) atau urutan DNG (RAW asli, sangat besar)
- SOOC Plain / RAW: kurva "Plain HDR" (highlight roll-off halus, bayangan terangkat) + bias AE -1 EV
- SOOC Custom: editor kurva layar penuh, kontras, saturasi, sharpness, tonal, muat/simpan/impor/ekspor (.json, .cube)
- Open gate, pilihan resolusi/fps, OIS on/off, eksposur Auto/Manual, WB + tint

## Foto
- JPEG / RAW / RAW+JPEG, open gate atau 16:9
- Warna JPEG: bawaan / Plain / kustom (kontras, saturasi, kurva, sharpness)
- Eksposur Auto/Manual (ISO, shutter, aperture, fokus), WB + tint

## Build
GitHub Actions (tab Actions -> Build APK) atau Android Studio (Build -> Build APK). Butuh Android 10+.
