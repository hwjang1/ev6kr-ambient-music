package kr.ev6.ambientmusic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;

import hmgapi.client.HmgApi;

/**
 * The app the home tile opens: the switch, what the light is doing now, and the
 * owner's choices. Every change is saved at once; a running light picks it up
 * within a frame.
 */
public final class MainActivity extends Activity {
    static final int BG = 0xff0d1418, PANEL = 0xff17222a, PANEL2 = 0xff22313a, LINE = 0xff2e3f4a;
    static final int TEXT = 0xffe8f0f2, MUTED = 0xff8fa3ab, ACCENT = 0xff23e4c5, WARN = 0xffffb35c;

    private final Handler handler = new Handler();
    private Settings s;
    private Button power;
    private Swatch preview;
    private LevelBar meterBar;
    private TextView stateText, detailText, meterText, micText, driveText, hmgText, carText, linkText, problemText;
    private android.content.BroadcastReceiver carStatus;
    private LinearLayout fixedSection, bandSection, fixedRow, bandRow;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        s = Settings.load(this);
        setContentView(build());
        renderColors();
    }

    @Override
    protected void onResume() {
        super.onResume();
        carStatus = LightService.statusReceiver();
        registerReceiver(carStatus, new android.content.IntentFilter(CarLight.ACTION_STATUS));
        handler.post(tick);
        checkHmgApi();
        askCar();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        unregisterReceiver(carStatus);
        super.onPause();
    }

    /** asks the car to report its light (the answer comes as a broadcast) */
    private void askCar() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new CarLight().connect().requestStatus();
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            carText.setText("차 무드램프: 서비스를 찾을 수 없음 (" + t + ")");
                        }
                    });
                }
            }
        }).start();
    }

    // ---- layout

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        // left: the switch and what the light is doing
        LinearLayout left = column();
        left.setPadding(dp(20), dp(16), dp(20), dp(16));
        left.setBackground(round(PANEL, 14));
        TextView title = text(getString(R.string.app_name), 26, ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);
        left.addView(text("마이크로 듣는 소리에 맞춰 무드램프의 색과 밝기를 바꿉니다", 14, MUTED));
        power = button("켜기", 22);
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LightService.turn(MainActivity.this, !LightService.running);
                handler.postDelayed(tick, 50);
            }
        });
        left.addView(power, lp(-1, dp(72), 0, dp(16), 0, dp(4)));
        left.addView(text("켜 두면 시동을 다시 걸어도 켜진 상태로 돌아옵니다.", 12, MUTED), lp(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout now = row();
        preview = new Swatch(this);
        now.addView(preview, new LinearLayout.LayoutParams(dp(92), dp(92)));
        LinearLayout nowText = column();
        nowText.setPadding(dp(14), 0, 0, 0);
        stateText = text("", 17, TEXT);
        detailText = text("", 14, MUTED);
        nowText.addView(stateText);
        nowText.addView(detailText);
        now.addView(nowText, new LinearLayout.LayoutParams(0, -2, 1));
        left.addView(now);

        meterText = text("마이크 입력", 13, MUTED);
        left.addView(meterText, lp(-1, -2, 0, dp(12), 0, dp(4)));
        meterBar = new LevelBar(this);
        left.addView(meterBar, new LinearLayout.LayoutParams(-1, dp(14)));
        micText = text("", 13, MUTED);
        driveText = text("", 13, MUTED);
        hmgText = text("HMG-API: 확인 중", 13, MUTED);
        carText = text("차 무드램프: 확인 중", 13, MUTED);
        linkText = text("", 14, WARN);
        left.addView(micText, lp(-1, -2, 0, dp(8), 0, 0));
        left.addView(driveText);
        left.addView(carText);
        left.addView(hmgText);
        left.addView(linkText, lp(-1, -2, 0, dp(8), 0, 0));
        problemText = text("", 14, WARN);
        left.addView(problemText, lp(-1, -2, 0, dp(8), 0, 0));
        left.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        left.addView(text("차량 설정 › 실내 무드 조명에서 '드라이브 모드 연동'을 꺼 두어야 색이 바뀝니다. 끄면 원래 색과 밝기로 돌아갑니다. "
                + "Siri·카플레이 통화·음성인식이 시작되면 바로 마이크를 놓고 30초 동안 비워 둡니다.", 12, MUTED));
        Button close = button("닫기", 15);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        left.addView(close, lp(dp(120), dp(48), 0, dp(10), 0, 0));
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        leftScroll.addView(left);
        root.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 4));

        // right: the choices
        LinearLayout right = column();
        right.setPadding(dp(20), dp(8), dp(20), dp(16));
        section(right, "반응 방식", null);
        right.addView(choice(new String[] { "대역별 색 + 밝기", "고정 색 + 밝기", "색만 바꾸기" },
                new String[] { Settings.MODE_BANDS, Settings.MODE_PULSE, Settings.MODE_COLORS }, s.mode, new Pick() {
                    @Override
                    public void picked(String v) {
                        s.mode = v;
                        save();
                        renderColors();
                    }
                }));

        fixedSection = column();
        section(fixedSection, "고정 색", "탭해서 색을 고릅니다. 밝기만 소리에 맞춰 바뀝니다.");
        fixedRow = row();
        fixedSection.addView(fixedRow);
        right.addView(fixedSection);

        bandSection = column();
        section(bandSection, "대역별 색상", "가장 크게 솟는 대역의 색으로 바뀝니다. 탭해서 색을 고릅니다.");
        bandRow = row();
        bandSection.addView(bandRow);
        right.addView(bandSection);

        section(right, "감도", "높을수록 작은 소리에도 밝게 반응합니다.");
        right.addView(slider("감도", 1, 10, s.sensitivity, new Slide() {
            @Override
            public void moved(int v) {
                s.sensitivity = v;
            }
        }));

        section(right, "밝기 범위", "조용할 때는 최소, 클 때는 최대 밝기 (차량 메뉴의 1-10 단계).");
        final SeekBar[] pair = new SeekBar[2];
        LinearLayout minRow = slider("최소", 1, 10, s.minBright, new Slide() {
            @Override
            public void moved(int v) {
                s.minBright = v;
                if (s.maxBright < v) pair[1].setProgress(v - 1);
            }
        });
        LinearLayout maxRow = slider("최대", 1, 10, s.maxBright, new Slide() {
            @Override
            public void moved(int v) {
                s.maxBright = v;
                if (s.minBright > v) pair[0].setProgress(v - 1);
            }
        });
        pair[0] = (SeekBar) minRow.getChildAt(1);
        pair[1] = (SeekBar) maxRow.getChildAt(1);
        right.addView(minRow);
        right.addView(maxRow);

        section(right, "반응 속도", "빠를수록 색이 자주 바뀝니다. 명령은 어느 경우든 초당 7회 이하로 보냅니다.");
        right.addView(choice(new String[] { "느리게", "보통", "빠르게" },
                new String[] { Settings.SPEED_SLOW, Settings.SPEED_NORMAL, Settings.SPEED_FAST }, s.speed, new Pick() {
                    @Override
                    public void picked(String v) {
                        s.speed = v;
                        save();
                    }
                }));

        section(right, "주행 중", "운전에 방해되지 않도록, 움직이는 동안의 동작을 고릅니다. 주행 상태를 모를 때도 이 설정을 따릅니다.");
        right.addView(choice(new String[] { "색만 천천히", "일시정지 (원래 조명)", "그대로" },
                new String[] { Settings.DRIVE_COLORS, Settings.DRIVE_PAUSE, Settings.DRIVE_NORMAL }, s.driving, new Pick() {
                    @Override
                    public void picked(String v) {
                        s.driving = v;
                        save();
                    }
                }));

        section(right, "주행 소음 억제", "달리는 동안 도로 소음이 색을 바꾸지 않도록, 차속에 따라 문턱을 올립니다. 강할수록 음악에도 덜 반응합니다.");
        right.addView(choice(new String[] { "약하게", "보통", "강하게" },
                new String[] { Settings.NOISE_LOW, Settings.NOISE_NORMAL, Settings.NOISE_HIGH }, s.noise, new Pick() {
                    @Override
                    public void picked(String v) {
                        s.noise = v;
                        save();
                    }
                }));

        section(right, "마이크 경로", "소리가 잘 잡히지 않으면 바꿔 보세요.");
        right.addView(choice(new String[] { "기본 마이크", "음성인식 경로" },
                new String[] { Settings.SOURCE_MIC, Settings.SOURCE_VOICE }, s.source, new Pick() {
                    @Override
                    public void picked(String v) {
                        s.source = v;
                        save();
                    }
                }));

        Button reset = button("기본값으로 되돌리기", 15);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Settings.reset(MainActivity.this);
                recreate();
            }
        });
        right.addView(reset, lp(dp(240), dp(52), 0, dp(20), 0, 0));
        right.addView(text("★ 버튼 설정에서 '뮤직 무드램프 켜기/끄기'를 고를 수 있습니다.  v" + version(), 12, MUTED),
                lp(-1, -2, 0, dp(12), 0, 0));
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.addView(right);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 6);
        rp.leftMargin = dp(16);
        rightScroll.setBackground(round(PANEL, 14));
        root.addView(rightScroll, rp);
        return root;
    }

    private void renderColors() {
        boolean pulse = Settings.MODE_PULSE.equals(s.mode);
        fixedSection.setVisibility(pulse ? View.VISIBLE : View.GONE);
        bandSection.setVisibility(pulse ? View.GONE : View.VISIBLE);
        fixedRow.removeAllViews();
        fixedRow.addView(colorCell("고정 색", "", s.fixedColor, -1), new LinearLayout.LayoutParams(dp(150), -2));
        bandRow.removeAllViews();
        for (int i = 0; i < Analyzer.BANDS; i++) {
            String hz = Settings.BAND_LOW[i] + "-" + (Settings.BAND_HIGH[i] >= 1000 ? Settings.BAND_HIGH[i] / 1000 + "k" : "" + Settings.BAND_HIGH[i]) + " Hz";
            bandRow.addView(colorCell(Settings.BAND_NAMES[i], hz, s.bandColors[i], i), new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    /** index -1: the fixed colour */
    private View colorCell(String name, String hz, int rgb, final int index) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(4));
        Swatch sw = new Swatch(this);
        sw.set(0xff000000 | rgb);
        cell.addView(sw, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView n = text(name, 15, TEXT);
        n.setGravity(Gravity.CENTER);
        cell.addView(n, new LinearLayout.LayoutParams(-1, -2));
        if (!hz.isEmpty()) {
            TextView h = text(hz, 12, MUTED);
            h.setGravity(Gravity.CENTER);
            cell.addView(h, new LinearLayout.LayoutParams(-1, -2));
        }
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickColor(name, index < 0 ? s.fixedColor : s.bandColors[index], index);
            }
        });
        return cell;
    }

    private void pickColor(String name, int current, final int index) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(8);
        grid.setPadding(dp(10), dp(10), dp(10), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(grid);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(name + " 색 고르기")
                .setView(scroll).setNegativeButton("취소", null).create();
        for (final int c : Palette.COLORS) {
            Swatch sw = new Swatch(this);
            sw.set(0xff000000 | c);
            sw.selected = c == current;
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = dp(66);
            p.height = dp(44);
            p.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(sw, p);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (index < 0) s.fixedColor = c;
                    else s.bandColors[index] = c;
                    save();
                    renderColors();
                    dialog.dismiss();
                }
            });
        }
        dialog.show();
    }

    // ---- live state

    private void refresh() {
        boolean on = LightService.running;
        power.setText(on ? "켜짐  ·  눌러서 끄기" : "켜기");
        power.setTextColor(on ? BG : TEXT);
        power.setBackground(round(on ? ACCENT : PANEL2, 12));
        int c = LightService.color, b = LightService.bright;
        if (on && c >= 0) {
            int alpha = 70 + (b > 0 ? b : 1) * 185 / 10;
            preview.set(alpha << 24 | c);
        } else {
            preview.set(0xff2a3740);
        }
        stateText.setText(on ? LightService.state : "꺼짐");
        String d = "";
        if (on && b > 0) d = "밝기 " + b + "/10";
        int bd = LightService.band;
        if (on && bd >= 0 && !Settings.MODE_PULSE.equals(s.mode)) d += (d.isEmpty() ? "" : "  ·  ") + Settings.BAND_NAMES[bd];
        if (on) d += (d.isEmpty() ? "" : "  ·  ") + "명령 " + LightService.sent + "회";
        detailText.setText(d);
        meterBar.set(on ? (float) LightService.level : 0f);
        meterText.setText("마이크 입력" + (on && !LightService.meter.isEmpty() ? "  ·  " + LightService.meter : ""));
        micText.setText(on ? LightService.mic : "");
        driveText.setText(on && !LightService.drive.isEmpty() ? "주행 상태: " + LightService.drive : "");
        String p = LightService.problem;
        problemText.setText(p == null ? "" : p);
        problemText.setVisibility(p == null ? View.GONE : View.VISIBLE);
        CarLight.Status cs = LightService.carStatus;
        if (cs != null) {
            carText.setText(String.format(java.util.Locale.US, "차 무드램프: #%06X · 밝기 %s · 드라이브 모드 연동 %s",
                    cs.rgb(), cs.brightness <= 1 ? "꺼짐" : String.valueOf(cs.brightness - 1), cs.driveModeLinked() ? "켜짐" : "꺼짐"));
        }
        boolean linked = cs != null && cs.driveModeLinked();
        linkText.setText(linked ? "드라이브 모드 연동이 켜져 있어 색이 바뀌지 않습니다(밝기만 바뀜). "
                + "차량 설정 › 실내 무드 조명 › 드라이브 모드 연동을 꺼 주세요." : "");
        linkText.setVisibility(linked ? View.VISIBLE : View.GONE);
    }

    private void checkHmgApi() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HmgApi api = HmgApi.connect().timeout(3000);
                String t;
                if (!api.available()) {
                    t = "HMG-API: 응답 없음 (HMG-API가 설치되어 실행 중인지 확인)";
                } else {
                    try {
                        String m = api.mod();
                        t = m == null ? "HMG-API: 이 앱이 켜진 모드로 인식되지 않음 (HMG-API 앱에서 모드 확인)"
                                : "HMG-API: 연결됨 (" + m + ")";
                    } catch (IOException e) {
                        t = "HMG-API: " + e.getMessage();
                    }
                }
                final String text = t;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hmgText.setText(text);
                    }
                });
            }
        }).start();
    }

    private void save() {
        s.save(this);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- small building blocks

    interface Pick {
        void picked(String value);
    }

    interface Slide {
        void moved(int value);
    }

    private LinearLayout choice(String[] labels, final String[] values, String current, final Pick pick) {
        final LinearLayout bar = row();
        for (int i = 0; i < labels.length; i++) {
            final Button b = button(labels[i], 15);
            final String v = values[i];
            b.setTag(v);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mark(bar, v);
                    pick.picked(v);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1);
            if (i > 0) p.leftMargin = dp(8);
            bar.addView(b, p);
        }
        mark(bar, current);
        return bar;
    }

    private void mark(LinearLayout bar, String value) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            Button b = (Button) bar.getChildAt(i);
            boolean on = value.equals(b.getTag());
            b.setBackground(round(on ? ACCENT : PANEL2, 10));
            b.setTextColor(on ? BG : TEXT);
        }
    }

    private LinearLayout slider(final String label, final int min, int max, int value, final Slide slide) {
        LinearLayout r = row();
        final TextView t = text(label + " " + value, 15, TEXT);
        r.addView(t, new LinearLayout.LayoutParams(dp(84), -2));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean user) {
                t.setText(label + " " + (progress + min));
                slide.moved(progress + min);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                save();
            }
        });
        r.addView(bar, new LinearLayout.LayoutParams(0, dp(52), 1));
        return r;
    }

    private void section(LinearLayout parent, String title, String hint) {
        TextView t = text(title, 17, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(t, lp(-1, -2, 0, dp(18), 0, hint == null ? dp(8) : 0));
        if (hint != null) parent.addView(text(hint, 13, MUTED), lp(-1, -2, 0, dp(2), 0, dp(8)));
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label, int sp) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(sp);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(round(PANEL2, 10));
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** a rounded colour patch (the light preview, palette cells) */
    static final class Swatch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int argb = 0xff2a3740;
        boolean selected;

        Swatch(Context c) {
            super(c);
        }

        void set(int argb) {
            if (this.argb != argb) {
                this.argb = argb;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density, r = 10 * d;
            box.set(2 * d, 2 * d, getWidth() - 2 * d, getHeight() - 2 * d);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff0d1418);
            canvas.drawRoundRect(box, r, r, paint);
            paint.setColor(argb);
            canvas.drawRoundRect(box, r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(selected ? 3 * d : d);
            paint.setColor(selected ? 0xffffffff : LINE);
            canvas.drawRoundRect(box, r, r, paint);
        }
    }

    /** the microphone level */
    static final class LevelBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float value;

        LevelBar(Context c) {
            super(c);
        }

        void set(float v) {
            v = v < 0 ? 0 : v > 1 ? 1 : v;
            if (Math.abs(v - value) > 0.005f) {
                value = v;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), r = h / 2;
            box.set(0, 0, getWidth(), h);
            paint.setColor(PANEL2);
            canvas.drawRoundRect(box, r, r, paint);
            if (value > 0) {
                box.set(0, 0, Math.max(h, getWidth() * value), h);
                paint.setColor(ACCENT);
                canvas.drawRoundRect(box, r, r, paint);
            }
        }
    }
}
