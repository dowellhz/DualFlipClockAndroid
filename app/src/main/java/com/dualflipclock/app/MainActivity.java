package com.dualflipclock.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Camera;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 31;
    private static final int STAR_COUNT = 150;
    private static final String PREF_LAYOUT_MODE = "layoutMode";
    private static final String LAYOUT_STACKED = "stacked";
    private static final String LAYOUT_SIDE_BY_SIDE = "sideBySide";
    private static final float FLIP_DURATION_MS = 620f;
    private static final double BEIJING_LATITUDE = 39.9042;
    private static final double BEIJING_LONGITUDE = 116.4074;
    private static final int MAX_WEATHER_RESPONSE_CHARS = 64 * 1024;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private FrameLayout rootLayout;
    private ClockSurface clockSurface;
    private SharedPreferences preferences;
    private City primaryCity = CityData.monterey();
    private City secondaryCity = null;
    private String gpsCityName = "北京";
    private TimeZone gpsTimeZone = TimeZone.getTimeZone("Asia/Shanghai");
    private Location gpsLocation = null;
    private String primaryTemp = "--°C";
    private String secondaryTemp = "--°C";
    private WeatherSnapshot primaryWeather = WeatherSnapshot.unknown();
    private WeatherSnapshot secondaryWeather = WeatherSnapshot.unknown();
    private View activePicker = null;
    private boolean useSideBySideLayout = true;
    private volatile boolean destroyed = false;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            clockSurface.invalidate();
            long now = System.currentTimeMillis();
            long delay = Math.max(250L, 1000L - (now % 1000L) + 20L);
            handler.postDelayed(this, delay);
        }
    };

    private final Runnable weatherRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTemperatures();
            handler.postDelayed(this, 30L * 60L * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        CityData.loadFromAssets(this);
        preferences = getSharedPreferences("clock", MODE_PRIVATE);
        primaryCity = CityData.find(preferences.getString("primaryCity", CityData.monterey().id));
        secondaryCity = CityData.findNullable(preferences.getString("secondaryCity", ""));
        useSideBySideLayout = LAYOUT_SIDE_BY_SIDE.equals(preferences.getString(PREF_LAYOUT_MODE, LAYOUT_SIDE_BY_SIDE));

        rootLayout = new FrameLayout(this);
        clockSurface = new ClockSurface(this);
        rootLayout.addView(clockSurface, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootLayout);
        hideSystemUi();
        requestLocation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(weatherRunnable);
        handler.post(tickRunnable);
        handler.post(weatherRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(weatherRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) {
            requestLocation();
        }
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        if (decorView == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            decorView.post(() -> {
                WindowInsetsController controller = decorView.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            });
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void requestLocation() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }

        executor.execute(() -> {
            try {
                LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
                Location location = null;
                if (manager != null) {
                    location = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (location == null) {
                        location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    }
                }

                if (location != null) {
                    gpsLocation = location;
                    String city = reverseGeocode(location);
                    City knownCity = CityData.matchKnownCity(city);
                    if (knownCity != null) {
                        gpsCityName = displayNameForLocale(knownCity);
                        gpsTimeZone = knownCity.zone;
                    } else {
                        gpsCityName = city == null ? "北京" : city;
                        gpsTimeZone = timeZoneFromLongitude(location.getLongitude());
                    }
                } else {
                    gpsCityName = "北京";
                }
            } catch (Exception ignored) {
                gpsCityName = "北京";
            }

            if (destroyed) return;
            handler.post(() -> {
                if (destroyed) return;
                refreshTemperatures();
                clockSurface.invalidate();
            });
        });
    }

    private String reverseGeocode(Location location) {
        try {
            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (addresses == null || addresses.isEmpty()) return null;
            Address address = addresses.get(0);
            if (address.getLocality() != null) return address.getLocality();
            if (address.getSubAdminArea() != null) return address.getSubAdminArea();
            if (address.getAdminArea() != null) return address.getAdminArea();
        } catch (Exception ignored) {
        }
        return null;
    }

    private TimeZone timeZoneFromLongitude(double longitude) {
        int offsetHours = (int) Math.round(longitude / 15.0);
        String[] ids = TimeZone.getAvailableIDs(offsetHours * 60 * 60 * 1000);
        return ids.length > 0 ? TimeZone.getTimeZone(ids[0]) : TimeZone.getDefault();
    }

    private void refreshTemperatures() {
        fetchWeather(primaryCity.latitude, primaryCity.longitude, value -> {
            primaryTemp = value.temperature;
            primaryWeather = value;
            clockSurface.invalidate();
        });

        if (secondaryCity != null) {
            fetchWeather(secondaryCity.latitude, secondaryCity.longitude, value -> {
                secondaryTemp = value.temperature;
                secondaryWeather = value;
                clockSurface.invalidate();
            });
        } else if (gpsLocation != null) {
            fetchWeather(gpsLocation.getLatitude(), gpsLocation.getLongitude(), value -> {
                secondaryTemp = value.temperature;
                secondaryWeather = value;
                clockSurface.invalidate();
            });
        } else {
            fetchWeather(BEIJING_LATITUDE, BEIJING_LONGITUDE, value -> {
                secondaryTemp = value.temperature;
                secondaryWeather = value;
                clockSurface.invalidate();
            });
        }
    }

    private String displayNameForLocale(City city) {
        String language = Locale.getDefault().getLanguage();
        return "zh".equals(language) ? city.displayName : city.englishName;
    }

    private void fetchWeather(double latitude, double longitude, WeatherCallback callback) {
        executor.execute(() -> {
            String value = "--°C";
            int weatherCode = -1;
            boolean isDay = true;
            try {
                String query = "api.open-meteo.com/v1/forecast?latitude=" + latitude
                        + "&longitude=" + longitude + "&current=temperature_2m,weather_code,is_day&temperature_unit=celsius";
                JSONObject json = requestWeatherJson(query);
                double temperature;
                if (json.has("current") && json.getJSONObject("current").has("temperature_2m")) {
                    JSONObject current = json.getJSONObject("current");
                    temperature = current.getDouble("temperature_2m");
                    weatherCode = current.optInt("weather_code", -1);
                    isDay = current.optInt("is_day", 1) == 1;
                } else {
                    String fallbackQuery = "api.open-meteo.com/v1/forecast?latitude=" + latitude
                            + "&longitude=" + longitude + "&current_weather=true&temperature_unit=celsius";
                    JSONObject fallback = requestWeatherJson(fallbackQuery);
                    JSONObject currentWeather = fallback.getJSONObject("current_weather");
                    temperature = currentWeather.getDouble("temperature");
                    weatherCode = currentWeather.optInt("weathercode", -1);
                    isDay = currentWeather.optInt("is_day", 1) == 1;
                }
                value = Math.round(temperature) + "°C";
            } catch (Exception exception) {
            }
            WeatherSnapshot snapshot = new WeatherSnapshot(value, weatherCode, isDay);
            if (destroyed) return;
            handler.post(() -> {
                if (!destroyed) {
                    callback.onWeather(snapshot);
                }
            });
        });
    }

    private JSONObject requestWeatherJson(String query) throws Exception {
        try {
            return requestJson("https://" + query);
        } catch (javax.net.ssl.SSLHandshakeException sslException) {
            return requestJson("http://" + query);
        }
    }

    private JSONObject requestJson(String urlString) throws Exception {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DualFlipClock/1.0");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code + " for " + urlString);
            }
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                if (builder.length() > MAX_WEATHER_RESPONSE_CHARS) {
                    throw new java.io.IOException("Weather response too large");
                }
            }
            return new JSONObject(builder.toString());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void showPicker(boolean primary) {
        if (activePicker != null) {
            return;
        }
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(238, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setFocusableInTouchMode(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(58, 36, 34, 24);
        layout.setFocusableInTouchMode(true);
        overlay.addView(layout, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(Color.argb(255, 0, 0, 0));
        layout.addView(controls, new LinearLayout.LayoutParams(-1, 106));

        EditText search = new EditText(this);
        SpannableString searchHint = new SpannableString("搜索城市，大学或高中");
        searchHint.setSpan(new AbsoluteSizeSpan(18, true), 0, searchHint.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        search.setHint(searchHint);
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.GRAY);
        search.setTextSize(26f);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(18, 0, 18, 0);
        search.setBackground(controlBackground(Color.rgb(24, 24, 24), Color.rgb(132, 132, 132), 2f, 6f));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, 82, 1);
        searchParams.setMargins(0, 8, 18, 16);
        controls.addView(search, searchParams);

        if (!primary) {
            Button gpsButton = new Button(this);
            gpsButton.setText("GPS");
            gpsButton.setTextColor(Color.WHITE);
            gpsButton.setTextSize(18f);
            gpsButton.setAllCaps(false);
            gpsButton.setPadding(0, 0, 0, 0);
            gpsButton.setBackground(controlBackground(Color.rgb(38, 38, 38), Color.rgb(132, 132, 132), 2f, 6f));
            LinearLayout.LayoutParams gpsParams = new LinearLayout.LayoutParams(112, 66);
            gpsParams.setMargins(0, 12, 12, 18);
            controls.addView(gpsButton, gpsParams);
            gpsButton.setOnClickListener(v -> {
                secondaryCity = null;
                preferences.edit().remove("secondaryCity").apply();
                requestLocation();
                closePicker(search);
            });
        }

        Button doneButton = new Button(this);
        doneButton.setText("完成");
        doneButton.setTextColor(Color.WHITE);
        doneButton.setTextSize(18f);
        doneButton.setAllCaps(false);
        doneButton.setPadding(0, 0, 0, 0);
        doneButton.setBackground(controlBackground(Color.rgb(38, 38, 38), Color.rgb(132, 132, 132), 2f, 6f));
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(124, 66);
        doneParams.setMargins(0, 12, 0, 18);
        controls.addView(doneButton, doneParams);
        doneButton.setOnClickListener(v -> closePicker(search));

        LinearLayout layoutModeRow = new LinearLayout(this);
        layoutModeRow.setOrientation(LinearLayout.HORIZONTAL);
        layoutModeRow.setGravity(Gravity.CENTER_VERTICAL);
        layoutModeRow.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams modeRowParams = new LinearLayout.LayoutParams(-1, 74);
        modeRowParams.setMargins(0, 2, 0, 4);
        layout.addView(layoutModeRow, modeRowParams);

        Button stackedButton = new Button(this);
        stackedButton.setText("上下");
        stackedButton.setTextColor(Color.WHITE);
        stackedButton.setTextSize(18f);
        stackedButton.setAllCaps(false);
        stackedButton.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams stackedParams = new LinearLayout.LayoutParams(0, 58, 1);
        stackedParams.setMargins(0, 4, 8, 8);
        layoutModeRow.addView(stackedButton, stackedParams);

        Button sideButton = new Button(this);
        sideButton.setText("左右");
        sideButton.setTextColor(Color.WHITE);
        sideButton.setTextSize(18f);
        sideButton.setAllCaps(false);
        sideButton.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(0, 58, 1);
        sideParams.setMargins(8, 4, 0, 8);
        layoutModeRow.addView(sideButton, sideParams);

        updateLayoutModeButtons(stackedButton, sideButton);
        stackedButton.setOnClickListener(v -> {
            setLayoutMode(false);
            updateLayoutModeButtons(stackedButton, sideButton);
        });
        sideButton.setOnClickListener(v -> {
            setLayoutMode(true);
            updateLayoutModeButtons(stackedButton, sideButton);
        });

        ListView listView = new ListView(this);
        listView.setBackgroundColor(Color.rgb(10, 10, 10));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1);
        listParams.setMargins(0, 14, 0, 0);
        layout.addView(listView, listParams);

        List<City> current = new ArrayList<>(CityData.CITIES);
        CityAdapter adapter = new CityAdapter(this, current);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            City city = current.get(position);
            if (primary) {
                primaryCity = city;
                preferences.edit().putString("primaryCity", city.id).apply();
            } else {
                secondaryCity = city;
                preferences.edit().putString("secondaryCity", city.id).apply();
            }
            refreshTemperatures();
            closePicker(search);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                current.clear();
                current.addAll(CityData.search(s.toString()));
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        activePicker = overlay;
        rootLayout.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.requestFocus();
    }

    private GradientDrawable controlBackground(int fillColor, int strokeColor, float strokeWidth, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setStroke(Math.round(strokeWidth), strokeColor);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void setLayoutMode(boolean sideBySide) {
        useSideBySideLayout = sideBySide;
        preferences.edit().putString(PREF_LAYOUT_MODE, sideBySide ? LAYOUT_SIDE_BY_SIDE : LAYOUT_STACKED).apply();
        if (clockSurface != null) {
            clockSurface.postInvalidateOnAnimation();
        }
    }

    private void updateLayoutModeButtons(Button stackedButton, Button sideButton) {
        applyLayoutButtonStyle(stackedButton, !useSideBySideLayout);
        applyLayoutButtonStyle(sideButton, useSideBySideLayout);
    }

    private void applyLayoutButtonStyle(Button button, boolean selected) {
        button.setTextColor(selected ? Color.BLACK : Color.WHITE);
        button.setBackground(controlBackground(
                selected ? Color.WHITE : Color.rgb(38, 38, 38),
                selected ? Color.WHITE : Color.rgb(132, 132, 132),
                2f,
                6f
        ));
    }

    private void closePicker(View focusView) {
        hideKeyboard(focusView);
        if (activePicker != null && rootLayout != null) {
            rootLayout.removeView(activePicker);
            activePicker = null;
        }
        hideSystemUi();
        clockSurface.postInvalidateOnAnimation();
    }

    private void hideKeyboard(View view) {
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null && view != null) {
            input.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private interface WeatherCallback {
        void onWeather(WeatherSnapshot weather);
    }

    private static final class WeatherSnapshot {
        final String temperature;
        final int code;
        final boolean isDay;

        WeatherSnapshot(String temperature, int code, boolean isDay) {
            this.temperature = temperature;
            this.code = code;
            this.isDay = isDay;
        }

        static WeatherSnapshot unknown() {
            return new WeatherSnapshot("--°C", -1, true);
        }
    }

    private final class ClockSurface extends View {
        private final float[] starX = new float[STAR_COUNT];
        private final float[] starY = new float[STAR_COUNT];
        private final float[] starRadius = new float[STAR_COUNT];
        private final int[] starAlpha = new int[STAR_COUNT];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Camera flipCamera = new Camera();
        private final Matrix flipMatrix = new Matrix();
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HHmmss", Locale.US);
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd EEEE", Locale.SIMPLIFIED_CHINESE);
        private final Date drawDate = new Date();
        private final RectF rect = new RectF();
        private final RectF topRect = new RectF();
        private final RectF bottomRect = new RectF();
        private final RectF tempRect = new RectF();
        private final RectF tempRect2 = new RectF();
        private final Path iconPath = new Path();
        private final char[] lastDigits = {' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' '};
        private final char[] previousDigits = {' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' '};
        private final long[] changedAt = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        private float touchLeft = 0f;
        private float touchRight = 0f;
        private float primaryTop = 0f;
        private float primaryBottom = 0f;
        private float secondaryTop = 0f;
        private float secondaryBottom = 0f;
        private boolean sideBySideMode = false;

        ClockSurface(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
            for (int i = 0; i < STAR_COUNT; i++) {
                starX[i] = ((i * 37) % 1000) / 1000f;
                starY[i] = ((i * 83 + 19) % 1000) / 1000f;
                starRadius[i] = 0.9f + ((i * 11) % 22) / 10f;
                starAlpha[i] = 30 + (i * 17) % 80;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = System.currentTimeMillis();
            drawStars(canvas);

            TimeZone secondaryZone = secondaryCity == null ? gpsTimeZone : secondaryCity.zone;
            String secondaryTitle = secondaryCity == null ? gpsCityName : secondaryCity.displayName;
            boolean secondaryDimmed = shouldDim(Calendar.getInstance(secondaryZone));

            if (useSideBySideLayout) {
                sideBySideMode = true;
                drawSideBySide(canvas, now, secondaryZone, secondaryTitle, secondaryDimmed);
            } else {
                sideBySideMode = false;
                drawStacked(canvas, now, secondaryZone, secondaryTitle, secondaryDimmed);
            }
            if (hasActiveFlip(now)) {
                postInvalidateOnAnimation();
            }
        }

        private void drawStacked(Canvas canvas, long now, TimeZone secondaryZone, String secondaryTitle, boolean secondaryDimmed) {
            float targetWidth = getWidth() * 0.80f;
            float targetHeight = getHeight() * 0.80f;
            float rowGap = Math.max(28f, targetHeight * 0.055f);
            float labelWidth = Math.min(Math.max(targetWidth * 0.19f, 170f), targetWidth * 0.26f);
            float labelClockGap = 32f;
            float cardWidthFromWidth = (targetWidth - labelWidth - labelClockGap - 74f) / 6f;
            float cardHeightFromHeight = (targetHeight - rowGap) / 2f;
            float cardWidth = Math.max(52f, Math.min(cardWidthFromWidth, cardHeightFromHeight / 1.36f));
            float cardHeight = cardWidth * 1.36f;
            float actualWidth = labelWidth + labelClockGap + 48f + cardWidth * 6f + 50f;
            float actualHeight = cardHeight * 2f + rowGap;
            float startX = (getWidth() - actualWidth) / 2f;
            float startY = (getHeight() - actualHeight) / 2f;
            touchLeft = startX;
            touchRight = startX + actualWidth;
            primaryTop = startY;
            primaryBottom = startY + cardHeight;
            secondaryTop = startY + cardHeight + rowGap;
            secondaryBottom = secondaryTop + cardHeight;

            drawRow(canvas, primaryCity.displayName, primaryCity.zone, primaryTemp, primaryWeather, startX, startY, labelWidth, labelClockGap, cardWidth, cardHeight, now, true, secondaryDimmed);
            drawRow(canvas, secondaryTitle, secondaryZone, secondaryTemp, secondaryWeather, startX, startY + cardHeight + rowGap, labelWidth, labelClockGap, cardWidth, cardHeight, now, false, secondaryDimmed);
        }

        private void drawSideBySide(Canvas canvas, long now, TimeZone secondaryZone, String secondaryTitle, boolean secondaryDimmed) {
            float dividerX = getWidth() / 2f;
            float outerPad = Math.max(34f, getWidth() * 0.045f);
            float middleGap = Math.max(42f, getWidth() * 0.045f);
            float panelWidth = (getWidth() - outerPad * 2f - middleGap) / 2f;
            float panelHeight = getHeight() * 0.74f;
            float panelTop = (getHeight() - panelHeight) / 2f;
            float leftX = outerPad;
            float rightX = dividerX + middleGap / 2f;

            touchLeft = 0f;
            touchRight = getWidth();
            primaryTop = 0f;
            primaryBottom = getHeight();
            secondaryTop = 0f;
            secondaryBottom = getHeight();

            drawCenterDivider(canvas, dividerX, panelTop - getHeight() * 0.04f, panelTop + panelHeight + getHeight() * 0.04f);
            drawColumn(canvas, primaryCity.displayName, primaryCity.zone, primaryTemp, primaryWeather, leftX, panelTop, panelWidth, panelHeight, now, true, secondaryDimmed);
            drawColumn(canvas, secondaryTitle, secondaryZone, secondaryTemp, secondaryWeather, rightX, panelTop, panelWidth, panelHeight, now, false, secondaryDimmed);
        }

        private void drawCenterDivider(Canvas canvas, float x, float top, float bottom) {
            paint.setShader(new LinearGradient(x, top, x, bottom,
                    new int[]{
                            Color.argb(0, 255, 255, 255),
                            Color.argb(135, 255, 255, 255),
                            Color.argb(190, 255, 255, 255),
                            Color.argb(135, 255, 255, 255),
                            Color.argb(0, 255, 255, 255)
                    },
                    new float[]{0f, 0.24f, 0.50f, 0.76f, 1f},
                    Shader.TileMode.CLAMP));
            paint.setStrokeWidth(2.6f);
            canvas.drawLine(x, top, x, bottom, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(52, 255, 255, 255));
            paint.setStrokeWidth(8f);
            canvas.drawLine(x, top + 12f, x, bottom - 12f, paint);
        }

        private void drawColumn(Canvas canvas, String title, TimeZone zone, String temp, WeatherSnapshot weather, float x, float y, float width, float height, long now, boolean primary, boolean forceDimmed) {
            Calendar calendar = Calendar.getInstance(zone);
            boolean dimmed = forceDimmed || (!primary && shouldDim(calendar));
            int color = dimmed ? Color.rgb(72, 72, 72) : Color.WHITE;
            drawDate.setTime(now);
            String digits = timeFormatFor(zone).format(drawDate).substring(0, 4);
            String date = dateWithTemperature(zone, temp, now);

            float centerX = x + width / 2f;
            float titleSize = clamp(height * 0.105f, 26f, 52f);
            float detailSize = clamp(height * 0.080f, 20f, 42f);
            float cardsTop = y + height * 0.27f;
            float maxCardHeight = height * 0.45f;
            float cardGap = Math.max(10f, width * 0.025f);
            float minuteGap = Math.max(18f, width * 0.038f);
            float cardW = Math.min((width - cardGap * 2f - minuteGap) / 4f, maxCardHeight / 1.36f);
            cardW = Math.max(46f, cardW);
            float cardH = cardW * 1.36f;
            float actualClockWidth = cardW * 4f + cardGap * 2f + minuteGap;
            float digitX = centerX - actualClockWidth / 2f;
            int digitOffset = primary ? 0 : 6;

            textPaint.setColor(withAlpha(color, 220));
            textPaint.setTextSize(titleSize);
            drawCenteredTextWithWeatherIcon(canvas, title, centerX, y + height * 0.14f, width * 0.88f, titleSize * 1.38f, weather, textPaint, color, 0.50f);

            for (int i = 0; i < 4; i++) {
                char current = digits.charAt(i);
                if (lastDigits[i + digitOffset] != current) {
                    previousDigits[i + digitOffset] = lastDigits[i + digitOffset] == ' ' ? current : lastDigits[i + digitOffset];
                    lastDigits[i + digitOffset] = current;
                    changedAt[i + digitOffset] = now;
                }
                float progress = Math.min(1f, (now - changedAt[i + digitOffset]) / FLIP_DURATION_MS);
                drawFlipDigit(canvas, String.valueOf(current), String.valueOf(previousDigits[i + digitOffset]), digitX, cardsTop, cardW, cardH, color, progress, dimmed);
                digitX += cardW + (i == 1 ? minuteGap : cardGap);
            }

            textPaint.setColor(withAlpha(color, 120));
            textPaint.setTextSize(detailSize);
            fitCenteredText(canvas, date, centerX, y + height * 0.84f, width * 0.86f, textPaint, 0.48f);
        }

        private boolean hasActiveFlip(long now) {
            for (long changed : changedAt) {
                if (changed > 0L && now - changed < FLIP_DURATION_MS) {
                    return true;
                }
            }
            return false;
        }

        private void drawStars(Canvas canvas) {
            paint.setColor(Color.BLACK);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(Color.WHITE);
            int width = getWidth();
            int height = getHeight();
            for (int i = 0; i < STAR_COUNT; i++) {
                paint.setAlpha(starAlpha[i]);
                canvas.drawCircle(starX[i] * width, starY[i] * height, starRadius[i], paint);
            }
            paint.setAlpha(255);
        }

        private void drawRow(Canvas canvas, String title, TimeZone zone, String temp, WeatherSnapshot weather, float x, float y, float labelWidth, float labelClockGap, float cardW, float cardH, long now, boolean primary, boolean forceDimmed) {
            Calendar calendar = Calendar.getInstance(zone);
            boolean dimmed = forceDimmed || (!primary && shouldDim(calendar));
            int color = dimmed ? Color.rgb(64, 64, 64) : Color.WHITE;
            drawDate.setTime(now);
            String digits = timeFormatFor(zone).format(drawDate);
            String date = dateWithTemperature(zone, temp, now);
            float titleSize = clamp(cardH * 0.34f, 26f, 52f);
            float detailSize = clamp(cardH * 0.27f, 20f, 42f);
            float titleY = y + cardH * 0.25f;
            float dateY = y + cardH * 0.67f;

            textPaint.setColor(withAlpha(color, 220));
            textPaint.setTextSize(titleSize);
            drawTextWithWeatherIcon(canvas, title, x, titleY, labelWidth, titleSize * 1.16f, weather, textPaint, color, 0.50f);

            textPaint.setColor(withAlpha(color, 112));
            textPaint.setTextSize(detailSize);
            fitText(canvas, date, x, dateY, labelWidth, textPaint, 0.42f);

            float digitX = x + labelWidth + labelClockGap;
            int digitOffset = primary ? 0 : 6;
            for (int i = 0; i < 6; i++) {
                if (i == 2 || i == 4) {
                    drawColon(canvas, digitX, y, cardH, color);
                    digitX += 24f;
                }

                char current = digits.charAt(i);
                if (lastDigits[i + digitOffset] != current) {
                    previousDigits[i + digitOffset] = lastDigits[i + digitOffset] == ' ' ? current : lastDigits[i + digitOffset];
                    lastDigits[i + digitOffset] = current;
                    changedAt[i + digitOffset] = now;
                }
                float progress = Math.min(1f, (now - changedAt[i + digitOffset]) / FLIP_DURATION_MS);
                drawFlipDigit(canvas, String.valueOf(current), String.valueOf(previousDigits[i + digitOffset]), digitX, y, cardW, cardH, color, progress, dimmed);
                digitX += cardW + 10f;
            }
        }

        private SimpleDateFormat timeFormatFor(TimeZone zone) {
            timeFormat.setTimeZone(zone);
            return timeFormat;
        }

        private SimpleDateFormat dateFormatFor(TimeZone zone) {
            dateFormat.setTimeZone(zone);
            return dateFormat;
        }

        private String dateWithTemperature(TimeZone zone, String temp, long now) {
            drawDate.setTime(now);
            return dateFormatFor(zone).format(drawDate) + " " + temp;
        }

        private boolean shouldDim(Calendar calendar) {
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            return hour >= 23 || hour < 7;
        }

        private void fitText(Canvas canvas, String text, float x, float y, float maxWidth, Paint paint) {
            fitText(canvas, text, x, y, maxWidth, paint, 0.58f);
        }

        private void fitText(Canvas canvas, String text, float x, float y, float maxWidth, Paint paint, float minScale) {
            float original = paint.getTextSize();
            float minimum = Math.max(10f, original * minScale);
            while (paint.measureText(text) > maxWidth && paint.getTextSize() > minimum) {
                paint.setTextSize(paint.getTextSize() - 1f);
            }
            canvas.drawText(text, x, y, paint);
            paint.setTextSize(original);
        }

        private void fitCenteredText(Canvas canvas, String text, float centerX, float y, float maxWidth, Paint paint, float minScale) {
            float original = paint.getTextSize();
            float minimum = Math.max(10f, original * minScale);
            while (paint.measureText(text) > maxWidth && paint.getTextSize() > minimum) {
                paint.setTextSize(paint.getTextSize() - 1f);
            }
            String visible = ellipsize(text, maxWidth, paint);
            canvas.drawText(visible, centerX - paint.measureText(visible) / 2f, y, paint);
            paint.setTextSize(original);
        }

        private void drawTextWithWeatherIcon(Canvas canvas, String text, float x, float y, float maxWidth, float iconSize, WeatherSnapshot weather, Paint paint, int color, float minScale) {
            float original = paint.getTextSize();
            float gap = Math.max(8f, iconSize * 0.24f);
            float textWidth = Math.max(28f, maxWidth - iconSize - gap);
            float minimum = Math.max(10f, original * minScale);
            while (paint.measureText(text) > textWidth && paint.getTextSize() > minimum) {
                paint.setTextSize(paint.getTextSize() - 1f);
            }
            String visible = ellipsize(text, textWidth, paint);
            canvas.drawText(visible, x, y, paint);
            float iconX = x + Math.min(paint.measureText(visible) + gap, maxWidth - iconSize);
            drawWeatherIcon(canvas, iconX, y - iconSize * 0.78f, iconSize, weather, color);
            paint.setTextSize(original);
        }

        private void drawCenteredTextWithWeatherIcon(Canvas canvas, String text, float centerX, float y, float maxWidth, float iconSize, WeatherSnapshot weather, Paint paint, int color, float minScale) {
            float original = paint.getTextSize();
            float gap = Math.max(12f, iconSize * 0.36f);
            float textWidth = Math.max(28f, maxWidth - iconSize - gap);
            float minimum = Math.max(10f, original * minScale);
            while (paint.measureText(text) > textWidth && paint.getTextSize() > minimum) {
                paint.setTextSize(paint.getTextSize() - 1f);
            }
            String visible = ellipsize(text, textWidth, paint);
            float groupWidth = paint.measureText(visible) + gap + iconSize;
            float textX = centerX - groupWidth / 2f;
            canvas.drawText(visible, textX, y, paint);
            drawWeatherIcon(canvas, textX + paint.measureText(visible) + gap, y - iconSize * 0.78f, iconSize, weather, color);
            paint.setTextSize(original);
        }

        private void drawTemperatureWithIcon(Canvas canvas, String text, float x, float y, float maxWidth, float iconSize, WeatherSnapshot weather, Paint paint, int color, float minScale) {
            float original = paint.getTextSize();
            float gap = Math.max(7f, iconSize * 0.22f);
            float textX = x + iconSize + gap;
            float textWidth = Math.max(28f, maxWidth - iconSize - gap);
            float minimum = Math.max(10f, original * minScale);
            while (paint.measureText(text) > textWidth && paint.getTextSize() > minimum) {
                paint.setTextSize(paint.getTextSize() - 1f);
            }
            drawWeatherIcon(canvas, x, y - iconSize * 0.78f, iconSize, weather, color);
            canvas.drawText(ellipsize(text, textWidth, paint), textX, y, paint);
            paint.setTextSize(original);
        }

        private void drawCenteredTemperatureWithIcon(Canvas canvas, String text, float centerX, float y, float maxWidth, float iconSize, WeatherSnapshot weather, Paint paint, int color, float minScale) {
            float original = paint.getTextSize();
            float gap = Math.max(7f, iconSize * 0.22f);
            float textWidth = Math.max(28f, maxWidth - iconSize - gap);
            float minimum = Math.max(10f, original * minScale);
            while (paint.measureText(text) > textWidth && paint.getTextSize() > minimum) {
                paint.setTextSize(paint.getTextSize() - 1f);
            }
            String visible = ellipsize(text, textWidth, paint);
            float groupWidth = iconSize + gap + paint.measureText(visible);
            float iconX = centerX - groupWidth / 2f;
            drawWeatherIcon(canvas, iconX, y - iconSize * 0.78f, iconSize, weather, color);
            canvas.drawText(visible, iconX + iconSize + gap, y, paint);
            paint.setTextSize(original);
        }

        private String ellipsize(String text, float maxWidth, Paint paint) {
            if (paint.measureText(text) <= maxWidth) return text;
            String ellipsis = "...";
            float ellipsisWidth = paint.measureText(ellipsis);
            int keep = paint.breakText(text, true, Math.max(0f, maxWidth - ellipsisWidth), null);
            if (keep <= 0) return ellipsis;
            return text.substring(0, Math.min(keep, text.length())) + ellipsis;
        }

        private void drawWeatherIcon(Canvas canvas, float x, float y, float size, WeatherSnapshot weather, int baseColor) {
            int code = weather == null ? -1 : weather.code;
            boolean day = weather == null || weather.isDay;
            float alphaScale = Color.alpha(baseColor) / 255f;
            boolean storm = code >= 95;
            boolean snow = code >= 71 && code <= 86;
            boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
            boolean fog = code == 45 || code == 48;
            boolean cloudy = code == 2 || code == 3 || fog || rain || snow || storm;
            boolean partly = code == 1 || code == 2;

            if (!cloudy && !rain && !snow && !storm && !fog) {
                if (day) drawSun(canvas, x, y, size, alphaScale); else drawMoon(canvas, x, y, size, alphaScale, true);
                return;
            }
            if (partly) {
                if (day) drawSun(canvas, x + size * 0.05f, y, size * 0.62f, alphaScale);
                else drawMoon(canvas, x + size * 0.18f, y - size * 0.02f, size * 0.62f, alphaScale, true);
            }
            drawCloud(canvas, x + size * 0.08f, y + size * 0.32f, size * 0.86f, alphaScale);
            if (rain || storm) drawRain(canvas, x + size * 0.18f, y + size * 0.78f, size * 0.70f, alphaScale);
            if (snow) drawSnow(canvas, x + size * 0.15f, y + size * 0.80f, size * 0.72f, alphaScale);
            if (storm) drawLightning(canvas, x + size * 0.42f, y + size * 0.60f, size * 0.33f, alphaScale);
            if (fog) drawFog(canvas, x + size * 0.12f, y + size * 0.82f, size * 0.76f, alphaScale);
        }

        private void drawSun(Canvas canvas, float x, float y, float size, float alphaScale) {
            paint.setColor(Color.argb((int) (230 * alphaScale), 255, 197, 44));
            canvas.drawCircle(x + size * 0.50f, y + size * 0.50f, size * 0.25f, paint);
            paint.setStrokeWidth(Math.max(2f, size * 0.07f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2.0 * i / 8.0;
                float cx = x + size * 0.50f;
                float cy = y + size * 0.50f;
                canvas.drawLine(cx + (float) Math.cos(angle) * size * 0.35f, cy + (float) Math.sin(angle) * size * 0.35f,
                        cx + (float) Math.cos(angle) * size * 0.45f, cy + (float) Math.sin(angle) * size * 0.45f, paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawMoon(Canvas canvas, float x, float y, float size, float alphaScale, boolean yellow) {
            paint.setColor(yellow ? Color.argb((int) (230 * alphaScale), 255, 197, 44) : Color.argb((int) (230 * alphaScale), 190, 194, 232));
            canvas.drawCircle(x + size * 0.48f, y + size * 0.48f, size * 0.30f, paint);
            paint.setColor(Color.BLACK);
            canvas.drawCircle(x + size * 0.60f, y + size * 0.38f, size * 0.31f, paint);
        }

        private void drawCloud(Canvas canvas, float x, float y, float size, float alphaScale) {
            paint.setColor(Color.argb((int) (235 * alphaScale), 238, 241, 250));
            canvas.drawCircle(x + size * 0.28f, y + size * 0.50f, size * 0.20f, paint);
            canvas.drawCircle(x + size * 0.48f, y + size * 0.36f, size * 0.26f, paint);
            canvas.drawCircle(x + size * 0.70f, y + size * 0.50f, size * 0.20f, paint);
            tempRect.set(x + size * 0.16f, y + size * 0.48f, x + size * 0.84f, y + size * 0.72f);
            canvas.drawRoundRect(tempRect, size * 0.10f, size * 0.10f, paint);
        }

        private void drawRain(Canvas canvas, float x, float y, float size, float alphaScale) {
            paint.setColor(Color.argb((int) (230 * alphaScale), 93, 181, 235));
            paint.setStrokeWidth(Math.max(2f, size * 0.08f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 3; i++) {
                float rx = x + size * (0.18f + i * 0.30f);
                canvas.drawLine(rx + size * 0.06f, y, rx - size * 0.03f, y + size * 0.24f, paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawSnow(Canvas canvas, float x, float y, float size, float alphaScale) {
            paint.setColor(Color.argb((int) (235 * alphaScale), 205, 230, 250));
            paint.setStrokeWidth(Math.max(1.4f, size * 0.045f));
            for (int i = 0; i < 3; i++) {
                float cx = x + size * (0.18f + i * 0.31f);
                float cy = y + size * (0.05f + (i % 2) * 0.13f);
                canvas.drawLine(cx - size * 0.08f, cy, cx + size * 0.08f, cy, paint);
                canvas.drawLine(cx, cy - size * 0.08f, cx, cy + size * 0.08f, paint);
                canvas.drawLine(cx - size * 0.055f, cy - size * 0.055f, cx + size * 0.055f, cy + size * 0.055f, paint);
                canvas.drawLine(cx - size * 0.055f, cy + size * 0.055f, cx + size * 0.055f, cy - size * 0.055f, paint);
            }
        }

        private void drawLightning(Canvas canvas, float x, float y, float size, float alphaScale) {
            paint.setColor(Color.argb((int) (240 * alphaScale), 255, 203, 40));
            iconPath.reset();
            iconPath.moveTo(x + size * 0.50f, y);
            iconPath.lineTo(x + size * 0.20f, y + size * 0.52f);
            iconPath.lineTo(x + size * 0.48f, y + size * 0.48f);
            iconPath.lineTo(x + size * 0.28f, y + size);
            iconPath.lineTo(x + size * 0.84f, y + size * 0.34f);
            iconPath.lineTo(x + size * 0.55f, y + size * 0.38f);
            iconPath.close();
            canvas.drawPath(iconPath, paint);
        }

        private void drawFog(Canvas canvas, float x, float y, float size, float alphaScale) {
            paint.setColor(Color.argb((int) (180 * alphaScale), 205, 213, 226));
            paint.setStrokeWidth(Math.max(2f, size * 0.06f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 3; i++) {
                float fy = y + i * size * 0.16f;
                canvas.drawLine(x, fy, x + size * (0.74f - i * 0.10f), fy, paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private void drawColon(Canvas canvas, float x, float y, float height, int color) {
            paint.setColor(withAlpha(color, 220));
            canvas.drawCircle(x + 10f, y + height * 0.38f, 5f, paint);
            canvas.drawCircle(x + 10f, y + height * 0.62f, 5f, paint);
        }

        private void drawFlipDigit(Canvas canvas, String digit, String previousDigit, float x, float y, float w, float h, int color, float progress, boolean dimmed) {
            rect.set(x, y, x + w, y + h);
            float hinge = y + h / 2f;
            topRect.set(x, y, x + w, hinge + 1f);
            bottomRect.set(x, hinge - 1f, x + w, y + h);

            if (progress >= 1f || previousDigit.equals(digit)) {
                drawStaticCard(canvas, rect, x, y, w, h, dimmed);
                drawDigitClipped(canvas, digit, rect, color, x, y, w, h);
                drawCardFrame(canvas, rect, x, y, w, h, dimmed);
                return;
            }

            if (progress < 0.5f) {
                float fold = easeInCubic(progress / 0.5f);
                float angle = -86f * fold;
                drawHalfCard(canvas, rect, bottomRect, previousDigit, color, x, y, w, h, dimmed);
                drawRotatingHalf(canvas, previousDigit, rect, topRect, color, x, y, w, h, x + w / 2f, hinge, angle, dimmed);
                drawSoftShadow(canvas, topRect, (int) (78 * fold), false);
            } else {
                float fold = easeOutCubic((progress - 0.5f) / 0.5f);
                float angle = 86f * (1f - fold);
                drawHalfCard(canvas, rect, topRect, digit, color, x, y, w, h, dimmed);
                drawRotatingHalf(canvas, digit, rect, bottomRect, color, x, y, w, h, x + w / 2f, hinge, angle, dimmed);
                drawSoftShadow(canvas, bottomRect, (int) (76 * (1f - fold)), true);
            }
            drawCardFrame(canvas, rect, x, y, w, h, dimmed);
        }

        private void drawStaticCard(Canvas canvas, RectF rect, float x, float y, float w, float h, boolean dimmed) {
            drawCardBase(canvas, rect, x, y, w, h, true, dimmed);
            drawCardFrame(canvas, rect, x, y, w, h, dimmed);
        }

        private void drawAnimatedCard(Canvas canvas, RectF rect, float x, float y, float w, float h, boolean dimmed) {
            drawCardBase(canvas, rect, x, y, w, h, true, dimmed);
        }

        private void drawCardBase(Canvas canvas, RectF rect, float x, float y, float w, float h, boolean includeHighlights, boolean dimmed) {
            drawCardBase(canvas, rect, x, y, w, h, includeHighlights, true, dimmed);
        }

        private void drawCardBase(Canvas canvas, RectF rect, float x, float y, float w, float h, boolean includeHighlights, boolean includeOuterLight, boolean dimmed) {
            float radius = 8f;
            paint.setColor(Color.argb(dimmed ? 108 : 72, 0, 0, 0));
            tempRect.set(x + 1f, y + 7f, x + w + 1f, y + h + 9f);
            canvas.drawRoundRect(tempRect, radius + 1f, radius + 1f, paint);
            if (includeOuterLight) {
                paint.setColor(dimmed ? Color.rgb(5, 5, 6) : Color.rgb(7, 8, 9));
                tempRect.set(x, y, x + w, y + h);
                canvas.drawRoundRect(tempRect, radius, radius, paint);
            }
            paint.setShader(new LinearGradient(0, y, 0, y + h,
                    dimmed ? new int[]{
                            Color.rgb(13, 14, 17),
                            Color.rgb(11, 12, 15),
                            Color.rgb(8, 9, 11),
                            Color.rgb(5, 6, 8),
                            Color.rgb(3, 4, 5)
                    } : new int[]{
                            Color.rgb(20, 21, 25),
                            Color.rgb(16, 17, 20),
                            Color.rgb(13, 14, 17),
                            Color.rgb(9, 10, 12),
                            Color.rgb(6, 7, 9)
                    },
                    new float[]{0f, 0.24f, 0.50f, 0.78f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);

            if (includeHighlights) {
                paint.setShader(new LinearGradient(0, y, 0, y + h * 0.50f,
                        Color.argb(dimmed ? 5 : 13, 255, 255, 255),
                        Color.argb(0, 255, 255, 255),
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setShader(null);
            }
            paint.setColor(Color.argb(dimmed ? 46 : 31, 0, 0, 0));
            canvas.drawRect(x, y + h * 0.50f, x + w, y + h, paint);
            if (includeHighlights) {
                paint.setShader(new LinearGradient(x, y, x + w, y,
                        new int[]{
                                Color.argb(dimmed ? 4 : 12, 255, 255, 255),
                                Color.argb(0, 255, 255, 255),
                                Color.argb(dimmed ? 42 : 34, 0, 0, 0)
                        },
                        new float[]{0f, 0.52f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setShader(null);
            }
        }

        private void drawCardFrame(Canvas canvas, RectF rect, float x, float y, float w, float h, boolean dimmed) {
            float hinge = y + h / 2f;
            float hingeH = clamp(h * 0.035f, 4f, 7f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(dimmed ? 199 : 168, 0, 0, 0));
            canvas.drawRect(x + 3f, hinge - hingeH * 0.21f, x + w - 3f, hinge + hingeH * 0.21f, paint);
            paint.setShader(new LinearGradient(0, hinge - hingeH / 2f, 0, hinge + hingeH / 2f,
                    new int[]{
                            Color.argb(dimmed ? 5 : 36, 255, 255, 255),
                            Color.argb(0, 255, 255, 255),
                            Color.argb(dimmed ? 117 : 87, 0, 0, 0)
                    },
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(x + 3f, hinge - hingeH / 2f, x + w - 3f, hinge + hingeH / 2f, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(dimmed ? 184 : 140, 0, 0, 0));
            float capsuleW = Math.max(7f, hingeH * 1.7f);
            float capsuleH = Math.max(3f, hingeH * 0.72f);
            float capInset = Math.max(5f, hingeH * 1.2f);
            tempRect.set(x + capInset, hinge - capsuleH / 2f, x + capInset + capsuleW, hinge + capsuleH / 2f);
            tempRect2.set(x + w - capInset - capsuleW, hinge - capsuleH / 2f, x + w - capInset, hinge + capsuleH / 2f);
            canvas.drawRoundRect(tempRect, capsuleH / 2f, capsuleH / 2f, paint);
            canvas.drawRoundRect(tempRect2, capsuleH / 2f, capsuleH / 2f, paint);
            paint.setColor(Color.argb(dimmed ? 8 : 31, 255, 255, 255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.7f);
            canvas.drawRoundRect(tempRect, capsuleH / 2f, capsuleH / 2f, paint);
            canvas.drawRoundRect(tempRect2, capsuleH / 2f, capsuleH / 2f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawRotatingHalf(Canvas canvas, String digit, RectF fullRect, RectF clip, int color, float x, float y, float w, float h, float pivotX, float pivotY, float angle, boolean dimmed) {
            flipMatrix.reset();
            flipCamera.save();
            flipCamera.setLocation(0f, 0f, -10f);
            flipCamera.rotateX(angle);
            flipCamera.getMatrix(flipMatrix);
            flipCamera.restore();
            flipMatrix.preTranslate(-pivotX, -pivotY);
            flipMatrix.postTranslate(pivotX, pivotY);

            canvas.save();
            canvas.clipRect(clip);
            canvas.concat(flipMatrix);
            drawAnimatedCard(canvas, fullRect, x, y, w, h, dimmed);
            drawDigitClipped(canvas, digit, fullRect, color, x, y, w, h);
            canvas.restore();
        }

        private void drawSoftShadow(Canvas canvas, RectF rect, int alpha, boolean fromTop) {
            int start = Color.argb(Math.max(0, alpha), 0, 0, 0);
            int end = Color.argb(0, 0, 0, 0);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    fromTop ? start : end,
                    fromTop ? end : start,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
        }

        private void drawHalfCard(Canvas canvas, RectF fullRect, RectF clip, String digit, int color, float x, float y, float w, float h, boolean dimmed) {
            canvas.save();
            canvas.clipRect(clip);
            drawCardBase(canvas, fullRect, x, y, w, h, true, dimmed);
            drawDigitClipped(canvas, digit, fullRect, color, x, y, w, h);
            canvas.restore();
        }

        private float easeOutCubic(float value) {
            float inverse = 1f - value;
            return 1f - inverse * inverse * inverse;
        }

        private float easeInCubic(float value) {
            return value * value * value;
        }

        private void drawDigitClipped(Canvas canvas, String digit, RectF clip, int color, float x, float y, float w, float h) {
            canvas.save();
            canvas.clipRect(clip);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(withAlpha(color, color == Color.WHITE ? 235 : 255));
            textPaint.setTextSize(h * 0.82f);
            textPaint.setFakeBoldText(true);
            textPaint.setShadowLayer(color == Color.WHITE ? 1f : 0f, 0f, 1f, Color.argb(20, 255, 255, 255));
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = y + h / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.save();
            canvas.scale(1f, 1.22f, x + w / 2f, y + h / 2f);
            canvas.drawText(digit, x + w / 2f, baseline, textPaint);
            canvas.restore();
            textPaint.clearShadowLayer();
            textPaint.setFakeBoldText(false);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.restore();
        }

        private int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float x = event.getX();
                float y = event.getY();
                if (sideBySideMode) {
                    if (x < getWidth() / 2f) {
                        showPicker(true);
                    } else {
                        showPicker(false);
                    }
                } else if (x >= touchLeft && x <= touchRight && y >= primaryTop && y <= primaryBottom) {
                    showPicker(true);
                } else if (x >= touchLeft && x <= touchRight && y >= secondaryTop && y <= secondaryBottom) {
                    showPicker(false);
                }
                return true;
            }
            return true;
        }
    }

    private static final class CityAdapter extends ArrayAdapter<City> {
        CityAdapter(Context context, List<City> cities) {
            super(context, android.R.layout.simple_list_item_2, android.R.id.text1, cities);
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            City city = getItem(position);
            TextView text1 = view.findViewById(android.R.id.text1);
            TextView text2 = view.findViewById(android.R.id.text2);
            text1.setText(city.displayName);
            text2.setText(city.englishName + " · " + city.countryName);
            text1.setTextColor(Color.WHITE);
            text2.setTextColor(Color.LTGRAY);
            view.setPadding(28, 10, 12, 10);
            view.setBackgroundColor(Color.rgb(18, 18, 18));
            return view;
        }
    }

    private static final class City {
        final String id;
        final String displayName;
        final String englishName;
        final String countryName;
        final double latitude;
        final double longitude;
        final TimeZone zone;
        final String searchable;
        final String normalizedDisplayName;
        final String normalizedEnglishName;
        final boolean school;

        City(String id, String displayName, String englishName, String countryName, double latitude, double longitude, String zoneId) {
            this.id = id;
            this.displayName = displayName;
            this.englishName = englishName;
            this.countryName = countryName;
            this.latitude = latitude;
            this.longitude = longitude;
            this.zone = TimeZone.getTimeZone(zoneId);
            this.searchable = normalize(displayName + " " + englishName + " " + countryName + " " + aliases(id, englishName));
            this.normalizedDisplayName = CityData.normalizePlace(displayName);
            this.normalizedEnglishName = CityData.normalizePlace(englishName);
            this.school = id.startsWith("uni-") || id.startsWith("hs-");
        }

        private static String normalize(String text) {
            return text.toLowerCase(Locale.US).replace("-", " ");
        }

        private static String aliases(String id, String englishName) {
            if (!id.startsWith("uni-") && !id.startsWith("hs-")) return "";
            String shortId = id.startsWith("uni-") ? id.substring(4) : id.substring(3);
            return shortId + " " + shortId.replace("-", "") + " " + acronym(englishName);
        }

        private static String acronym(String text) {
            Set<String> stop = new HashSet<>();
            stop.add("and");
            stop.add("at");
            stop.add("for");
            stop.add("in");
            stop.add("of");
            stop.add("the");

            StringBuilder builder = new StringBuilder();
            for (String part : text.split("[^A-Za-z0-9]+")) {
                if (part.length() == 0 || stop.contains(part.toLowerCase(Locale.US))) continue;
                builder.append(part.charAt(0));
            }
            return builder.toString();
        }
    }

    private static final class CityData {
        static final List<City> CITIES = new ArrayList<>();
        private static final Map<String, City> CITIES_BY_ID = new HashMap<>();
        private static final List<City> PLACE_CITIES = new ArrayList<>();
        private static final Comparator<City> PICKER_ORDER = (left, right) -> {
            int category = Integer.compare(categoryRank(left), categoryRank(right));
            if (category != 0) return category;
            int english = left.englishName.compareToIgnoreCase(right.englishName);
            if (english != 0) return english;
            return left.id.compareTo(right.id);
        };

        static {
            city("us-monterey", "Monterey", "Monterey", "美国", 36.6002, -121.8947, "America/Los_Angeles");
        }

        static City monterey() {
            City city = CITIES_BY_ID.get("us-monterey");
            return city == null ? CITIES.get(0) : city;
        }

        static City find(String id) {
            City city = findNullable(id);
            return city == null ? monterey() : city;
        }

        static City findNullable(String id) {
            if (id == null || id.length() == 0) return null;
            return CITIES_BY_ID.get(id);
        }

        static City matchKnownCity(String rawName) {
            if (rawName == null || rawName.trim().length() == 0) return null;
            String normalized = normalizePlace(rawName);
            for (City city : PLACE_CITIES) {
                if (normalized.equals(city.normalizedDisplayName)
                        || normalized.equals(city.normalizedEnglishName)) {
                    return city;
                }
            }
            for (City city : PLACE_CITIES) {
                if (city.normalizedDisplayName.length() > 1 && normalized.contains(city.normalizedDisplayName)) return city;
                if (city.normalizedEnglishName.length() > 2 && normalized.contains(city.normalizedEnglishName)) return city;
            }
            return null;
        }

        static List<City> search(String query) {
            String normalized = query == null ? "" : query.toLowerCase(Locale.US).replace("-", " ").trim();
            if (normalized.length() == 0) return new ArrayList<>(CITIES);
            List<City> result = new ArrayList<>();
            for (City city : CITIES) {
                if (city.searchable.contains(normalized)) result.add(city);
            }
            return result;
        }

        private static void city(String id, String displayName, String englishName, String countryName, double latitude, double longitude, String zoneId) {
            addCity(new City(id, displayName, englishName, countryName, latitude, longitude, zoneId));
        }

        private static void addCity(City city) {
            CITIES.add(city);
            CITIES_BY_ID.put(city.id, city);
            if (!city.school) {
                PLACE_CITIES.add(city);
            }
        }

        private static String normalizePlace(String text) {
            String normalized = text.toLowerCase(Locale.US)
                    .replace("市", "")
                    .replace("区", "")
                    .replace("縣", "")
                    .replace("县", "")
                    .replace("特别行政", "")
                    .replace("special administrative region", "")
                    .replace("city", "")
                    .replace(".", "")
                    .replace("-", " ")
                    .trim();
            return normalized.replaceAll("\\s+", " ");
        }

        private static int categoryRank(City city) {
            return city.school ? 1 : 0;
        }

        static void loadFromAssets(Context context) {
            List<City> loaded = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("cities.tsv")))) {
                String line;
                Set<String> seen = new HashSet<>();
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t");
                    if (parts.length != 7 || seen.contains(parts[0])) continue;
                    seen.add(parts[0]);
                    loaded.add(new City(
                            parts[0],
                            parts[1],
                            parts[2],
                            parts[3],
                            Double.parseDouble(parts[4]),
                            Double.parseDouble(parts[5]),
                        parts[6]
                    ));
                }
                Collections.sort(loaded, PICKER_ORDER);
                CITIES.clear();
                CITIES_BY_ID.clear();
                PLACE_CITIES.clear();
                for (City city : loaded) {
                    addCity(city);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
