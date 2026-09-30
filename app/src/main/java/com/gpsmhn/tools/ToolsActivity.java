package com.gpsmhn.tools;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * TAB TIỆN ÍCH — gom các tính năng chuyển từ AnyTo vào 1 màn riêng, mở từ màn hình chính.
 *
 * <p>Gồm 4 phần: giả lập GPS + joystick + route, bản đồ OSM có ghim vị trí, OCR đọc màn hình
 * (MLKit) và máy tính IV/CP/PVP.
 */
public class ToolsActivity extends Activity implements FakeGpsEngine.Listener {

    private static final int BG = Color.rgb(8, 17, 31), CARD = Color.rgb(17, 29, 48);
    private static final int GOLD = Color.rgb(213, 168, 78), BLUE = Color.rgb(74, 163, 255);

    private static final int REQ_PERMS = 100, REQ_PROJECTION = 101, REQ_OVERLAY = 102;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FakeGpsEngine engine;
    private PlaceStore places;
    private PokemonData pokemon;
    private IvCalculator iv;

    private FrameLayout host;
    private View pageLocation, pageMap, pageOcr, pageIv;
    private final Button[] navButtons = new Button[4];
    private int currentPage = 0;

    // MHN custom binder
    private MhnCustomBinder mhn;
    private TextView mhnStatus;
    private Button mhnButton;
    private CheckBox mhnPush;
    private BroadcastReceiver downloadReceiver;
    private Button bgButton;

    // Location page
    private TextView locStatus;
    private Button mockButton;
    private Spinner speedSpinner;
    private EditText latInput, lngInput, speedInput;
    private TextView routeInfo;
    private CheckBox routeLoop;
    private JoystickView joystick;

    // Map page
    private OsmMapView mapView;
    private TextView mapInfo;
    private LinearLayout placeList;
    private double selectedLat, selectedLng;
    private boolean hasSelection;
    private int moveMode = 0;                                  // 0 đi bộ, 1 xe đạp, 2 ô tô
    private final Button[] moveModeButtons = new Button[3];
    private final List<double[]> plannedPath = new ArrayList<>();   // đường tìm được (OSRM) để vẽ + đi theo
    private boolean routeFetching = false;
    private boolean autoMoveOnTap = true;
    private CheckBox autoMoveCheck;
    private LocationManager locationManager;
    private double realLat, realLng;
    private boolean hasReal, realApplied;

    // OCR page
    private TextView ocrResult;
    private TextView ocrStatus;
    private Button ocrCapture, ocrStart;

    // IV page
    private EditText ivName, ivCp, ivHp, ivDust, ivLevel;
    private CheckBox ivShadow;
    private TextView ivResult;
    private Spinner ivSpeciesSpinner;
    private final List<PokemonData.Species> speciesList = new ArrayList<>();
    private PokemonData.Species selectedSpecies;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = FakeGpsEngine.get(this);
        places = new PlaceStore(this);
        mhn = new MhnCustomBinder(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        setContentView(buildUi());
        mhn.setListener((connected, message) -> updateMhnUi(connected, message));
        registerDownloadReceiver();
        selectMoveMode(0);
        requestRuntimePermissions();
        loadPokemonAsync();
    }

    @Override protected void onResume() {
        super.onResume();
        engine.addListener(this);
        requestRealLocation();
        if (!FakeGpsEngine.hasMockPermission(this) && mockButton != null) {
            locStatus.setText("⚠ Chưa bật \"ứng dụng vị trí mô phỏng\". Vào Tùy chọn nhà phát triển → chọn aTSBot.\n"
                    + locStatus.getText().toString().split("\n")[0]);
        }
        onPosition(engine.lat(), engine.lng(), engine.bearing(), engine.speed());
        refreshMapMarkers();
        refreshPlaces();
        updateBgButton();
    }

    @Override protected void onPause() {
        engine.removeListener(this);
        try { if (locationManager != null) locationManager.removeUpdates(realListener); } catch (Exception ignored) { }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (mhn != null) mhn.stop();
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) { }
            downloadReceiver = null;
        }
        super.onDestroy();
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    MhnUpdater.handleDownloadComplete(ToolsActivity.this, id);
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    // ================================================================== UI shell

    private View buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(8));
        TextView title = text("📍  GPS MHN", 22, GOLD);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("✕ THOÁT", Color.rgb(38, 50, 68), Color.WHITE);
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(120), dp(48)));
        root.addView(header);

        host = new FrameLayout(this);
        pageLocation = buildLocationPage();
        pageMap = buildMapPage();
        pageOcr = buildOcrPage();
        pageIv = buildIvPage();
        host.addView(pageLocation, full());
        host.addView(pageMap, full());
        host.addView(pageOcr, full());
        host.addView(pageIv, full());
        root.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildNav(), new LinearLayout.LayoutParams(-1, -2));

        showPage(0);
        return root;
    }

    private View buildNav() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(5, 12, 22));
        LinearLayout nav = row();
        nav.setPadding(dp(6), dp(7), dp(6), dp(16));
        String[] labels = {"📍 VỊ TRÍ", "🗺 BẢN ĐỒ", "🔍 OCR", "🧮 IV"};
        for (int i = 0; i < labels.length; i++) {
            final int page = i;
            navButtons[i] = button(labels[i], Color.rgb(25, 35, 49), Color.WHITE);
            navButtons[i].setOnClickListener(v -> showPage(page));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(58), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(navButtons[i], lp);
        }
        scroll.addView(nav);
        return scroll;
    }

    private void showPage(int page) {
        currentPage = page;
        pageLocation.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        pageMap.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        pageOcr.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        pageIv.setVisibility(page == 3 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navButtons.length; i++) {
            navButtons[i].setBackgroundTintList(ColorStateList.valueOf(i == page ? GOLD : Color.rgb(25, 35, 49)));
            navButtons[i].setTextColor(i == page ? Color.rgb(20, 20, 20) : Color.WHITE);
        }
        if (page == 1) {
            mapView.setCurrent(engine.lat(), engine.lng());
            refreshMapMarkers();
        }
    }

    // ================================================================== location page

    private View buildLocationPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(14), dp(8), dp(14), dp(20));
        scroll.addView(root);

        root.addView(section("GIẢ LẬP GPS"));
        locStatus = info("Chưa bật giả lập GPS.");
        root.addView(locStatus, wrap());

        mockButton = button("▶  BẬT GIẢ LẬP GPS", Color.rgb(32, 150, 92), Color.WHITE);
        mockButton.setOnClickListener(v -> toggleMock());
        root.addView(mockButton, wrap());

        Button mockHelp = button("⚙  HƯỚNG DẪN CHỌN APP MÔ PHỎNG", Color.rgb(52, 60, 76), Color.WHITE);
        mockHelp.setOnClickListener(v -> showMockHelp());
        root.addView(mockHelp, wrap());

        root.addView(section("CHẠY NỀN"));
        bgButton = button("▶  BẬT CHẠY NỀN", Color.rgb(58, 104, 148), Color.WHITE);
        bgButton.setOnClickListener(v -> toggleBackground());
        root.addView(bgButton, wrap());
        root.addView(info("Bật để app giữ vị trí ảo / binder MHN / route khi fen chuyển sang MHN custom. "
                + "Sẽ có thông báo \"GPS MHN đang chạy nền\". Tự bật khi fen bật giả lập GPS hoặc di chuyển."), wrap());

        root.addView(section("KẾT NỐI MHN CUSTOM (AIDL)"));
        mhnStatus = info("Chưa kết nối MHN custom.");
        root.addView(mhnStatus, wrap());
        mhnButton = button("🔗  KẾT NỐI MHN CUSTOM", Color.rgb(150, 92, 42), Color.WHITE);
        mhnButton.setOnClickListener(v -> toggleMhn());
        root.addView(mhnButton, wrap());
        mhnPush = new CheckBox(this);
        mhnPush.setText("📍  Đẩy toạ độ vào game qua binder (changeLocation)");
        mhnPush.setTextColor(Color.WHITE);
        mhnPush.setChecked(true);
        mhnPush.setOnCheckedChangeListener((v, checked) -> mhn.setPushEnabled(checked));
        root.addView(mhnPush, wrap());
        root.addView(info("Bản MHN custom expose service (package com.nianticlabs.monsterhunter, action "
                + "com.imyfone.main.action_mhn) với AIDL com.imyfone.main.LocaltionInterfaceMHN: "
                + "changeLocation(lat,lng) / getVersion() / sendKeepLive(). Khi bật, mỗi lần vị trí giả lập "
                + "đổi sẽ gọi changeLocation để ghi đè vị trí trong game (không cần mock location), và gửi "
                + "keep-alive mỗi 2 giây. LƯU Ý: phải mở app MHN custom ít nhất 1 lần (nó nạp plugin.apk) "
                + "trước khi toạ độ có tác dụng."), wrap());
        Button mhnUpdate = button("⬇  CHECK UPDATE MHN", Color.rgb(52, 104, 168), Color.WHITE);
        mhnUpdate.setOnClickListener(v -> MhnUpdater.checkAndPrompt(this));
        root.addView(mhnUpdate, wrap());
        root.addView(info("Nguồn tải lấy từ AnyTo: download.pogoace.com/files/location/monsterhunterCustom.apk. "
                + "Nếu máy đã cài MHN custom thì app so version với server (apipdm.pogoace.com/game/version, type=3); "
                + "chưa cài hoặc có bản mới mới mời tải, đã mới nhất thì báo không cần."), wrap());

        root.addView(section("TỌA ĐỘ"));
        LinearLayout xy = row();
        latInput = input("Vĩ độ (lat)", false);
        lngInput = input("Kinh độ (lng)", false);
        latInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lngInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        xy.addView(latInput, weight());
        xy.addView(lngInput, weight());
        root.addView(xy, wrap());
        Button go = button("➤  ĐẾN TỌA ĐỘ (teleport)", Color.rgb(38, 100, 160), Color.WHITE);
        go.setOnClickListener(v -> teleportFromInputs());
        root.addView(go, wrap());

        root.addView(section("TỐC ĐỘ DI CHUYỂN"));
        speedSpinner = spinner();
        speedSpinner.setAdapter(adapter(new ArrayList<>(java.util.Arrays.asList(
                "Đi bộ — 1.2 m/s", "Chạy bộ — 3.6 m/s", "Xe đạp — 6 m/s",
                "Xe máy — 13 m/s", "Ô tô — 25 m/s", "Tùy chỉnh…"))));
        speedSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { applySpeed(pos); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        root.addView(speedSpinner, wrap());
        speedInput = input("Tốc độ tùy chỉnh (m/s)", false);
        speedInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(speedInput, wrap());

        root.addView(section("JOYSTICK"));
        joystick = new JoystickView(this);
        joystick.setListener(new JoystickView.Listener() {
            @Override public void onMove(double dx, double dy) {
                if (!engine.isRunning()) engine.startSim();   // joystick chỉ cần mô phỏng, không cần mock GPS
                ensureBackground();
                engine.setJoystick(dx, dy);
            }
            @Override public void onRelease() { engine.stopJoystick(); }
        });
        LinearLayout joyBox = row();
        joyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        int size = dp(190);
        LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(size, size);
        joyBox.addView(joystick, jp);
        root.addView(joyBox, wrap());
        Button floating = button("🕹  BẬT JOYSTICK NỔI (trên app khác)", Color.rgb(126, 82, 190), Color.WHITE);
        floating.setOnClickListener(v -> toggleOverlay());
        root.addView(floating, wrap());

        root.addView(section("ROUTE (điểm đi tuần tự)"));
        routeInfo = info("Route trống.");
        root.addView(routeInfo, wrap());
        Button addPoint = button("📌  GHIM ĐIỂM HIỆN TẠI", Color.rgb(58, 104, 148), Color.WHITE);
        addPoint.setOnClickListener(v -> {
            engine.addRoutePoint(engine.lat(), engine.lng());
            places.saveRoute(engine.route());
            refreshRouteInfo();
        });
        root.addView(addPoint, wrap());
        routeLoop = new CheckBox(this);
        routeLoop.setText("↻ Lặp lại route khi hết");
        routeLoop.setTextColor(Color.WHITE);
        routeLoop.setChecked(true);
        routeLoop.setOnCheckedChangeListener((v, c) -> engine.setRouteLoop(c));
        root.addView(routeLoop, wrap());
        LinearLayout routeActions = row();
        Button play = button("▶ PHÁT", Color.rgb(32, 150, 92), Color.WHITE);
        play.setOnClickListener(v -> {
            engine.setRoute(engine.route(), routeLoop.isChecked());
            engine.setJoystick(0, 0);
            if (!engine.isRunning()) engine.startSim();
            ensureBackground();
            toast("Đang chạy route " + engine.route().size() + " điểm");
        });
        Button stopRoute = button("■ DỪNG", Color.rgb(170, 58, 68), Color.WHITE);
        stopRoute.setOnClickListener(v -> { engine.stopJoystick(); toast("Đã dừng route"); });
        Button clear = button("🗑 XÓA", Color.rgb(90, 60, 66), Color.WHITE);
        clear.setOnClickListener(v -> { engine.clearRoute(); places.saveRoute(engine.route()); refreshRouteInfo(); });
        routeActions.addView(play, weight());
        routeActions.addView(stopRoute, weight());
        routeActions.addView(clear, weight());
        root.addView(routeActions, wrap());

        // Khôi phục route đã lưu
        List<double[]> saved = places.route();
        if (!saved.isEmpty()) {
            engine.setRoute(saved, true);
            refreshRouteInfo();
        }
        return scroll;
    }

    private void toggleMhn() {
        if (mhn.isConnected()) {
            mhn.stop();
        } else {
            String err = mhn.start();
            if (err != null) toast(err);
            ensureBackground();
            openMhnApp();
        }
    }

    /** Mở app MHN custom để nó nạp plugin và nhận vị trí. */
    private void openMhnApp() {
        if (!mhn.isGameInstalled()) { toast("Chưa cài MHN custom — bấm CHECK UPDATE để tải"); return; }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(MhnCustomBinder.PACKAGE);
            if (launch == null) { toast("Không tìm thấy app MHN custom để mở"); return; }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Exception e) {
            toast("Không mở được MHN custom: " + e.getMessage());
        }
    }

    private void updateMhnUi(boolean connected, String message) {
        if (mhnStatus == null) return;
        mhnStatus.setText(message);
        mhnStatus.setTextColor(connected ? Color.rgb(90, 220, 145) : Color.rgb(255, 170, 90));
        if (mhnButton != null) {
            mhnButton.setText(connected ? "■  NGẮT MHN CUSTOM" : "🔗  KẾT NỐI MHN CUSTOM");
            mhnButton.setBackgroundTintList(ColorStateList.valueOf(connected ? Color.rgb(170, 58, 68) : Color.rgb(150, 92, 42)));
        }
    }

    private void toggleBackground() {
        if (SimulationService.isRunning()) {
            SimulationService.stop(this);
        } else {
            SimulationService.start(this);
            checkBackgroundStarted();
        }
        handler.postDelayed(this::updateBgButton, 400);
    }

    /** Đảm bảo app chạy nền khi có hoạt động (mock/binder/di chuyển). */
    private void ensureBackground() {
        if (!SimulationService.isRunning()) {
            SimulationService.start(this);
            checkBackgroundStarted();
        }
        handler.postDelayed(this::updateBgButton, 400);
    }

    private void checkBackgroundStarted() {
        handler.postDelayed(() -> {
            if (!SimulationService.isRunning()) {
                toast("Không bật được chạy nền — kiểm tra quyền Thông báo / pin cho app");
            }
            updateBgButton();
        }, 900);
    }

    private void updateBgButton() {
        if (bgButton == null) return;
        boolean on = SimulationService.isRunning();
        bgButton.setText(on ? "■  TẮT CHẠY NỀN" : "▶  BẬT CHẠY NỀN");
        bgButton.setBackgroundTintList(ColorStateList.valueOf(on ? Color.rgb(170, 58, 68) : Color.rgb(58, 104, 148)));
    }

    private void toggleMock() {
        if (engine.isMockActive()) {
            engine.disableMock();
        } else {
            String err = engine.enableMock();
            if (err != null) {
                toast(err);
                showMockHelp();
            }
            ensureBackground();
        }
        updateMockButton();
    }

    private void updateMockButton() {
        boolean on = engine.isMockActive();
        mockButton.setText(on ? "■  TẮT GIẢ LẬP GPS" : "▶  BẬT GIẢ LẬP GPS");
        mockButton.setBackgroundTintList(ColorStateList.valueOf(on ? Color.rgb(170, 58, 68) : Color.rgb(32, 150, 92)));
    }

    private void applySpeed(int pos) {
        double[] speeds = {1.2, 3.6, 6, 13, 25, -1};
        if (pos >= 0 && pos < speeds.length && speeds[pos] > 0) {
            engine.setSpeed(speeds[pos]);
            speedInput.setText(String.format(Locale.US, "%.1f", speeds[pos]));
        } else {
            try { engine.setSpeed(Double.parseDouble(speedInput.getText().toString())); } catch (Exception ignored) { }
        }
        onPosition(engine.lat(), engine.lng(), engine.bearing(), engine.speed());
    }

    private void teleportFromInputs() {
        try {
            double lat = Double.parseDouble(latInput.getText().toString().trim());
            double lng = Double.parseDouble(lngInput.getText().toString().trim());
            engine.moveTo(lat, lng);
            if (!engine.isRunning()) engine.startSim();
            ensureBackground();
            updateMockButton();
            refreshMapMarkers();
            toast("Đã đặt vị trí " + fmt(lat) + ", " + fmt(lng));
        } catch (Exception e) {
            toast("Toạ độ không hợp lệ");
        }
    }

    private void refreshRouteInfo() {
        List<double[]> r = engine.route();
        routeInfo.setText(r.isEmpty() ? "Route trống." : "Route có " + r.size() + " điểm.");
    }

    private void toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            toast("Cấp quyền \"Hiển thị trên ứng dụng khác\" rồi bấm lại");
            return;
        }
        if (!engine.isRunning()) engine.startSim();   // joystick nổi chỉ cần mô phỏng, không cần mock GPS
        ensureBackground();
        startForegroundService(new Intent(this, LocationOverlayService.class));
        toast("Đã bật joystick nổi");
    }

    private void showMockHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Bật vị trí mô phỏng")
                .setMessage("1. Mở Cài đặt → Giới thiệu điện thoại → bấm 7 lần vào Số bản dựng để bật Tùy chọn nhà phát triển.\n\n"
                        + "2. Vào Tùy chọn nhà phát triển → \"Chọn ứng dụng vị trí mô phỏng\" → chọn aTSBot.\n\n"
                        + "3. Quay lại đây bấm BẬT GIẢ LẬP GPS.\n\n"
                        + "Lưu ý: giả lập GPS chỉ ảnh hưởng app đọc qua LocationManager; vị trí hiển thị trên game có thể cần bật quyền vị trí cho app này.")
                .setPositiveButton("MỞ TÙY CHỌN NHÀ PHÁT TRIỂN", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
                    catch (Exception ignored) { }
                })
                .setNegativeButton("ĐÓNG", null)
                .show();
    }

    // ================================================================== map page

    private View buildMapPage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        mapView = new OsmMapView(this);
        mapView.setListener(new OsmMapView.Listener() {
            @Override public void onMapTap(double lat, double lng) {
                selectedLat = lat; selectedLng = lng; hasSelection = true;
                refreshMapMarkers();
                mapInfo.setText("Đã chọn: " + fmt(lat) + ", " + fmt(lng));
                fetchRouteTo(lat, lng, autoMoveOnTap);
            }
            @Override public void onCurrentChanged(double lat, double lng) { }
        });
        root.addView(mapView, new FrameLayout.LayoutParams(-1, -1));   // bản đồ full màn hình

        // Panel điều khiển NỔI trên bản đồ (nền mờ), neo ở đáy.
        LinearLayout panel = column();
        panel.setBackgroundColor(Color.argb(228, 6, 14, 26));
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));

        // 3 chế độ di chuyển: đi bộ / xe đạp / ô tô
        LinearLayout modes = row();
        String[] modeLabels = {"🚶  ĐI BỘ", "🚲  XE ĐẠP", "🚗  Ô TÔ"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            moveModeButtons[i] = button(modeLabels[i], Color.rgb(25, 35, 49), Color.WHITE);
            moveModeButtons[i].setOnClickListener(v -> selectMoveMode(mode));
            modes.addView(moveModeButtons[i], weight());
        }
        panel.addView(modes, wrap());

        LinearLayout tools = row();
        Button here = button("🎯 VỊ TRÍ THẬT", Color.rgb(38, 100, 160), Color.WHITE);
        here.setOnClickListener(v -> goToRealLocation());
        Button tele = button("➤ ĐI TỚI", Color.rgb(32, 150, 92), Color.WHITE);
        tele.setOnClickListener(v -> moveToSelected());
        Button jump = button("⚡ TELEPORT", Color.rgb(170, 100, 40), Color.WHITE);
        jump.setOnClickListener(v -> teleportToSelected());
        Button stopBtn = button("■ DỪNG", Color.rgb(170, 58, 68), Color.WHITE);
        stopBtn.setOnClickListener(v -> stopFollowing());
        tools.addView(here, weight());
        tools.addView(tele, weight());
        tools.addView(jump, weight());
        tools.addView(stopBtn, weight());
        panel.addView(tools, wrap());

        LinearLayout tools2 = row();
        Button save = button("📌 LƯU ĐIỂM", Color.rgb(126, 82, 190), Color.WHITE);
        save.setOnClickListener(v -> askSavePlace());
        Button addRoute = button("➕  VÀO ROUTE", Color.rgb(150, 92, 42), Color.WHITE);
        addRoute.setOnClickListener(v -> {
            if (!hasSelection) { toast("Chạm bản đồ để chọn điểm"); return; }
            engine.addRoutePoint(selectedLat, selectedLng);
            places.saveRoute(engine.route());
            refreshRouteInfo();
            refreshMapMarkers();
            toast("Đã thêm vào route");
        });
        Button listToggle = button("📋  DANH SÁCH", Color.rgb(52, 60, 76), Color.WHITE);
        Button clearTrail = button("🧹  XÓA VẾT", Color.rgb(90, 60, 66), Color.WHITE);
        clearTrail.setOnClickListener(v -> {
            engine.clearTrail();
            mapView.setTrail(engine.trail());
            refreshMapMarkers();
            toast("Đã xóa vệt đường đi");
        });
        tools2.addView(save, weight());
        tools2.addView(addRoute, weight());
        tools2.addView(listToggle, weight());
        tools2.addView(clearTrail, weight());
        panel.addView(tools2, wrap());

        autoMoveCheck = new CheckBox(this);
        autoMoveCheck.setText("🚶  Tự tìm đường & đi khi chạm bản đồ");
        autoMoveCheck.setTextColor(Color.WHITE);
        autoMoveCheck.setChecked(true);
        autoMoveCheck.setOnCheckedChangeListener((v, checked) -> autoMoveOnTap = checked);
        panel.addView(autoMoveCheck, wrap());

        mapInfo = info("Chạm bản đồ: tìm đường tới điểm đó (đường cam) rồi đi theo đường. Đỏ = vị trí đã lưu, xanh lá = GPS thật, cyan = vệt đã đi.");
        panel.addView(mapInfo, wrap());

        placeList = column();
        placeList.setPadding(dp(8), dp(2), dp(8), dp(6));
        final ScrollView placeScroll = new ScrollView(this);
        placeScroll.setBackgroundColor(Color.argb(215, 10, 20, 34));
        placeScroll.addView(placeList);
        placeScroll.setVisibility(View.GONE);
        listToggle.setOnClickListener(v -> {
            boolean show = placeScroll.getVisibility() != View.VISIBLE;
            placeScroll.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) refreshPlaces();
        });
        panel.addView(placeScroll, new LinearLayout.LayoutParams(-1, dp(150)));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(panel, panelLp);
        return root;
    }

    private void askSavePlace() {
        if (!hasSelection) { toast("Chạm bản đồ để chọn điểm"); return; }
        final EditText field = input("Tên địa điểm", false);
        new AlertDialog.Builder(this)
                .setTitle("Lưu địa điểm")
                .setView(field)
                .setNegativeButton("HỦY", null)
                .setPositiveButton("LƯU", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) name = fmt(selectedLat) + ", " + fmt(selectedLng);
                    places.addPlace(name, selectedLat, selectedLng);
                    refreshPlaces();
                    refreshMapMarkers();
                })
                .show();
    }

    private void refreshPlaces() {
        if (placeList == null) return;
        placeList.removeAllViews();
        List<PlaceStore.Place> list = places.places();
        if (list.isEmpty()) {
            placeList.addView(info("Chưa lưu địa điểm nào."), wrap());
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final PlaceStore.Place p = list.get(i);
            final int index = i;
            LinearLayout r = row();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), dp(8), dp(10), dp(8));
            r.setBackgroundColor(i % 2 == 0 ? Color.rgb(19, 36, 58) : Color.rgb(15, 30, 49));
            TextView label = text(p.name + "\n" + fmt(p.lat) + ", " + fmt(p.lng), 13, Color.WHITE);
            label.setSingleLine(false);
            r.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            Button goBtn = button("➤", Color.rgb(32, 150, 92), Color.WHITE);
            goBtn.setOnClickListener(v -> {
                engine.moveTo(p.lat, p.lng);
                if (!engine.isRunning()) engine.startSim();
                mapView.centerOn(p.lat, p.lng, false);
                updateMockButton();
                refreshMapMarkers();
            });
            Button delBtn = button("🗑", Color.rgb(150, 60, 66), Color.WHITE);
            delBtn.setOnClickListener(v -> { places.removePlace(index); refreshPlaces(); refreshMapMarkers(); });
            r.addView(goBtn, new LinearLayout.LayoutParams(dp(58), dp(46)));
            r.addView(delBtn, new LinearLayout.LayoutParams(dp(58), dp(46)));
            placeList.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void refreshMapMarkers() {
        if (mapView == null) return;
        List<PlaceStore.Place> list = places.places();
        List<double[]> pts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (PlaceStore.Place p : list) { pts.add(new double[]{p.lat, p.lng}); labels.add(p.name); }
        if (hasSelection) {
            pts.add(new double[]{selectedLat, selectedLng});
            labels.add("Điểm chọn");
        }
        mapView.setMarkers(pts, labels);
        mapView.setRoute(engine.route().size() > 1 ? engine.route() : plannedPath);
        mapView.setTrail(engine.trail());
        if (hasReal) mapView.setRealLocation(realLat, realLng);
        // Chưa bật giả lập GPS thì "vị trí hiện tại" chính là GPS thật của máy.
        if (!engine.isRunning() && hasReal) mapView.setCurrent(realLat, realLng);
        else mapView.setCurrent(engine.lat(), engine.lng());
        String cur;
        if (engine.isRunning() && engine.isMockActive()) cur = "Đang giả lập (mock GPS): " + fmt(engine.lat()) + ", " + fmt(engine.lng());
        else if (engine.isRunning()) cur = "Đang mô phỏng (mock tắt): " + fmt(engine.lat()) + ", " + fmt(engine.lng());
        else if (hasReal) cur = "GPS thật: " + fmt(realLat) + ", " + fmt(realLng);
        else cur = "Chưa lấy được GPS thật";
        mapInfo.setText(cur + "  •  Đã lưu " + list.size() + " điểm  •  Route " + engine.route().size() + " điểm");
    }

    // ================================================================== map actions

    private void selectMoveMode(int mode) {
        moveMode = mode;
        for (int i = 0; i < moveModeButtons.length; i++) {
            if (moveModeButtons[i] == null) continue;
            moveModeButtons[i].setBackgroundTintList(ColorStateList.valueOf(i == mode ? GOLD : Color.rgb(25, 35, 49)));
            moveModeButtons[i].setTextColor(i == mode ? Color.rgb(20, 20, 20) : Color.WHITE);
        }
        engine.setSpeed(speedForMode());
    }

    private double speedForMode() {
        return moveMode == 0 ? 1.4 : moveMode == 1 ? 6.0 : 16.0;   // m/s
    }

    private String modeName() {
        return moveMode == 0 ? "đi bộ" : moveMode == 1 ? "xe đạp" : "ô tô";
    }

    /** ĐI TỚI: tìm đường tới điểm chọn rồi đi theo đúng con đường đó. */
    private void moveToSelected() {
        if (!hasSelection) { toast("Chạm bản đồ để chọn điểm"); return; }
        // Đã có đường vẽ sẵn tới đúng điểm này thì đi luôn, khỏi gọi lại mạng.
        if (!plannedPath.isEmpty()) {
            double[] last = plannedPath.get(plannedPath.size() - 1);
            if (distMeters(last[0], last[1], selectedLat, selectedLng) < 30) { startFollowingPath(); return; }
        }
        fetchRouteTo(selectedLat, selectedLng, true);
    }

    private void teleportToSelected() {
        if (!hasSelection) { toast("Chạm bản đồ để chọn điểm"); return; }
        engine.cancelMoveTo();
        engine.clearRoute();
        plannedPath.clear();
        engine.moveTo(selectedLat, selectedLng);
        if (!engine.isRunning()) engine.startSim();
        ensureBackground();
        updateMockButton();
        refreshMapMarkers();
        toast("Đã teleport tới điểm chọn");
    }

    /** Tìm đường (OSRM) rồi vẽ lên bản đồ; move=true thì đi theo đường luôn. */
    private void fetchRouteTo(double lat, double lng, boolean move) {
        if (routeFetching) { toast("Đang tìm đường…"); return; }
        routeFetching = true;
        if (move) toast("Đang tìm đường…");
        final double fromLat = engine.lat(), fromLng = engine.lng();
        new Thread(() -> {
            RouteFinder.Result res = null;
            String err = null;
            try { res = RouteFinder.fetch(fromLat, fromLng, lat, lng); }
            catch (Exception e) { err = e.getMessage(); }
            final RouteFinder.Result fr = res;
            final String fe = err;
            runOnUiThread(() -> {
                routeFetching = false;
                if (fr == null) { toast("Không tìm được đường: " + fe); return; }
                plannedPath.clear();
                plannedPath.addAll(fr.path);
                mapView.setRoute(plannedPath);
                mapView.setCurrent(engine.lat(), engine.lng());
                if (move) {
                    startFollowingPath();
                    toast("Đang " + modeName() + " theo đường (" + fmtDist(fr.distanceMeters) + ")");
                } else {
                    toast("Đã vẽ đường (" + fmtDist(fr.distanceMeters) + ") — bấm ĐI TỚI để đi");
                }
                refreshMapMarkers();
            });
        }, "route-fetch").start();
    }

    /** Đi theo đường đã vẽ (plannedPath) với tốc độ của chế độ đang chọn. */
    private void startFollowingPath() {
        if (plannedPath.size() < 2) { toast("Chưa có đường để đi"); return; }
        engine.setSpeed(speedForMode());
        engine.setRoute(plannedPath, false);
        if (!engine.isRunning()) engine.startSim();
        ensureBackground();
        updateMockButton();
        refreshMapMarkers();
    }

    /** Dừng đi theo đường (giữ nguyên vị trí hiện tại). */
    private void stopFollowing() {
        engine.cancelMoveTo();
        engine.clearRoute();
        plannedPath.clear();
        if (mapView != null) mapView.setRoute(plannedPath);
        refreshMapMarkers();
        toast("Đã dừng đi theo đường");
    }

    private static double distMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = (lat2 - lat1) * 111320.0;
        double dLng = (lng2 - lng1) * 111320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(dLat, dLng);
    }

    private static String fmtDist(double meters) {
        if (meters >= 1000) return String.format(Locale.US, "%.2f km", meters / 1000.0);
        return String.format(Locale.US, "%.0f m", meters);
    }

    /** Về vị trí GPS thật của máy. */
    private void goToRealLocation() {
        if (!hasReal) { toast("Chưa lấy được GPS thật, thử lại sau"); requestRealLocation(); return; }
        engine.cancelMoveTo();
        engine.clearRoute();
        plannedPath.clear();
        engine.moveTo(realLat, realLng);
        if (!engine.isRunning()) engine.startSim();
        ensureBackground();
        mapView.centerOn(realLat, realLng, false);
        mapView.setCurrent(realLat, realLng);
        updateMockButton();
        refreshMapMarkers();
        toast("Đã về vị trí GPS thật của máy");
    }

    private void requestRealLocation() {
        if (locationManager == null) return;
        try {
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location l = locationManager.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) onRealLocation(best);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 1f, realListener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 1f, realListener);
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    private void onRealLocation(Location l) {
        realLat = l.getLatitude();
        realLng = l.getLongitude();
        hasReal = true;
        if (mapView != null) {
            mapView.setRealLocation(realLat, realLng);
            if (!engine.isRunning()) mapView.setCurrent(realLat, realLng);
            if (!realApplied) { mapView.centerOn(realLat, realLng, false); realApplied = true; }
        }
        // Đặt mốc xuất phát cho engine = vị trí thật (khi chưa giả lập gì).
        if (!engine.isRunning() && !engine.hasTarget()) engine.moveTo(realLat, realLng);
        if (currentPage == 1) refreshMapMarkers();
    }

    private final LocationListener realListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { onRealLocation(location); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };

    // ================================================================== OCR page

    private View buildOcrPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(14), dp(8), dp(14), dp(20));
        scroll.addView(root);

        root.addView(section("ĐỌC MÀN HÌNH (OCR)"));
        ocrStatus = info("Chưa bật chụp màn hình.");
        root.addView(ocrStatus, wrap());

        ocrStart = button("①  BẬT CHỤP MÀN HÌNH", Color.rgb(38, 100, 160), Color.WHITE);
        ocrStart.setOnClickListener(v -> requestProjection());
        root.addView(ocrStart, wrap());

        ocrCapture = button("②  QUÉT CHỈ SỐ GAME", Color.rgb(32, 150, 92), Color.WHITE);
        ocrCapture.setOnClickListener(v -> {
            ScreenOcrService.setListener(ocrListener);
            if (!ScreenOcrService.isRunning()) { toast("Bấm \"BẬT CHỤP MÀN HÌNH\" trước"); return; }
            startService(new Intent(this, ScreenOcrService.class).setAction("CAPTURE"));
        });
        root.addView(ocrCapture, wrap());

        Button fillIv = button("③  ĐIỀN VÀO MÁY TÍNH IV", Color.rgb(126, 82, 190), Color.WHITE);
        fillIv.setOnClickListener(v -> fillIvFromOcr());
        root.addView(fillIv, wrap());

        root.addView(section("KẾT QUẢ"));
        ocrResult = info("Chưa quét.");
        ocrResult.setTextIsSelectable(true);
        ocrResult.setTypeface(Typeface.MONOSPACE);
        root.addView(ocrResult, wrap());

        root.addView(info("Mẹo: mở màn hình chi tiết Pokémon (có CP/HP/bụi) rồi bấm QUÉT. "
                + "Kết quả OCR có thể sai vài ký tự — hãy kiểm tra lại ở tab IV."), wrap());
        return scroll;
    }

    private final ScreenOcrService.Listener ocrListener = new ScreenOcrService.Listener() {
        @Override public void onOcrText(String text, Bitmap screenshot) {
            runOnUiThread(() -> {
                ocrResult.setText(text == null || text.trim().isEmpty() ? "(Không nhận ra chữ nào)" : text);
                ocrStatus.setText("✓ Đã quét xong. Bấm \"ĐIỀN VÀO MÁY TÍNH IV\".");
            });
        }
        @Override public void onOcrError(String message) {
            runOnUiThread(() -> { ocrStatus.setText("✗ " + message); toast(message); });
        }
    };

    private void requestProjection() {
        ScreenOcrService.setListener(ocrListener);
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    private void fillIvFromOcr() {
        String text = ocrResult.getText().toString();
        PokemonData.Species sp = pokemon == null ? null : pokemon.fuzzyName(firstLine(text));
        Integer cp = matchInt(text, "(?i)\\bCP\\b\\D{0,8}(\\d{1,5})");
        if (cp == null) cp = matchInt(text, "(\\d{1,5})\\s*CP");
        Integer hp = matchInt(text, "(\\d{1,4})\\s*/\\s*\\d{1,4}");
        if (hp == null) hp = matchInt(text, "(?i)\\bHP\\b\\D{0,8}(\\d{1,4})");
        Integer dust = findStardust(text);
        if (sp != null && ivSpeciesSpinner != null) {
            int index = speciesList.indexOf(sp);
            if (index >= 0) ivSpeciesSpinner.setSelection(index);
            selectedSpecies = sp;
        }
        if (cp != null) ivCp.setText(String.valueOf(cp));
        if (hp != null) ivHp.setText(String.valueOf(hp));
        if (dust != null) ivDust.setText(String.valueOf(dust));
        showPage(3);
        ocrStatus.setText("Đã điền dữ liệu OCR vào tab IV (kiểm tra lại trước khi tính).");
    }

    // ================================================================== IV page

    private View buildIvPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(14), dp(8), dp(14), dp(20));
        scroll.addView(root);

        root.addView(section("MÁY TÍNH IV / CP / PVP"));
        ivName = input("Tên Pokémon (vd: Venusaur)", false);
        root.addView(ivName, wrap());
        ivSpeciesSpinner = spinner();
        root.addView(ivSpeciesSpinner, wrap());
        ivSpeciesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < speciesList.size()) selectedSpecies = speciesList.get(pos);
                if (selectedSpecies != null) ivName.setText(selectedSpecies.displayName());
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        Button findName = button("🔎  TÌM THEO TÊN", Color.rgb(38, 100, 160), Color.WHITE);
        findName.setOnClickListener(v -> findByTypedName());
        root.addView(findName, wrap());

        LinearLayout chp = row();
        ivCp = input("CP", false); ivHp = input("HP", false);
        ivCp.setInputType(InputType.TYPE_CLASS_NUMBER);
        ivHp.setInputType(InputType.TYPE_CLASS_NUMBER);
        chp.addView(ivCp, weight());
        chp.addView(ivHp, weight());
        root.addView(chp, wrap());

        LinearLayout dl = row();
        ivDust = input("Bụi (stardust)", false); ivLevel = input("Cấp (vd 25.5)", false);
        ivDust.setInputType(InputType.TYPE_CLASS_NUMBER);
        ivLevel.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        dl.addView(ivDust, weight());
        dl.addView(ivLevel, weight());
        root.addView(dl, wrap());

        ivShadow = new CheckBox(this);
        ivShadow.setText("👤 Pokémon Shadow (bóng tối)");
        ivShadow.setTextColor(Color.WHITE);
        root.addView(ivShadow, wrap());

        LinearLayout actions = row();
        Button byDust = button("💠 TÌM THEO BỤI", Color.rgb(32, 150, 92), Color.WHITE);
        byDust.setOnClickListener(v -> computeIv(true));
        Button byLevel = button("📈 TÌM THEO CẤP", Color.rgb(126, 82, 190), Color.WHITE);
        byLevel.setOnClickListener(v -> computeIv(false));
        actions.addView(byDust, weight());
        actions.addView(byLevel, weight());
        root.addView(actions, wrap());

        ivResult = info("Nhập thông tin rồi bấm TÌM. Dùng \"bụi\" khi biết giá Power Up, hoặc \"cấp\" nếu biết chính xác cấp.");
        ivResult.setTextIsSelectable(true);
        ivResult.setTypeface(Typeface.MONOSPACE);
        root.addView(ivResult, wrap());
        return scroll;
    }

    private void loadPokemonAsync() {
        new Thread(() -> {
            final PokemonData data;
            try { data = PokemonData.get(this); }
            catch (Throwable t) { runOnUiThread(() -> ivResult.setText("Lỗi nạp dữ liệu Pokémon: " + t.getMessage())); return; }
            runOnUiThread(() -> {
                pokemon = data;
                iv = new IvCalculator(data);
                speciesList.clear();
                speciesList.addAll(data.all());
                List<String> labels = new ArrayList<>();
                for (PokemonData.Species s : speciesList) labels.add("#" + s.id + "  " + s.displayName());
                ivSpeciesSpinner.setAdapter(adapter(labels));
                ivResult.setText("Đã nạp " + speciesList.size() + " loài Pokémon. Nhập CP/HP/bụi rồi bấm TÌM.");
            });
        }, "pokemon-load").start();
    }

    private void findByTypedName() {
        if (pokemon == null) { toast("Đang nạp dữ liệu, thử lại sau"); return; }
        PokemonData.Species s = pokemon.byName(ivName.getText().toString());
        if (s == null) s = pokemon.fuzzyName(ivName.getText().toString());
        if (s == null) { toast("Không tìm thấy Pokémon"); return; }
        selectedSpecies = s;
        int index = speciesList.indexOf(s);
        if (index >= 0) ivSpeciesSpinner.setSelection(index);
        toast("Đã chọn " + s.displayName());
    }

    private void computeIv(boolean byStardust) {
        if (pokemon == null || iv == null) { toast("Đang nạp dữ liệu, thử lại sau"); return; }
        PokemonData.Species sp = selectedSpecies;
        if (sp == null) { findByTypedName(); sp = selectedSpecies; }
        if (sp == null) { toast("Chưa chọn Pokémon"); return; }
        int cp = parseInt(ivCp, 0), hp = parseInt(ivHp, 0);
        boolean shadow = ivShadow.isChecked();
        List<IvCalculator.IvCombo> combos;
        StringBuilder header = new StringBuilder(sp.displayName() + "  •  ");
        if (byStardust) {
            int dust = parseInt(ivDust, 0);
            if (dust <= 0) { toast("Nhập mức bụi Power Up"); return; }
            combos = iv.searchFromStardust(sp, cp, hp, dust, shadow);
            header.append("bụi ").append(dust).append("  •  CP ").append(cp).append("  •  HP ").append(hp);
        } else {
            double level = parseDouble(ivLevel, 0);
            if (level <= 0) { toast("Nhập cấp (vd 25.5)"); return; }
            combos = iv.searchAtLevel(sp, cp, hp, level, shadow);
            header.append("cấp ").append(level).append("  •  CP ").append(cp).append("  •  HP ").append(hp);
        }
        StringBuilder sb = new StringBuilder(header).append("\n");
        if (combos.isEmpty()) {
            sb.append("\nKhông tìm thấy tổ hợp IV khớp. Kiểm tra lại CP/HP/cấp/bụi.");
        } else {
            sb.append("Tìm thấy ").append(combos.size()).append(" tổ hợp (sắp theo stat product):\n\n");
            int limit = Math.min(combos.size(), 60);
            for (int i = 0; i < limit; i++) sb.append(formatCombo(combos.get(i))).append('\n');
            if (combos.size() > limit) sb.append("… còn ").append(combos.size() - limit).append(" tổ hợp nữa\n");
        }
        // Xếp hạng PVP
        sb.append("\n── XẾP HẠNG PVP ──\n");
        sb.append(rankLine(sp, IvCalculator.CP_LITTLE, 50, shadow, "Little  (≤500)"));
        sb.append(rankLine(sp, IvCalculator.CP_GREAT, 50, shadow, "Great   (≤1500)"));
        sb.append(rankLine(sp, IvCalculator.CP_ULTRA, 50, shadow, "Ultra   (≤2500)"));
        sb.append(rankLine(sp, 0, 50, shadow, "Master  (không trần)"));
        ivResult.setText(sb.toString());
    }

    private String rankLine(PokemonData.Species sp, int cap, double maxLevel, boolean shadow, String label) {
        List<IvCalculator.RankEntry> ranked = iv.rank(sp, cap, maxLevel, shadow, 1);
        if (ranked.isEmpty()) return label + ": không có dữ liệu\n";
        IvCalculator.RankEntry top = ranked.get(0);
        return String.format(Locale.US, "%s • #1 là %s (CP %d, %.1f%%)\n",
                label, top.combo.shortForm(), top.combo.cp, top.combo.percent());
    }

    private String formatCombo(IvCalculator.IvCombo c) {
        return String.format(Locale.US, "  %d/%d/%d  cấp %.1f  CP %d  HP %d  %.1f%%",
                c.attackIV, c.defenseIV, c.staminaIV, c.level, c.cp, c.hp, c.percent());
    }

    // ================================================================== engine callbacks

    @Override public void onPosition(double lat, double lng, float bearing, double speed) {
        handler.post(() -> {
            if (currentPage == 0 || currentPage == 1) {
                if (latInput != null && !latInput.hasFocus()) latInput.setText(fmt(lat));
                if (lngInput != null && !lngInput.hasFocus()) lngInput.setText(fmt(lng));
            }
            if (locStatus != null && engine.isRunning()) {
                String head = engine.isMockActive() ? "Đang giả lập GPS" : "Đang mô phỏng (mock provider tắt — chỉ đẩy qua binder MHN)";
                locStatus.setText(head + "\nVị trí: " + fmt(lat) + ", " + fmt(lng)
                        + "\nHướng: " + Math.round(bearing) + "°  •  Tốc độ: " + fmt(speed) + " m/s");
            }
            if (mapView != null) mapView.setCurrent(lat, lng);
            if (mapView != null) mapView.setTrail(engine.trail());
            if (mapInfo != null && currentPage == 1) {
                mapInfo.setText("Vị trí hiện tại: " + fmt(lat) + ", " + fmt(lng)
                        + "  •  Đã lưu " + places.places().size() + " điểm  •  Route " + engine.route().size() + " điểm");
            }
        });
    }

    @Override public void onEngineState(boolean running, String message) {
        handler.post(() -> {
            updateMockButton();
            locStatus.setText(message);
        });
    }

    // ================================================================== permissions

    private void requestRuntimePermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenOcrService.setListener(ocrListener);
                Intent i = new Intent(this, ScreenOcrService.class)
                        .setAction("START")
                        .putExtra("code", resultCode)
                        .putExtra("data", data);
                startForegroundService(i);
                ocrStatus.setText("✓ Đã bật chụp màn hình. Chuyển sang app/game rồi quay lại bấm QUÉT.");
                handler.postDelayed(() -> {
                    if (ScreenOcrService.isRunning()) ocrStatus.setText("✓ Chụp màn hình đang bật. Bấm QUÉT CHỈ SỐ GAME.");
                }, 1200);
            } else {
                ocrStatus.setText("✗ Bạn đã từ chối quyền chụp màn hình.");
            }
        } else if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) toggleOverlay();
        }
    }

    // ================================================================== UI helpers

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private FrameLayout.LayoutParams full() { return new FrameLayout.LayoutParams(-1, -1); }

    private TextView text(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(size); v.setTextColor(color);
        return v;
    }
    private TextView section(String s) {
        TextView v = text(s, 14, BLUE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(18), 0, dp(8));
        return v;
    }
    private TextView info(String s) {
        TextView v = text(s, 13, Color.rgb(185, 205, 230));
        v.setSingleLine(false);
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        v.setBackgroundColor(CARD);
        return v;
    }
    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(Color.rgb(145, 163, 186));
        e.setTextColor(Color.rgb(244, 248, 255));
        e.setSingleLine(true);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
    private Spinner spinner() {
        Spinner s = new Spinner(this);
        s.setBackgroundTintList(ColorStateList.valueOf(GOLD));
        return s;
    }
    private ArrayAdapter<String> adapter(List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            private TextView paint(View v, int color) {
                TextView t = (TextView) v;
                t.setTextColor(color); t.setTextSize(14);
                t.setPadding(dp(12), dp(10), dp(12), dp(10));
                return t;
            }
            @Override public View getView(int p, View c, ViewGroup g) { return paint(super.getView(p, c, g), Color.WHITE); }
            @Override public View getDropDownView(int p, View c, ViewGroup g) {
                TextView t = paint(super.getDropDownView(p, c, g), Color.rgb(25, 35, 48));
                t.setBackgroundColor(Color.WHITE);
                return t;
            }
        };
    }
    private Button button(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextSize(13); b.setMinHeight(dp(50));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(12));
        b.setBackground(shape);
        b.setBackgroundTintList(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(37, 45, 58), bg}));
        b.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(120, 132, 149), fg}));
        return b;
    }
    private LinearLayout.LayoutParams wrap() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(4), 0, dp(4)); return p; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(4), dp(6), dp(4), dp(6)); return p; }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ================================================================== parsing helpers

    private static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static Integer matchInt(String text, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) { }
        return null;
    }

    private Integer findStardust(String text) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{2,6})\\b").matcher(text);
            while (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v >= 200 && v <= 20000) {
                    for (PokemonData.PowerUp p : pokemon.powerUps()) if (p.stardust == v) return v;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }
    private double parseDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }
    private static String fmt(double v) { return String.format(Locale.US, "%.5f", v); }
}
