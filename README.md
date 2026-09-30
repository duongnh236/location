# GPS MHN

App Android độc lập gồm các tiện ích phục vụ chơi **Monster Hunter Now** (và game dùng GPS khác):
giả lập GPS, joystick ảo, bản đồ ghim vị trí, route tự đi, OCR đọc chỉ số màn hình và máy tính IV.

Code được **viết lại** từ việc phân tích app AnyTo (`com.imyfone.anytoandroid`) — không phải bản dịch
ngược nguyên khối, mà cài lại logic bằng Java thuần cho một project Android Studio gọn nhẹ.

## Yêu cầu build

- JDK 17
- Android SDK 35 (compileSdk 35, minSdk 26)
- Android Studio hoặc Gradle Wrapper đi kèm

## Build

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Tính năng (4 tab)

1. **📍 VỊ TRÍ** — giả lập GPS (`LocationManager` test provider), teleport theo toạ độ, chọn tốc độ
   (đi bộ/chạy/xe đạp/xe máy/ô tô/tuỳ chỉnh), joystick trong app, joystick **nổi trên app khác**,
   route đi tuần tự nhiều điểm có lặp, và **kết nối MHN custom (AIDL)**.
2. **🗺 BẢN ĐỒ** — bản đồ OpenStreetMap **full màn hình**, các nút điều khiển **nổi ở đáy** (đè lên bản
   đồ), lấy **vị trí GPS thật của máy** làm mốc ban đầu (chấm xanh lá).
   **Chạm bản đồ chỉ để CHỌN điểm**; bấm **➤ ĐI TỚI** mới tìm đường (routing theo phố, OSRM) → vẽ đường
   (cam) → đi theo đúng đường đó; **⚡ TELEPORT** nhảy tức thời; **■ DỪNG** dừng đi theo đường.
   Có **3 chế độ tốc độ: đi bộ / xe đạp / ô tô**, lưu địa điểm (📋 DANH SÁCH), thêm điểm vào route.
3. **🔍 OCR** — chụp màn hình (MediaProjection) + MLKit đọc chữ, tách `CP`/`HP`/mức bụi rồi điền
   sẵn vào máy tính IV.
4. **🧮 IV** — tra CP/HP/IV theo mức bụi hoặc theo cấp, kèm xếp hạng PVP Little/Great/Ultra/Master.

## Quyền cần cấp

- **Vị trí** (`ACCESS_FINE_LOCATION`) — để lấy GPS thật và tạo provider mô phỏng.
- **Ứng dụng vị trí mô phỏng**: Cài đặt → Tùy chọn nhà phát triển → *Chọn ứng dụng vị trí mô phỏng* → GPS MHN.
- **Hiển thị trên ứng dụng khác** (`SYSTEM_ALERT_WINDOW`) cho joystick nổi.
- **Chụp màn hình** (hỏi khi bật OCR) và **Thông báo**.

## Chạy nền

Nút **▶ BẬT CHẠY NỀN** (tab VỊ TRÍ) bật `SimulationService` — một foreground service kiểu
`specialUse` (cần quyền `FOREGROUND_SERVICE_SPECIAL_USE`), giữ tiến trình sống để vị trí ảo / binder
MHN / route **tiếp tục khi fen chuyển sang MHN custom**. Có thông báo "GPS MHN đang chạy nền".
Service **tự bật** khi fen bật giả lập GPS, di chuyển, dùng joystick hoặc kết nối MHN. Bấm lại để tắt.

Vì `MhnCustomBinder` nghe vị trí trực tiếp từ engine (không qua Activity), toạ độ vẫn được đẩy sang
MHN **kể cả khi app đã ra nền**.

Bấm **🔗 KẾT NỐI MHN CUSTOM** sẽ tự **mở app MHN custom** (để nó nạp plugin rồi nhận vị trí).

## Cấu trúc

```
app/src/main/java/com/gpsmhn/tools/
  FakeGpsEngine.java        giả lập GPS + teleport + joystick + route
  JoystickView.java         joystick trong app
  LocationOverlayService.java  joystick nổi (foreground service)
  MhnCustomBinder.java      bind AIDL tới bản MHN đã patch (giống AnyTo)
  MhnUpdater.java           check update + tải APK MHN custom
  SimulationService.java    foreground service giữ app chạy nền
  RouteFinder.java          tìm đường theo phố (OSRM) để vẽ + đi theo
  OsmMapView.java           bản đồ OSM + marker + route
  PlaceStore.java           lưu địa điểm/route
  ScreenOcrService.java     chụp màn hình + OCR (MLKit)
  PokemonData.java          dữ liệu Pokémon
  IvCalculator.java         công thức IV/CP/PVP
  ToolsActivity.java        màn hình chính 4 tab
app/src/main/assets/tools/pokemon/   dữ liệu Pokémon
```

## Ghi chú

- Bản đồ dùng **OpenStreetMap** thay Mapbox (Mapbox cần token trả phí).
- Dữ liệu base stat giữ nguyên như AnyTo: `poke_list.txt` là bảng cũ, ~410 loài được
  `basestats_11_14_18.txt` ghi đè. Muốn chính xác hơn thì cập nhật file đó.
- Công thức: `CP = max(10, floor(cpm² · √(sta+ivS) · (√(def+ivD)·defMul) · (atk+ivA)·atkMul) / 10)`,
  Shadow `atkMul=1.2, defMul=0.833`.

### Kết nối MHN custom (AIDL) — đẩy toạ độ qua binder

Nút **🔗 KẾT NỐI MHN CUSTOM** ở tab VỊ TRÍ bind tới service do bản MHN đã patch expose ra:

| | |
|---|---|
| package | `com.nianticlabs.monsterhunter` |
| service | `com.imyfone.main.LocationServiceMHN` (exported, không permission) |
| action | `com.imyfone.main.action_mhn` |
| interface | `com.imyfone.main.LocaltionInterfaceMHN` |

AIDL (lấy từ `monsterhunterCustom.apk`):

| transaction | method | tác dụng |
|---|---|---|
| 1 | `void changeLocation(double lat, double lng)` | server gọi `NianticLabs.saveLocation` → reflect vào `plugin.apk` (`LocationUtil.save`) để ghi đè vị trí trong game |
| 2 | `String getVersion()` | bản này trả `"Not yet implemented"` |
| 3 | `void sendKeepLive()` | nhịp giữ service sống |

`MhnCustomBinder` giữ `IBinder` + `linkToDeath`, tự bind lại mỗi 5 giây, gửi `sendKeepLive` mỗi 2 giây,
và khi tick **Đẩy toạ độ vào game qua binder** bật thì mỗi lần vị trí giả lập đổi sẽ gọi
`changeLocation(lat,lng)` (gộp lệnh tối thiểu 150ms/lần). Nhờ vậy có thể ghi đè vị trí **không cần
mock location**.

Lưu ý: `plugin.apk` chỉ được nạp khi app MHN custom đã chạy (`MagellanUnityPlayerActivity.onCreate`
→ `NianticLabs.install`). Phải **mở MHN custom ít nhất 1 lần** trước thì `changeLocation` mới có tác dụng;
nếu chưa, lệnh vẫn gửi được nhưng game chưa nhận. Máy chỉ có MHN gốc sẽ bind thất bại và app báo rõ.

### Check update / tải MHN custom

Nút **⬇ CHECK UPDATE MHN** (tab VỊ TRÍ). Nguồn lấy từ source AnyTo (`InstallGameActivity`):

| | |
|---|---|
| APK MHN | `https://download.pogoace.com/files/location/monsterhunterCustom.apk` |
| APK PG | `https://download.pogoace.com/files/location/pokemongoCustom.apk` |
| APK DQ | `https://download.pogoace.com/files/location/dragonquestCustom.apk` |
| API version | `GET https://apipdm.pogoace.com/game/version?sign=<MD5>&timestamp=<ts>&type=3` |

`sign = MD5("timestamp=<ts>&type=<type>&key=PDM637d875cd89a9").toUpperCase()`; `type`: 1 = PG,
2 = DQ, 3 = MHN. Server trả `{"code":1,"data":{"version":"128.0"}}`.

Hành vi nút:
- **Chưa cài** MHN custom → mời tải bản mới nhất.
- **Đã cài & server mới hơn** (so kiểu `128.0` vs `128`) → mời tải bản mới.
- **Đã là bản mới nhất** → báo không cần tải.
- Tải bằng `DownloadManager` vào thư mục riêng của app, xong tự mở màn hình cài đặt APK
  (cần quyền `REQUEST_INSTALL_PACKAGES`, Android sẽ hỏi "cho phép cài từ nguồn này").
