# Cài công cụ Android trên máy phát triển (Windows)

Máy hiện tại: Windows 11 Home, 16 GB RAM, chưa có JDK, Android Studio, SDK
hay `adb`. JDK 1.8 đi kèm Oracle XE (`C:\app\...\jdk`) quá cũ cho Android
Gradle Plugin: không dùng, không đưa vào `JAVA_HOME`.

## Phân việc

| Bạn làm (cần quyền admin, giao diện hoặc chấp nhận license) | Claude làm |
|---|---|
| Bật Windows Hypervisor Platform, khởi động lại máy | Chuẩn bị các lệnh đặt biến môi trường để bạn duyệt |
| Cài Android Studio, chấp nhận license SDK | Chuyển Gradle wrapper và version catalog từ project mẫu vào `AppProduct/`, xoá thư mục tạm sau khi bạn đồng ý |
| Cài gói trong SDK Manager, tạo AVD | Chạy `fetch_artifacts.py`, `gradlew.bat test / assemble… / connected…AndroidTest` |
| Tạo project mẫu | Chạy emulator nền với host audio, `adb install/push/pull`, các validator |
| Duyệt lệnh đặt biến môi trường; bật quyền micro cho ứng dụng desktop | Chỉ tạo nhánh hoặc commit khi bạn yêu cầu |
| Việc cần người thật: nói thử mic, nghe đánh giá giọng TTS và lexicon, thu WAV giọng vùng miền và tiếng ồn, gửi `design_check.md` cho anh, mượn máy thật cho Phase 05 | |

## Dung lượng và RAM

- **Ổ C:** khoảng 20–25 GB (Android Studio, SDK, system image, AVD, cache Gradle).
- **Ổ D:** khoảng 4–6 GB (build output, `.cache/`, model trong assets).
- Lúc kiểm tra (2026-09-30) máy còn trống khoảng 81 GB ở C: và 29 GB ở D:.
- **RAM:** emulator dùng 4 GB, Android Studio và Gradle dùng thêm vài GB.
  Gradle giới hạn heap `-Xmx3g` (đặt trong `gradle.properties` ở M0). Khi chạy
  emulator, đóng bớt ứng dụng khác (trình duyệt nhiều tab). Có thể chạy emulator
  bằng dòng lệnh mà không mở Android Studio.

## Cách đang dùng: công cụ dòng lệnh, không cần Android Studio

Ngày 2026-10-01, người dùng chọn để Claude cài công cụ build bằng dòng lệnh và
đồng ý điều khoản Android SDK License. Đã cài (ngoài repo, dùng lại được nếu sau
này cài Android Studio):

| Thành phần | Phiên bản | Vị trí |
|---|---|---|
| JDK (Eclipse Temurin, sha256 kiểm theo Adoptium) | 21.0.12.1+1 | `%LOCALAPPDATA%\Programs\Temurin\jdk-21.0.12.1+1` |
| Android SDK cmdline-tools (sha1 kiểm theo repository của Google) | 23.0 | `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest` |
| Platform-Tools (`adb`) | 37.0.1 | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |
| Platform | android-37.0 | `%LOCALAPPDATA%\Android\Sdk\platforms` |
| Build-Tools | 37.0.0 (+ 36.0.0 do AGP tự cài) | `%LOCALAPPDATA%\Android\Sdk\build-tools` |
| Gradle | 9.8.0 qua wrapper (`gradle/wrapper`, có `distributionSha256Sum`) | `%USERPROFILE%\.gradle` |

Không đặt biến môi trường hệ thống; mỗi phiên PowerShell đặt tạm:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Temurin\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

Lưu ý khi dùng `sdkmanager.bat`: tên gói có `;` phải đặt trong nháy kép lồng
(`'"platforms;android-37.0"'`), vì file `.bat` coi `;` là dấu tách tham số.

Máy dev có Windows Application Control (Smart App Control): nó chặn DLL chưa ký
bị giải nén vào `%TEMP%` (đã gặp với Conscrypt của Robolectric, xem DR-01).

Các bước bên dưới (Android Studio, emulator) chỉ cần nếu bạn muốn tự mở và sửa
code trong IDE, hoặc muốn chạy emulator.

## Máy thật hay emulator?

Nên dùng **máy thật** làm máy test chính: số liệu độ trễ và RAM đúng thực tế,
micro thật, và test Airplane mode của Phase 05 bắt buộc làm trên máy thật. Kết
quả trên emulator chỉ dùng để kiểm tra chức năng.

| Nếu dùng | Làm các bước |
|---|---|
| Chỉ máy thật | 2, 3 (bỏ system image x86_64), 5, 6, 9 |
| Chỉ emulator | 1–8 |
| Cả hai | Tất cả |

Bỏ emulator thì tiết kiệm khoảng 5–8 GB ổ C và vài GB RAM khi chạy. Thử giao
diện nhiều cỡ màn hình vẫn làm được trên máy thật bằng `adb shell wm size` /
`wm density`.

## Bước 1. Bật ảo hoá cho emulator (bạn, chỉ khi dùng emulator)

Emulator trên Windows dùng WHPX (Windows Hypervisor Platform).

1. Kiểm tra CPU đã bật ảo hoá: Task Manager → Performance → CPU →
   "Virtualization: Enabled". Nếu "Disabled", bật Intel VT-x trong BIOS.
2. Mở PowerShell bằng quyền Administrator, chạy:

   ```powershell
   Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All
   ```

3. Khởi động lại máy.

## Bước 2. Cài Android Studio (bạn)

1. Tải bản ổn định mới nhất từ <https://developer.android.com/studio>, cài vào
   vị trí mặc định (`C:\Program Files\Android\Android Studio`).
2. Lần chạy đầu chọn **Standard**. SDK nằm ở `%LOCALAPPDATA%\Android\Sdk`.
3. Chấp nhận các license SDK khi được hỏi.
4. Không cài JDK riêng: Android Studio có sẵn JBR (JetBrains Runtime, JDK 21)
   tại `C:\Program Files\Android\Android Studio\jbr`.

## Bước 3. Cài gói SDK (bạn)

Android Studio → Tools → SDK Manager, bật "Show Package Details" ở cả hai tab:

| Tab | Gói |
|---|---|
| SDK Platforms | Platform mặc định mà Android Studio đề xuất |
| SDK Platforms | Android 15 (API 35) → Google APIs Intel x86_64 Atom System Image (`system-images;android-35;google_apis;x86_64`) |
| SDK Tools | Android SDK Build-Tools ≥ 35 (cần cho `zipalign -P 16`), Android SDK Platform-Tools, Android Emulator |

Không cần NDK.

## Bước 4. Tạo AVD `VTT_API35_4GB` (bạn)

Device Manager → Create Virtual Device:

1. Phone → **Medium Phone**.
2. System image: API 35, Google APIs, x86_64 (vừa cài ở bước 3).
3. Tên AVD: `VTT_API35_4GB`.
4. Phần cài đặt nâng cao: RAM **4096 MB**, Internal storage **8 GB**.

## Bước 5. Tạo project mẫu (bạn, rồi Claude)

File → New → New Project → **Empty Activity**:

| Trường | Giá trị |
|---|---|
| Name | `VietTechTrans` |
| Package name | `vn.viettechtrans.app` |
| Save location | `D:\DATN_Dat\_vtt_template` |
| Minimum SDK | API 26 |
| Build configuration language | Kotlin DSL (`build.gradle.kts`) |

Chờ Gradle sync xong (lần đầu tải Gradle và Android Gradle Plugin), rồi báo
Claude. Claude chuyển `gradle/wrapper/`, `gradlew`, `gradlew.bat` và
`gradle/libs.versions.toml` sang `AppProduct/`; phiên bản compile/target SDK,
AGP, Kotlin và Compose BOM lấy theo template này. `_vtt_template` là thư mục
tạm, không commit, xoá sau khi bạn đồng ý.

## Bước 6. Biến môi trường (Claude chuẩn bị, bạn duyệt)

Chạy trong PowerShell thường (không cần admin), kiểm tra đường dẫn JBR trước:

```powershell
setx ANDROID_HOME "$env:LOCALAPPDATA\Android\Sdk"
setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
[Environment]::SetEnvironmentVariable("Path", "$userPath;$sdk\platform-tools;$sdk\emulator", "User")
```

PATH không đặt bằng `setx PATH "%PATH%;..."`: lệnh đó chép cả PATH hệ thống vào
PATH người dùng và cắt chuỗi ở 1024 ký tự. Biến mới chỉ có hiệu lực trong
terminal mở sau khi chạy lệnh.

## Bước 7. Quyền micro của Windows (bạn)

Settings → Privacy & security → Microphone: bật **Microphone access** và
**Let desktop apps access your microphone**. Emulator là ứng dụng desktop nên
cần quyền này để nhận âm thanh từ micro máy tính.

## Bước 8. Chạy emulator với micro máy tính

```bat
emulator -avd VTT_API35_4GB -allow-host-audio
```

Host audio bị tắt lại sau mỗi lần khởi động emulator. Nếu chạy emulator không
có cờ trên, bật trong Extended controls (nút "…") → Microphone → "Virtual
microphone uses host audio input".

## Bước 9. Kết nối máy thật (bạn)

Yêu cầu máy: Android 8.0 (API 26) trở lên, 64-bit (arm64-v8a); còn trống khoảng
1 GB (APK khoảng 290 MB). Máy mục tiêu của đồ án có khoảng 4 GB RAM.

1. Bật chế độ nhà phát triển: Cài đặt → Thông tin điện thoại → chạm 7 lần vào
   **Số hiệu bản dựng** (Build number). Tên mục có thể khác theo hãng.
2. Cài đặt → Tùy chọn nhà phát triển → bật **Gỡ lỗi USB** (USB debugging).
   Xiaomi/Redmi: bật thêm **Cài đặt qua USB** (Install via USB) và **Gỡ lỗi USB
   (Cài đặt bảo mật)**.
3. Kết nối, chọn một trong hai cách:

   | Cách | Thao tác | Ghi chú |
   |---|---|---|
   | Cáp USB (nên dùng) | Cắm cáp, chọn chế độ truyền tệp, bấm "Cho phép gỡ lỗi USB" trên điện thoại | Samsung cần Samsung USB Driver; Pixel dùng Google USB Driver (SDK Manager → SDK Tools). Dùng được cả khi bật Airplane mode |
   | Không dây (Android 11+) | Tùy chọn nhà phát triển → **Gỡ lỗi không dây** → "Ghép nối bằng mã" → `adb pair <ip>:<port>` rồi `adb connect <ip>:<port>` | Máy tính và điện thoại cùng Wi-Fi; không dùng được khi test Airplane mode |

4. Kiểm tra: `adb devices` phải có một dòng với trạng thái `device` (không phải
   `unauthorized`).

Claude đọc tên máy, RAM, chip, phiên bản Android qua `adb shell getprop` và ghi
vào evidence; không cần gửi thông tin máy.

## Kiểm tra

Mở terminal mới:

| Lệnh | Kết quả đúng |
|---|---|
| `& "$env:JAVA_HOME\bin\java.exe" -version` | JBR, phiên bản 21 (tối thiểu 17 cho AGP) |
| `adb version` | In phiên bản Android Debug Bridge |
| `emulator -accel-check` | Có dòng `WHPX ... is installed and usable` |
| `emulator -list-avds` | Có `VTT_API35_4GB` |
| `adb devices` (khi emulator đang chạy) | Có dòng `emulator-5554` với trạng thái `device` |
| `adb devices` (khi cắm máy thật) | Có dòng `<serial>` với trạng thái `device` |

Dùng máy thật: chỉ cần dòng `java`, `adb version` và `adb devices` (máy thật).
Dùng emulator: cần năm dòng đầu. Khi đủ, báo Claude để bắt đầu M0.
