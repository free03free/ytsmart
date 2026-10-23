package com.example.l2sbuilder;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * شاشة Local AI — واجهة عصرية + نظام تنزيل مزدوج الطريقة (تلقائي متعدد الروابط / اختيار رابط واحد)
 * يُستخدم لكل من محرك llama-cli ونماذج GGUF، مع فحص جاهزية حقيقي (توقيع ELF + تشغيل تجريبي فعلي)
 * بدل الاعتماد على حجم الملف فقط.
 */
public class LocalAIActivity extends Activity {

    // ===================== ألوان الواجهة العصرية =====================
    private static final int BG = 0xFF11121A;
    private static final int CARD = 0xFF1C1E2A;
    private static final int CARD_ALT = 0xFF23263A;
    private static final int ACCENT = 0xFF7C5CFF;
    private static final int ACCENT_2 = 0xFF22D3B8;
    private static final int DANGER = 0xFFFF5C7A;
    private static final int WARN = 0xFFFFB84D;
    private static final int TEXT_MAIN = 0xFFF2F2F7;
    private static final int TEXT_SUB = 0xFFA8ABBE;
    private static final int LOG_BG = 0xFF0B0C12;

    private static final int PICK_MODEL = 101;
    private static final int PICK_BINARY = 102;
    private static final int SAVE_ENGINE_COPY = 103;
    private static final int SAVE_MODEL_COPY = 104;
    private static final String MODELS_DIR = "local-ai/models";
    private static final String BIN_DIR = "local-ai/bin";
    private static final String BIN_NAME = "llama-cli";

    private Spinner modelSpinner;
    private TextView statusPill;
    private TextView ramPill;
    private TextView installedStatusText; // بطاقة "ما المُثبَّت الآن" — تُحدَّث تلقائيًا مع كل تغيير في المحرك/النموذج
    private LinearLayout binUrlList;
    private LinearLayout modelUrlList;
    private EditText promptEdit;
    private TextView chat;
    private TextView opLog;
    private ScrollView opLogScroll;
    private ScrollView chatScroll;
    private Button runBtn; // زر واحد يبدّل بين "▶️ إرسال" و"⏹️ إيقاف" حسب حالة التشغيل (بدل زرّين منفصلين)
    private boolean chatStarted = false; // هل أُرسلت أول رسالة فعلية؟ يُستخدم لإخفاء نص الترحيب الأولي عند بدء المحادثة
    private volatile boolean generating = false; // true من لحظة الضغط على إرسال حتى انتهاء الرد فعليًا (يمنع ازدواج الضغط قبل بدء العملية)
    private Process running;
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final AtomicBoolean downloadingBin = new AtomicBoolean(false);
    private final ArrayList<File> models = new ArrayList<>();
    private File pendingModelCopySource; // الملف المحدد وقت الضغط على "حفظ نسخة" — يُستخدم بعد عودة منتقي الوجهة
    private android.app.Dialog settingsDialog;

    private Spinner projectSpinner;
    private Spinner fileSpinner;
    private EditText projectSearchEdit;
    private EditText fileSearchEdit;
    private TextView filePreview;
    private EditText requestEdit;
    private LinearLayout chatContainer;      // حاوية فقاعات المحادثة (بدل textview واحد)
    private ScrollView assistResultScroll;
    private Button applyEditBtn;
    private Button diffBtn;
    private Button forceApplyBtn;     // يظهر فقط عند فشل الفحص الآلي للتعديل، لتطبيقه يدويًا بعد المراجعة
    private TextView safetyWarningText; // يعرض تفاصيل مشاكل الفحص الآلي (أقواس غير متزنة، تعريف مكرر، إلخ)
    // ---- نطاق العمل الموحّد: ملف واحد أو المشروع كامل، داخل نفس البطاقة بدل بطاقتين منفصلتين ----
    private boolean scopeProject = false; // false = ملف واحد، true = المشروع كامل
    private Button scopeFileBtn, scopeProjectBtn;
    private LinearLayout fileScopeSection;   // بحث/اختيار الملف + المعاينة (يظهر فقط في نطاق "ملف واحد")
    private LinearLayout fileActionsRow;     // زرّا "تحليل فقط" / "اقتراح تعديل" (نطاق الملف)
    private LinearLayout projectActionsCol;  // زرّا "تحليل المشروع الآن" / "إرسال الطلب" (نطاق المشروع)
    // ---- بنك الأمثلة الخارجية: أمثلة تعديل حقيقية أتت من مساعد خارجي (Claude/ChatGPT وغيرهما)، وليس من النموذج المحلي ----
    private static final String PREFS_NAME = "local_ai_prefs";
    private static final String PREF_SELECTED_MODEL = "selected_model_filename";
    private static final String PREF_BANK_ENABLED = "external_bank_enabled";
    private static final String PREF_BANK_MAX = "external_bank_max";
    private static final int DEFAULT_BANK_MAX = 60;
    private boolean bankEnabled = true;
    private int bankMaxExamples = DEFAULT_BANK_MAX; // 0 = بلا حد؛ يحدّده المستخدم بنفسه من الواجهة
    private android.widget.Switch bankToggleBtn;
    private TextView bankCountText;
    private EditText bankMaxEdit;
    private LinearLayout bankListContainer;
    private LinearLayout modelListContainer;
    private final ArrayList<File> projectList = new ArrayList<>();      // كل المشاريع
    private final ArrayList<File> projectShown = new ArrayList<>();     // المشاريع بعد تصفية البحث (ما يعرضه الـ Spinner فعليًا)
    private final ArrayList<File> projectFiles = new ArrayList<>();     // كل الملفات النصية بالمشروع الحالي
    private final ArrayList<File> fileShown = new ArrayList<>();        // الملفات بعد تصفية البحث
    private final ArrayList<String[]> conversation = new ArrayList<>(); // سجل المحادثة الحالي: {role, text}
    private File loadedFile;
    private String loadedFileContent;
    private String pendingEditedContent; // آخر تعديل اقترحه الذكاء الاصطناعي، بانتظار تأكيد التطبيق
    private final AtomicBoolean assistRunning = new AtomicBoolean(false);
    private static final int MAX_FILE_CHARS = 6000; // حد أقصى لحجم الملف المُرسل للنموذج (نماذج صغيرة = سياق محدود)
    private static final String[] TEXT_EXTENSIONS = {
            ".java", ".kt", ".xml", ".gradle", ".kts", ".json", ".txt", ".md",
            ".properties", ".pro", ".cfg", ".yml", ".yaml", ".gitignore", ".groovy"
    };

    private File projectsDir() { File f = new File(getFilesDir(), "projects"); f.mkdirs(); return f; }

    private File modelsDir() { File f = new File(getFilesDir(), MODELS_DIR); f.mkdirs(); return f; }


    private File binDir() { File f = new File(getFilesDir(), BIN_DIR); f.mkdirs(); return f; }
    private File binaryFile() { return new File(binDir(), BIN_NAME); }

    private String preselectProject;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Local AI");
        preselectProject = getIntent() != null ? getIntent().getStringExtra("project_name") : null;
        buildUi();
        refreshModels();
        refreshStatus();
        refreshProjects();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    // ===================== أدوات بناء واجهة عصرية =====================

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable roundedStroke(int strokeColor, int strokeW, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dp(strokeW), strokeColor);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 16));
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(15);
        v.setTypeface(null, Typeface.BOLD);
        v.setTextColor(TEXT_MAIN);
        v.setPadding(0, 0, 0, dp(6));
        return v;
    }

    private TextView hint(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(12);
        v.setTextColor(TEXT_SUB);
        v.setPadding(0, dp(2), 0, dp(6));
        return v;
    }

    private TextView pill(String s, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(12.5f);
        v.setTextColor(TEXT_MAIN);
        v.setBackground(rounded(color, 10));
        v.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    private Button styledButton(String s, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        b.setTextColor(textColor);
        b.setBackground(rounded(bgColor, 12));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setMinHeight(0); b.setMinimumHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(4), dp(6), dp(4), dp(0));
        b.setLayoutParams(lp);
        return b;
    }

    private Button primaryBtn(String s) { return styledButton(s, ACCENT, Color.WHITE); }
    private Button secondaryBtn(String s) { return styledButton(s, CARD_ALT, TEXT_MAIN); }
    private Button dangerBtn(String s) { return styledButton(s, DANGER, Color.WHITE); }
    private Button okBtn(String s) { return styledButton(s, ACCENT_2, 0xFF06231F); }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v);
        return r;
    }

    private EditText styledEdit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(TEXT_SUB);
        e.setTextColor(TEXT_MAIN);
        e.setBackground(rounded(CARD_ALT, 10));
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        return e;
    }

    // ===================== بناء الواجهة =====================

    private void buildUi() {
        // ================= الشاشة الرئيسية: شريط علوي + محادثة تملأ الشاشة + شريط إدخال ثابت (مثل واجهة Claude) =================
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);

        // ---- الشريط العلوي: العنوان + شارة الحالة المصغّرة + زر الإعدادات ----
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(CARD);
        topBar.setPadding(dp(16), dp(12), dp(10), dp(12));
        topBar.setElevation(dp(3));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView title = new TextView(this);
        title.setText("🤖 Local AI");
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(TEXT_MAIN);
        titleBox.addView(title);

        statusPill = new TextView(this);
        statusPill.setText("الحالة: جارٍ الفحص...");
        statusPill.setTextSize(11.5f);
        statusPill.setTextColor(TEXT_SUB);
        statusPill.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams statusPillLp = new LinearLayout.LayoutParams(-2, -2);
        statusPillLp.topMargin = dp(4);
        statusPill.setLayoutParams(statusPillLp);
        titleBox.addView(statusPill);

        topBar.addView(titleBox);

        Button gearBtn = iconBtn("⚙️");
        gearBtn.setOnClickListener(v -> { if (settingsDialog != null) settingsDialog.show(); });
        topBar.addView(gearBtn);

        screen.addView(topBar);
        screen.addView(divider());

        // ---- منطقة المحادثة (تملأ كل المساحة المتبقية بين الشريط العلوي وشريط الإدخال) ----
        chat = new TextView(this);
        chat.setText("👋 مرحبًا! لا توجد رسائل بعد.\n\nافتح ⚙️ الإعدادات أعلاه لتنزيل المحرك ونموذج GGUF أول مرة، ثم اكتب رسالتك في الأسفل.");
        chat.setTextIsSelectable(true);
        chat.setTextColor(TEXT_MAIN);
        chat.setTextSize(14f);
        chat.setLineSpacing(dp(3), 1f);
        chat.setPadding(dp(16), dp(14), dp(16), dp(14));

        chatScroll = new ScrollView(this);
        chatScroll.setBackgroundColor(BG);
        chatScroll.addView(chat);
        chatScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        screen.addView(chatScroll);

        screen.addView(divider());

        // ---- شريط الإدخال السفلي (ثابت دائمًا، لا يتحرك مع التمرير) ----
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.BOTTOM);
        inputBar.setBackgroundColor(CARD);
        inputBar.setPadding(dp(10), dp(8), dp(10), dp(8));

        promptEdit = styledEdit("اكتب رسالتك هنا...");
        promptEdit.setGravity(Gravity.TOP | Gravity.START);
        promptEdit.setMaxLines(5);
        LinearLayout.LayoutParams peLp = new LinearLayout.LayoutParams(0, -2, 1);
        peLp.rightMargin = dp(8);
        promptEdit.setLayoutParams(peLp);
        inputBar.addView(promptEdit);

        // زر واحد فقط بدل زرّين متراصّين رأسيًا (كانا يأخذان ارتفاعًا مضاعفًا بلا داعٍ) — يتبدّل شكله
        // ووظيفته تلقائيًا بين "إرسال" أثناء الخمول و"إيقاف" أثناء التوليد.
        runBtn = iconBtn("▶️");
        runBtn.setBackground(rounded(ACCENT, 12));
        runBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        inputBar.addView(runBtn);
        screen.addView(inputBar);

        runBtn.setOnClickListener(v -> { if (generating) stopRunning(); else runPrompt(); });

        setContentView(screen);

        buildSettingsDialog();
    }

    /** زر مربّع مدمج لأيقونة/رمز واحد (بخلاف styledButton الذي يتمدد أفقيًا دائمًا). */
    private Button iconBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17f);
        b.setTextColor(TEXT_MAIN);
        b.setBackground(rounded(CARD_ALT, 12));
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return b;
    }

    /** يبدّل شكل زر الإرسال/الإيقاف الموحّد: ▶️ بنفسجي أثناء الخمول، ⏹️ أحمر أثناء توليد رد فعليًا. */
    private void setRunButtonState(boolean isRunning) {
        runBtn.setText(isRunning ? "⏹️" : "▶️");
        runBtn.setBackground(rounded(isRunning ? DANGER : ACCENT, 12));
        runBtn.setEnabled(true); // الزر يبقى مفعّلًا دائمًا الآن لأنه يخدم الوظيفتين معًا (إرسال أو إيقاف)
    }

    /**
     * يغلّف عنصرًا (مثل صندوق روابط طويل) داخل قسم قابل للطي: زر عنوان يُظهر/يُخفي المحتوى عند الضغط،
     * مطويًا افتراضيًا لتقليل طول قائمة الإعدادات. المحتوى يبقى موجودًا وقابلًا للقراءة/التعديل برمجيًا
     * حتى وهو مخفيًا (View.GONE لا يحذفه، فقط يخفيه بصريًا ويزيل مساحته).
     */
    private LinearLayout collapsibleSection(String title, View innerView) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        Button toggle = secondaryBtn("▸ " + title);
        toggle.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        innerView.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> {
            boolean expand = innerView.getVisibility() != View.VISIBLE;
            innerView.setVisibility(expand ? View.VISIBLE : View.GONE);
            toggle.setText((expand ? "▾ " : "▸ ") + title);
        });
        wrapper.addView(toggle);
        wrapper.addView(innerView);
        return wrapper;
    }

    /**
     * ينشئ محرر قائمة روابط بواجهة أزرار بدل نص حر: صف EditText + 🗑️ لكل رابط،
     * وزر ➕ ثابت في الأسفل لإضافة رابط جديد. container.getTag() يحتفظ بمرجع زر ➕
     * حتى تُدرَج الصفوف الجديدة دائمًا قبله مباشرة.
     */
    private LinearLayout buildUrlListEditor(List<String> initialUrls) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        Button addBtn = secondaryBtn("➕ إضافة رابط");
        addBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        container.setTag(addBtn);
        addBtn.setOnClickListener(v -> addUrlRow(container, ""));

        for (String u : initialUrls) addUrlRowInternal(container, u, addBtn);
        container.addView(addBtn);
        return container;
    }

    private void addUrlRow(LinearLayout container, String initialValue) {
        addUrlRowInternal(container, initialValue, (Button) container.getTag());
    }

    private void addUrlRowInternal(LinearLayout container, String initialValue, Button addBtn) {
        LinearLayout rowL = new LinearLayout(this);
        rowL.setOrientation(LinearLayout.HORIZONTAL);
        rowL.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(6);
        rowL.setLayoutParams(rowLp);

        EditText urlField = styledEdit("https://...");
        urlField.setText(initialValue);
        urlField.setMaxLines(3);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(0, -2, 1);
        fieldLp.rightMargin = dp(6);
        urlField.setLayoutParams(fieldLp);
        rowL.addView(urlField);

        Button delBtn = iconBtn("🗑️");
        delBtn.setBackground(rounded(DANGER, 12));
        delBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        delBtn.setOnClickListener(v -> container.removeView(rowL));
        rowL.addView(delBtn);

        int insertIndex = (addBtn == null) ? container.getChildCount() : container.indexOfChild(addBtn);
        if (insertIndex < 0) insertIndex = container.getChildCount();
        container.addView(rowL, insertIndex);
    }

    /** يجمع كل الروابط الصالحة (http/https فقط، تتجاهل الصفوف الفارغة) من صفوف المحرر الحالية. */
    private ArrayList<String> collectUrls(LinearLayout container) {
        ArrayList<String> urls = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue; // يتجاوز زر ➕ نفسه (ليس LinearLayout)
            LinearLayout rowL = (LinearLayout) child;
            if (rowL.getChildCount() == 0 || !(rowL.getChildAt(0) instanceof EditText)) continue;
            String t = ((EditText) rowL.getChildAt(0)).getText().toString().trim();
            if (t.startsWith("http://") || t.startsWith("https://")) urls.add(t);
        }
        return urls;
    }

    /** يستبدل كل صفوف الروابط الحالية بقائمة جديدة (يُستخدم عند تعبئة قالب نموذج جاهز). */
    private void setUrlRows(LinearLayout container, List<String> urls) {
        Button addBtn = (Button) container.getTag();
        container.removeAllViews();
        for (String u : urls) addUrlRowInternal(container, u, addBtn);
        container.addView(addBtn);
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(CARD_ALT);
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return d;
    }

    // ===================== لوحة الإعدادات (نافذة كاملة الشاشة تفتح بزر ⚙️) =====================

    private void buildSettingsDialog() {
        settingsDialog = new android.app.Dialog(this);
        settingsDialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackgroundColor(BG);

        // ---- ترويسة لوحة الإعدادات ----
        LinearLayout dHeader = new LinearLayout(this);
        dHeader.setOrientation(LinearLayout.HORIZONTAL);
        dHeader.setGravity(Gravity.CENTER_VERTICAL);
        dHeader.setBackgroundColor(CARD);
        dHeader.setPadding(dp(10), dp(14), dp(16), dp(14));
        dHeader.setElevation(dp(3));

        Button closeBtn = iconBtn("✕");
        closeBtn.setOnClickListener(v -> settingsDialog.dismiss());
        dHeader.addView(closeBtn);

        TextView dTitle = new TextView(this);
        dTitle.setText("⚙️ الإعدادات");
        dTitle.setTextSize(18);
        dTitle.setTypeface(null, Typeface.BOLD);
        dTitle.setTextColor(TEXT_MAIN);
        dTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        dHeader.addView(dTitle);
        dialogRoot.addView(dHeader);
        dialogRoot.addView(divider());

        ScrollView dScroll = new ScrollView(this);
        dScroll.setFillViewport(true);
        dScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        dScrollRef = dScroll;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(24));

        TextView subtitle = new TextView(this);
        subtitle.setText("تشغيل نماذج لغوية محليًا على جهازك دون إنترنت بعد التجهيز");
        subtitle.setTextSize(12.5f);
        subtitle.setTextColor(TEXT_SUB);
        subtitle.setPadding(0, 0, 0, dp(2));
        content.addView(subtitle);

        ramPill = pill("", CARD_ALT);
        content.addView(ramPill);

        // ---- شريط تبويبات مقسّم لتنظيم الإعدادات بدل قائمة طويلة واحدة ----
        final LinearLayout page0 = new LinearLayout(this); // الإعداد: المحرك + النموذج + فحص الجاهزية
        final LinearLayout page1 = new LinearLayout(this); // متقدم: إعدادات الاستئناف
        final LinearLayout page2 = new LinearLayout(this); // المساعد: قراءة/تحليل/تعديل المشروع
        final LinearLayout page3 = new LinearLayout(this); // السجل
        final LinearLayout[] pages = { page0, page1, page2, page3 };
        page2Ref = page2;
        for (LinearLayout p : pages) p.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackground(rounded(CARD_ALT, 14));
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams tabBarLp = new LinearLayout.LayoutParams(-1, -2);
        tabBarLp.topMargin = dp(10);
        tabBarLp.bottomMargin = dp(4);
        tabBar.setLayoutParams(tabBarLp);

        String[] tabLabels = { "🧠 الإعداد", "🛠️ متقدم", "🗂️ المساعد", "📋 السجل" };
        final Button[] tabButtons = new Button[tabLabels.length];
        for (int i = 0; i < tabLabels.length; i++) {
            Button tb = new Button(this);
            tb.setText(tabLabels[i]);
            tb.setAllCaps(false);
            tb.setTextSize(12.5f);
            tb.setMinHeight(0); tb.setMinimumHeight(0);
            tb.setPadding(dp(6), dp(10), dp(6), dp(10));
            LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(0, -2, 1);
            tbLp.setMargins(dp(2), 0, dp(2), 0);
            tb.setLayoutParams(tbLp);
            tabButtons[i] = tb;
            tabBar.addView(tb);
        }
        content.addView(tabBar);

        final int[] activeTab = { 0 };
        Runnable refreshTabs = () -> {
            for (int i = 0; i < tabButtons.length; i++) {
                boolean active = i == activeTab[0];
                tabButtons[i].setBackground(rounded(active ? ACCENT : CARD_ALT, 10));
                tabButtons[i].setTextColor(active ? Color.WHITE : TEXT_SUB);
                pages[i].setVisibility(active ? View.VISIBLE : View.GONE);
            }
        };
        for (int i = 0; i < tabButtons.length; i++) {
            final int idx = i;
            tabButtons[i].setOnClickListener(v -> { activeTab[0] = idx; refreshTabs.run(); });
        }
        refreshTabs.run();
        for (LinearLayout p : pages) content.addView(p);

        // ---- بطاقة تعريفية: ما المُثبَّت فعليًا الآن (المحرك + النموذج + الأدوات المدمجة) ----
        LinearLayout infoCard = card();
        infoCard.addView(sectionTitle("ℹ️ الحالة الحالية — ما المُثبَّت الآن"));
        installedStatusText = new TextView(this);
        installedStatusText.setTypeface(Typeface.MONOSPACE);
        installedStatusText.setTextSize(11.5f);
        installedStatusText.setTextColor(TEXT_SUB);
        installedStatusText.setTextIsSelectable(true);
        installedStatusText.setPadding(dp(8), dp(8), dp(8), dp(8));
        installedStatusText.setBackground(rounded(LOG_BG, 10));
        infoCard.addView(installedStatusText);

        LinearLayout infoBody = new LinearLayout(this);
        infoBody.setOrientation(LinearLayout.VERTICAL);

        infoBody.addView(sectionTitle("⚙️ المحرك (llama-cli)"));
        infoBody.addView(hint("برنامج تشغيل خفيف مبني لأندرويد (ARM64) يقرأ ملف النموذج (GGUF) وينفّذه على معالج جهازك مباشرة، دون إنترنت بعد تجهيزه. لا يحتوي أي ذكاء بذاته — هو فقط \"المُشغِّل\"، والذكاء الفعلي يأتي من ملف النموذج."));

        infoBody.addView(sectionTitle("🧠 النموذج (ملف GGUF)"));
        infoBody.addView(hint("ملف يحمل \"وزن\" النموذج اللغوي نفسه. كلما كان أكبر كان أذكى وأبطأ وأثقل على الذاكرة، والعكس صحيح. النماذج الصغيرة المناسبة للهاتف (0.5B–1.5B) سريعة وخفيفة، لكنها أضعف بكثير من نماذج سحابية كبيرة — تصلح لطلبات بسيطة وملفات صغيرة، وقد تُخطئ."));

        infoBody.addView(sectionTitle("🗂️ أدوات الذكاء الاصطناعي المدمجة (تبويب «المساعد»)"));
        infoBody.addView(hint("مساعد واحد بنطاقين تختارهما من نفس البطاقة، دون الخروج لتغيير المشروع:\n"
                + "• 📄 ملف واحد — يقرأ ملفًا محدَّدًا، يحلله أو يقترح تعديلًا له، مع معاينة Diff إلزامية قبل أي تطبيق فعلي، ونسخة احتياطية تلقائية عند التطبيق.\n"
                + "• 📁 المشروع كامل — يبني فهرسًا مضغوطًا لبنية المشروع ويستخدمه للإجابة عن أسئلة عامة أو اقتراح ميزات؛ إن اقترح تعديل ملف معيّن يعطيك زرًا لفتحه ومراجعته بنفس آلية Diff.\n"
                + "لا شيء يُطبَّق تلقائيًا على ملفاتك بدون موافقتك الصريحة."));

        infoCard.addView(collapsibleSection("ما معنى هذا؟ (شرح مبسّط)", infoBody));
        page0.addView(infoCard);

        // ---- بطاقة: المحرك (llama-cli) ----
        LinearLayout engineCard = card();
        engineCard.addView(sectionTitle("⚙️ محرك التشغيل (llama-cli)"));
        engineCard.addView(hint("يدعم طريقتين: تنزيل تلقائي يجرّب كل الروابط، أو استيراد ملف من جهازك."));

        Button importBin = secondaryBtn("📂 استيراد ملف من الجهاز");
        Button binInfo = secondaryBtn("ℹ️ تفاصيل المحرك");
        engineCard.addView(row(importBin, binInfo));
        importBin.setOnClickListener(v -> pickBinary());
        binInfo.setOnClickListener(v -> showBinaryInfo());

        engineCard.addView(hint("روابط تنزيل llama-cli — عدّة إصدارات رسمية من نفس المشروع. أضف رابطًا جديدًا بـ➕ أو احذف أي رابط بـ🗑️ بجانبه:"));
        binUrlList = buildUrlListEditor(Arrays.asList(
                "https://github.com/ggml-org/llama.cpp/releases/download/b11178/llama-b11178-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11177/llama-b11177-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11176/llama-b11176-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11175/llama-b11175-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11174/llama-b11174-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11173/llama-b11173-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11172/llama-b11172-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11171/llama-b11171-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11170/llama-b11170-bin-android-arm64.tar.gz",
                "https://github.com/ggml-org/llama.cpp/releases/download/b11169/llama-b11169-bin-android-arm64.tar.gz"));
        engineCard.addView(collapsibleSection("عرض/تعديل روابط المحرك (مطوي لتقصير القائمة)", binUrlList));

        Button downloadBinAuto = primaryBtn("⬇️ طريقة 1: تجربة كل الروابط تلقائيًا");
        Button pickOneBin = secondaryBtn("🎯 طريقة 2: اختيار رابط واحد");
        engineCard.addView(row(downloadBinAuto, pickOneBin));
        downloadBinAuto.setOnClickListener(v -> downloadAllBinaryUrls());
        pickOneBin.setOnClickListener(v -> pickOneUrlDialog(collectUrls(binUrlList), true));

        Button saveEngineCopyBtn = secondaryBtn("💾 حفظ نسخة من المحرك (ZIP) إلى جهازك");
        saveEngineCopyBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        engineCard.addView(saveEngineCopyBtn);
        saveEngineCopyBtn.setOnClickListener(v -> saveEngineCopy());

        page0.addView(engineCard);

        // ---- بطاقة: نماذج GGUF ----
        LinearLayout modelCard = card();
        modelCard.addView(sectionTitle("🧠 نموذج GGUF"));
        modelCard.addView(hint("عند تنزيل أو استيراد أكثر من نموذج، اختر من القائمة أدناه أيّها يعمل فعليًا — يُحفظ اختيارك ويُستعاد تلقائيًا حتى بعد إغلاق التطبيق."));

        modelSpinner = new Spinner(this);
        modelCard.addView(modelSpinner);
        modelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < models.size()) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_SELECTED_MODEL, models.get(position).getName()).apply();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        Button addModel = secondaryBtn("📂 استيراد GGUF من الجهاز");
        Button deleteModel = dangerBtn("🗑️ حذف النموذج المحدد");
        modelCard.addView(row(addModel, deleteModel));
        addModel.setOnClickListener(v -> pickModel());
        deleteModel.setOnClickListener(v -> deleteSelectedModel());

        Button saveModelCopyBtn = secondaryBtn("💾 حفظ نسخة من النموذج المحدد إلى جهازك");
        saveModelCopyBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        modelCard.addView(saveModelCopyBtn);
        saveModelCopyBtn.setOnClickListener(v -> saveModelCopy());

        modelListContainer = new LinearLayout(this);
        modelListContainer.setOrientation(LinearLayout.VERTICAL);
        modelCard.addView(collapsibleSection("📋 كل النماذج المثبّتة (حذف مباشر لكل واحد)", modelListContainer));

        modelCard.addView(hint("قوالب جاهزة (تملأ حقل الروابط أدناه تلقائيًا حسب اختيارك):"));
        String[] modelLabels = {
                "Qwen2.5 0.5B Instruct — الأنسب لذاكرة جهازك",
                "Llama 3.2 1B Instruct — يحتاج ذاكرة أكبر",
                "Qwen2.5 1.5B Instruct — كبير، غير موصى به لجهازك"
        };
        // مجموعة مستويات ضغط قياسية يستخدمها bartowski في كل مستودعاته تقريبًا (تم التحقق من وجودها
        // فعليًا لنموذج Qwen2.5-0.5B عبر البحث؛ للنموذجين الآخرين نفس النمط القياسي لنفس الناشر).
        // من الأكبر حجمًا/الأعلى جودة إلى الأصغر — اختر وانسخ الرابط الذي يناسبك بنفسك.
        final String[][] modelQuantUrls = {
                {
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q2_K.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q3_K_M.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q3_K_L.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-IQ4_XS.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_S.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q5_K_M.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q6_K.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q8_0.gguf",
                        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
                },
                {
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q2_K.gguf",
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q3_K_M.gguf",
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q3_K_L.gguf",
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-IQ4_XS.gguf",
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_S.gguf",
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q5_K_M.gguf",
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q6_K.gguf",
                        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q8_0.gguf"
                },
                {
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q2_K.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q3_K_M.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q3_K_L.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-IQ4_XS.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_S.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q5_K_M.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q6_K.gguf",
                        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q8_0.gguf"
                }
        };
        Spinner modelPickSpinner = new Spinner(this);
        modelPickSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, modelLabels));
        modelCard.addView(modelPickSpinner);
        Button fillTemplate = secondaryBtn("↧ ملء حقل الروابط بهذا القالب");
        fillTemplate.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        modelCard.addView(fillTemplate);
        fillTemplate.setOnClickListener(v -> {
            int mi = modelPickSpinner.getSelectedItemPosition();
            if (mi < 0) return;
            if (mi > 0) Toast.makeText(this, "تنبيه: قد يحتاج هذا النموذج ذاكرة أكبر من المتاح على جهازك.", Toast.LENGTH_LONG).show();
            setUrlRows(modelUrlList, Arrays.asList(modelQuantUrls[mi]));
        });

        modelCard.addView(hint("روابط تنزيل GGUF — كل مستويات الضغط المتاحة لهذا النموذج، من الأعلى جودة (Q8_0) إلى الأصغر حجمًا (Q2_K). أضف رابطًا بـ➕ أو احذف أي رابط بـ🗑️ بجانبه:"));
        modelUrlList = buildUrlListEditor(Arrays.asList(modelQuantUrls[0]));
        modelCard.addView(collapsibleSection("عرض/تعديل روابط النموذج (مطوي لتقصير القائمة)", modelUrlList));

        Button downloadModelAuto = primaryBtn("⬇️ طريقة 1: تجربة الروابط بالترتيب حتى ينجح أحدها");
        Button pickOneModel = secondaryBtn("🎯 طريقة 2: اختيار رابط واحد بنفسي");
        modelCard.addView(row(downloadModelAuto, pickOneModel));
        downloadModelAuto.setOnClickListener(v -> downloadAllModelUrls());
        pickOneModel.setOnClickListener(v -> pickOneUrlDialog(collectUrls(modelUrlList), false));

        page0.addView(modelCard);

        // ---- بطاقة: فحص الجاهزية ----
        LinearLayout readyCard = card();
        readyCard.addView(sectionTitle("✅ هل الجهاز جاهز للعمل؟"));
        readyCard.addView(hint("فحص حقيقي: يتأكد من صحة ملف المحرك (توقيع ELF + المعمارية)، ووجود كل المكتبات المطلوبة، ثم يُشغّل المحرك فعليًا للتأكد أنه يعمل على معالج جهازك — وليس مجرد التحقق من وجود الملفات."));
        Button checkReady = okBtn("🔍 فحص الجاهزية الآن (تشغيل تجريبي فعلي)");
        checkReady.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        readyCard.addView(checkReady);
        checkReady.setOnClickListener(v -> checkDeviceReadiness());
        page0.addView(readyCard);

        // ---- بطاقة: إعدادات متقدمة (رقم قابل للتعديل بدل ثابت في الكود) ----
        LinearLayout advCard = card();
        advCard.addView(sectionTitle("🛠️ إعدادات متقدمة"));
        advCard.addView(hint("الحد الأقصى لمحاولات إعادة الاستئناف عند انقطاع الاتصال أثناء التنزيل (لكل ملف). "
                + "ارفعه إن كانت شبكتك كثيرة الانقطاع، أو قلّله إن كنت تفضّل الفشل بسرعة بدل الانتظار طويلًا. "
                + "بين " + MIN_MAX_RESUME_RETRIES + " و" + MAX_MAX_RESUME_RETRIES + " (الافتراضي: " + DEFAULT_MAX_RESUME_RETRIES + ")."));
        EditText maxRetriesEdit = styledEdit("مثال: 30");
        maxRetriesEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        maxRetriesEdit.setText(String.valueOf(getMaxResumeRetries()));
        advCard.addView(maxRetriesEdit);
        Button saveRetriesBtn = okBtn("💾 حفظ هذا الإعداد");
        saveRetriesBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        advCard.addView(saveRetriesBtn);
        saveRetriesBtn.setOnClickListener(v -> {
            String txt = maxRetriesEdit.getText().toString().trim();
            int parsed;
            try { parsed = Integer.parseInt(txt); } catch (NumberFormatException e) {
                Toast.makeText(this, "أدخل رقمًا صحيحًا", Toast.LENGTH_LONG).show(); return;
            }
            int clamped = Math.max(MIN_MAX_RESUME_RETRIES, Math.min(MAX_MAX_RESUME_RETRIES, parsed));
            setMaxResumeRetries(clamped);
            maxRetriesEdit.setText(String.valueOf(clamped));
            Toast.makeText(this, "تم الحفظ: " + clamped + " محاولة — يُطبَّق فورًا على أي تنزيل جديد أو مستأنف",
                    clamped == parsed ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        });
        page1.addView(advCard);

        // ---- بطاقة موحّدة: مساعد الذكاء الاصطناعي (ملف واحد أو المشروع كامل، بدون خروج لتغيير المشروع) ----
        LinearLayout assistCard = card();
        assistCardRef = assistCard;
        assistCard.addView(sectionTitle("🗂️ مساعد الذكاء الاصطناعي — قراءة وتحليل وتعديل"));
        assistCard.addView(hint("يقرأ الذكاء الاصطناعي المحلي مشروعك ويحلله أو يقترح تعديلًا حسب طلبك. تنبيه صادق: النماذج الصغيرة (0.5B–1.5B) أضعف بكثير من مساعد برمجي حقيقي — تصلح لملفات صغيرة وطلبات بسيطة فقط، وقد تُخطئ. راجع أي تعديل قبل تطبيقه دائمًا."));

        assistCard.addView(hint("1) المشروع (اكتب للبحث — يمكنك تغييره هنا في أي وقت دون الخروج من هذه الشاشة):"));
        projectSearchEdit = styledEdit("🔎 بحث في المشاريع...");
        assistCard.addView(projectSearchEdit);
        projectSpinner = new Spinner(this);
        assistCard.addView(projectSpinner);
        Button refreshProjBtn = secondaryBtn("🔄 تحديث قائمة المشاريع");
        refreshProjBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        assistCard.addView(refreshProjBtn);
        refreshProjBtn.setOnClickListener(v -> refreshProjects());

        assistCard.addView(hint("2) نطاق العمل:"));
        scopeFileBtn = primaryBtn("📄 ملف واحد");
        scopeProjectBtn = secondaryBtn("📁 المشروع كامل");
        assistCard.addView(row(scopeFileBtn, scopeProjectBtn));
        scopeFileBtn.setOnClickListener(v -> setScope(false));
        scopeProjectBtn.setOnClickListener(v -> setScope(true));

        // ---- قسم نطاق "ملف واحد": بحث الملف + تحميله + معاينته ----
        fileScopeSection = new LinearLayout(this);
        fileScopeSection.setOrientation(LinearLayout.VERTICAL);
        fileScopeSection.addView(hint("اختر الملف داخل المشروع (اكتب للبحث، الملفات النصية فقط):"));
        fileSearchEdit = styledEdit("🔎 بحث في ملفات المشروع...");
        fileScopeSection.addView(fileSearchEdit);
        fileSpinner = new Spinner(this);
        fileScopeSection.addView(fileSpinner);
        Button loadFileBtn = primaryBtn("📥 تحميل الملف");
        loadFileBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        fileScopeSection.addView(loadFileBtn);
        loadFileBtn.setOnClickListener(v -> loadSelectedFile());

        fileScopeSection.addView(hint("محتوى الملف المُحمَّل (معاينة، أول " + MAX_FILE_CHARS + " حرف فقط سيُرسل للنموذج):"));
        filePreview = new TextView(this);
        filePreview.setText("لم يُحمَّل أي ملف بعد.");
        filePreview.setTextIsSelectable(true);
        filePreview.setTypeface(Typeface.MONOSPACE);
        filePreview.setTextSize(10.5f);
        filePreview.setTextColor(TEXT_SUB);
        ScrollView filePreviewScroll = new ScrollView(this);
        filePreviewScroll.setBackground(rounded(LOG_BG, 10));
        filePreviewScroll.setPadding(dp(6), dp(6), dp(6), dp(6));
        filePreviewScroll.addView(filePreview);
        filePreviewScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(120)));
        fileScopeSection.addView(filePreviewScroll);
        assistCard.addView(fileScopeSection);

        projectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                refreshProjectFiles();
                if (scopeProject) {
                    int pi2 = projectSpinner.getSelectedItemPosition();
                    if (pi2 >= 0 && pi2 < projectShown.size()) loadProjectChatFor(projectShown.get(pi2));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        projectSearchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { rebuildProjectSpinner(); }
        });
        fileSearchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { rebuildFileSpinner(); }
        });

        assistCard.addView(hint("3) ماذا تريد؟ (في نطاق الملف: \"اشرح هذا الملف\"، \"أضف تعليقًا لكل دالة\"... — في نطاق المشروع: \"حلل المشروع\"، \"أعطني نواقصه\"، \"اقترح ميزات\"، \"أضف ميزة كذا\"...):"));
        requestEdit = styledEdit("اكتب طلبك هنا...");
        requestEdit.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams reqLp = new LinearLayout.LayoutParams(-1, dp(70));
        requestEdit.setLayoutParams(reqLp);
        assistCard.addView(requestEdit);

        // ---- أزرار نطاق "ملف واحد" ----
        fileActionsRow = new LinearLayout(this);
        fileActionsRow.setOrientation(LinearLayout.VERTICAL);
        Button analyzeBtn = secondaryBtn("🔍 تحليل فقط (بدون تعديل)");
        Button suggestBtn = primaryBtn("✏️ اقتراح تعديل");
        fileActionsRow.addView(row(analyzeBtn, suggestBtn));
        analyzeBtn.setOnClickListener(v -> runAssistant(false));
        suggestBtn.setOnClickListener(v -> runAssistant(true));
        assistCard.addView(fileActionsRow);

        // ---- أزرار نطاق "المشروع كامل" ----
        projectActionsCol = new LinearLayout(this);
        projectActionsCol.setOrientation(LinearLayout.VERTICAL);
        Button analyzeProjectBtn = primaryBtn("📊 تحليل المشروع الآن (نواقص + اقتراحات)");
        analyzeProjectBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        projectActionsCol.addView(analyzeProjectBtn);
        analyzeProjectBtn.setOnClickListener(v -> sendProjectRequest(
                "حلل هذا المشروع بالكامل: اذكر نقاط الضعف والنواقص فيه، ثم اقترح من 3 إلى 5 ميزات أو تحسينات عملية يمكن إضافتها."));
        Button sendProjectBtn = okBtn("🚀 إرسال الطلب الحر أعلاه إلى مساعد المشروع");
        sendProjectBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        projectActionsCol.addView(sendProjectBtn);
        sendProjectBtn.setOnClickListener(v -> {
            String req = requestEdit.getText().toString().trim();
            if (req.isEmpty()) { Toast.makeText(this, "اكتب طلبك أولًا", Toast.LENGTH_LONG).show(); return; }
            sendProjectRequest(req);
            requestEdit.setText("");
        });
        assistCard.addView(projectActionsCol);

        // ---- محادثة نطاق "ملف واحد" ----
        assistCard.addView(hint("💬 المحادثة (يمكنك متابعة الحوار بطلبات إضافية قبل التطبيق):"));
        chatContainer = new LinearLayout(this);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        chatContainer.setPadding(dp(6), dp(6), dp(6), dp(6));
        assistResultScroll = new ScrollView(this);
        assistResultScroll.setBackground(rounded(LOG_BG, 10));
        assistResultScroll.addView(chatContainer);
        assistResultScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(280)));
        assistCard.addView(assistResultScroll);

        applyEditBtn = okBtn("✅ تطبيق هذا التعديل على الملف (بعد نسخة احتياطية تلقائية)");
        applyEditBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        applyEditBtn.setEnabled(false);
        assistCard.addView(applyEditBtn);
        diffBtn = secondaryBtn("🔍 معاينة الفرق قبل التطبيق (Diff)");
        diffBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        diffBtn.setEnabled(false);
        assistCard.addView(diffBtn);
        diffBtn.setOnClickListener(v -> showDiffDialog());
        applyEditBtn.setOnClickListener(v -> confirmApplyEdit());

        // ---- فحص أمان آلي (بدون أي ذكاء اصطناعي) قبل السماح بالتطبيق: أقواس غير متزنة، تعريف مكرر لمتغيّر، إلخ ----
        safetyWarningText = new TextView(this);
        safetyWarningText.setTextColor(0xFFFFC24B);
        safetyWarningText.setTextSize(11.5f);
        safetyWarningText.setPadding(0, dp(4), 0, dp(2));
        safetyWarningText.setVisibility(View.GONE);
        assistCard.addView(safetyWarningText);

        forceApplyBtn = dangerBtn("⚠️ تجاهل التحذير وفعّل زر التطبيق يدويًا (راجعت الفرق وأنا متأكد)");
        forceApplyBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        forceApplyBtn.setVisibility(View.GONE);
        assistCard.addView(forceApplyBtn);
        forceApplyBtn.setOnClickListener(v -> {
            applyEditBtn.setEnabled(true);
            forceApplyBtn.setVisibility(View.GONE);
        });

        // ---- محادثة نطاق "المشروع كامل" (نفس البطاقة، تُخفى وتظهر حسب النطاق المختار أعلاه) ----
        LinearLayout projectChatLabel = new LinearLayout(this);
        projectChatLabel.setOrientation(LinearLayout.VERTICAL);
        projectChatLabel.addView(hint("💬 محادثة مساعد المشروع:"));
        projectChatContainer = new LinearLayout(this);
        projectChatContainer.setOrientation(LinearLayout.VERTICAL);
        projectChatContainer.setPadding(dp(6), dp(6), dp(6), dp(6));
        projectChatScroll = new ScrollView(this);
        projectChatScroll.setBackground(rounded(LOG_BG, 10));
        projectChatScroll.addView(projectChatContainer);
        projectChatScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(280)));
        projectChatLabel.addView(projectChatScroll);
        assistCard.addView(projectChatLabel);
        projectChatLabelRef = projectChatLabel;

        page2.addView(assistCard);
        setScope(false); // الوضع الافتراضي: ملف واحد

        // ---- بطاقة: بنك الأمثلة الخارجية ----
        LinearLayout bankCard = card();
        bankCard.addView(sectionTitle("🧩 بنك الأمثلة الخارجية"));
        bankCard.addView(hint("يُستخدم فقط للأمثلة التي جئت بها من مساعد خارجي (Claude أو ChatGPT أو غيرهما) وطبّقتها بنفسك — وليس لردود المساعد المحلي نفسه (تلك محفوظة أصلًا في محادثة كل ملف/مشروع، وإعادة تلقيمها لنفس النموذج الضعيف تكرّر أخطاءه بدل تحسينه). عند تفعيل البنك، يبحث المساعد المحلي عن أقرب مثال مشابه لطلبك (نفس المشروع/امتداد الملف/كلمات الطلب) ويُرفق فرقه (Diff) المختصر كمرجع أسلوب قبل توليد ردّه — لا تدريب حقيقي، فقط سياق إضافي."));

        bankToggleBtn = new android.widget.Switch(this);
        bankToggleBtn.setText("تفعيل بنك الأمثلة");
        bankToggleBtn.setTextColor(TEXT_MAIN);
        bankToggleBtn.setTextSize(14);
        bankToggleBtn.setPadding(0, 12, 0, 12);
        {
            float dn = getResources().getDisplayMetrics().density;
            GradientDrawable off = new GradientDrawable();
            off.setColor(0xFFE53935); off.setCornerRadius(14 * dn); off.setSize((int) (52 * dn), (int) (28 * dn));
            GradientDrawable on = new GradientDrawable();
            on.setColor(0xFF43A047); on.setCornerRadius(14 * dn); on.setSize((int) (52 * dn), (int) (28 * dn));
            android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
            track.addState(new int[]{android.R.attr.state_checked}, on);
            track.addState(new int[]{}, off);
            bankToggleBtn.setTrackDrawable(track);
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(0xFFFFFFFF); thumb.setSize((int) (24 * dn), (int) (24 * dn));
            bankToggleBtn.setThumbDrawable(thumb);
            bankToggleBtn.setShowText(false);
        }
        bankToggleBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        bankCard.addView(bankToggleBtn);
        bankToggleBtn.setChecked(bankEnabled);
        bankToggleBtn.setOnCheckedChangeListener((b, on) -> { if (on != bankEnabled) setBankEnabled(on); });

        Button addExampleBtn = okBtn("➕ أضف مثالًا خارجيًا (قبل / بعد)");
        addExampleBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        bankCard.addView(addExampleBtn);
        addExampleBtn.setOnClickListener(v -> showAddExampleDialog());

        // ---- تحديد المستخدم بنفسه لعدد الأمثلة الأقصى المحفوظ (بدل رقم ثابت 60) ----
        bankCard.addView(hint("الحد الأقصى لعدد الأمثلة المحفوظة (اتركه 0 لجعله بلا حد):"));
        LinearLayout maxRow = new LinearLayout(this);
        maxRow.setOrientation(LinearLayout.HORIZONTAL);
        maxRow.setGravity(Gravity.CENTER_VERTICAL);
        bankMaxEdit = styledEdit("مثال: 60");
        bankMaxEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        bankMaxEdit.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button saveMaxBtn = secondaryBtn("حفظ الحد");
        saveMaxBtn.setOnClickListener(v -> {
            String txt = bankMaxEdit.getText().toString().trim();
            int newMax;
            try { newMax = txt.isEmpty() ? 0 : Math.max(0, Integer.parseInt(txt)); }
            catch (Throwable e) { Toast.makeText(this, "أدخل رقمًا صحيحًا", Toast.LENGTH_SHORT).show(); return; }
            applyBankMax(newMax);
        });
        maxRow.addView(bankMaxEdit);
        maxRow.addView(saveMaxBtn);
        bankCard.addView(maxRow);

        bankCountText = hint("جارٍ التحميل...");
        bankCard.addView(bankCountText);

        bankListContainer = new LinearLayout(this);
        bankListContainer.setOrientation(LinearLayout.VERTICAL);
        bankCard.addView(collapsibleSection("تصفّح/تعديل/حذف الأمثلة المحفوظة", bankListContainer));

        page2.addView(bankCard);
        bankEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_BANK_ENABLED, true);
        bankMaxExamples = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_BANK_MAX, DEFAULT_BANK_MAX);
        bankMaxEdit.setText(String.valueOf(bankMaxExamples));
        applyBankToggleUi();
        refreshBankList();

        // ---- بطاقة: السجل (قابل للتمرير والنسخ دائمًا) ----
        LinearLayout logCard = card();
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = sectionTitle("📋 السجل");
        logTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button copyLogBtn = secondaryBtn("📄 نسخ");
        copyLogBtn.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        logHeader.addView(logTitle);
        logHeader.addView(copyLogBtn);
        logCard.addView(logHeader);
        logCard.addView(hint("هذا الصندوق ثابت ولا يختفي تلقائيًا؛ مرّر بإصبعك داخله لقراءة كل التفاصيل."));

        opLog = new TextView(this);
        opLog.setText("لا توجد عملية بعد.");
        opLog.setTextIsSelectable(true);
        opLog.setTextColor(0xFFCFEFC0);
        opLog.setTypeface(Typeface.MONOSPACE);
        opLog.setTextSize(11.5f);
        opLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        opLogScroll = new ScrollView(this);
        opLogScroll.setBackground(rounded(LOG_BG, 10));
        opLogScroll.addView(opLog);
        opLogScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(220)));
        logCard.addView(opLogScroll);
        page3.addView(logCard);

        copyLogBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("local-ai-log", opLog.getText().toString()));
            Toast.makeText(this, "تم نسخ السجل", Toast.LENGTH_SHORT).show();
        });

        Button back = secondaryBtn("← العودة إلى L2S Builder");
        back.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams backLp = (LinearLayout.LayoutParams) back.getLayoutParams();
        backLp.topMargin = dp(14);
        content.addView(back);
        back.setOnClickListener(v -> { settingsDialog.dismiss(); finish(); });

        dScroll.addView(content);
        dialogRoot.addView(dScroll);

        settingsDialog.setContentView(dialogRoot);
        android.view.Window w = settingsDialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        }
    }

    // ===================== الحالة والذاكرة =====================

    // ملف llama-cli حقيقي مبني لأندرويد (حتى النسخ المصغّرة منه) لا يكون بهذا الصغر أبدًا؛ أي شيء أقل من هذا
    // يكاد يكون مؤكدًا تنزيلًا ناقصًا أو صفحة خطأ حُفظت باسم الملف بالخطأ، حتى لو حمل توقيع ELF صالحًا شكليًا.
    private static final long MIN_PLAUSIBLE_BINARY_BYTES = 300 * 1024;

    private void refreshStatus() {
        File bin = binaryFile();
        boolean elf = bin.exists() && isElf(bin);
        String engineText; int color;
        if (!bin.exists()) { engineText = "المحرك: غير موجود"; color = CARD_ALT; }
        else if (!elf) { engineText = "المحرك: ملف موجود لكن ليس ثنائيًا صالحًا (" + human(bin.length()) + ")"; color = DANGER; }
        else if (bin.length() < MIN_PLAUSIBLE_BINARY_BYTES) {
            engineText = "المحرك: ⚠ الحجم صغير جدًا وغير منطقي (" + human(bin.length()) + ") — على الأرجح تنزيل ناقص، أعد التنزيل";
            color = WARN;
        } else { engineText = "المحرك: llama-cli موجود وصالح (" + human(bin.length()) + ")"; color = ACCENT_2; }

        // نعتمد على قائمة models كما هي محفوظة حاليًا (يحدّثها refreshModels() عند كل تنزيل/استيراد/حذف)
        // بدل إعادة مسحها من القرص هنا، تفاديًا لكسر تزامنها مع ترتيب القائمة المنسدلة المعروضة للمستخدم.
        String modelText;
        if (models.isEmpty()) { modelText = " | النموذج: لا يوجد GGUF بعد"; if (color != DANGER) color = WARN; }
        else modelText = " | النماذج: " + models.size() + " (" + human(minModel().length()) + " فأكثر)";

        statusPill.setText("الحالة: " + engineText + modelText);
        statusPill.setBackground(rounded(color, 10));

        long max = Runtime.getRuntime().maxMemory();
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        ramPill.setText("RAM للعملية: " + human(used) + " مستخدم / " + human(max) + " حد JVM");
        refreshInstalledCard();
    }

    /** يحدّث بطاقة "ما المُثبَّت الآن" بحالة حقيقية للمحرك والنماذج المثبتة فعليًا على الجهاز. */
    private void refreshInstalledCard() {
        if (installedStatusText == null) return;
        StringBuilder sb = new StringBuilder();
        File bin = binaryFile();
        sb.append("⚙️ المحرك:\n");
        if (!bin.exists()) {
            sb.append("  ✘ غير مثبت — نزّله أو استورده من بطاقة \"محرك التشغيل\" أدناه.\n");
        } else {
            boolean elf = isElf(bin);
            sb.append("  ").append(elf ? "✔ llama-cli مثبت وصالح" : "✘ ملف موجود لكن غير صالح")
                    .append(" — ").append(human(bin.length()));
            if (elf) sb.append(" — ").append(elfArchName(bin));
            sb.append("\n");
        }
        sb.append("\n🧠 النموذج/النماذج المثبتة:\n");
        if (models.isEmpty()) {
            sb.append("  ✘ لا يوجد نموذج GGUF مثبت بعد.\n");
        } else {
            for (File m : models) sb.append("  • ").append(m.getName()).append(" — ").append(human(m.length())).append("\n");
        }
        sb.append("\n🗂️ أدوات الذكاء الاصطناعي المدمجة:\n");
        sb.append("  • 📄 مساعد ملف واحد (تحليل / اقتراح تعديل + Diff)\n");
        sb.append("  • 📁 مساعد مشروع كامل (تحليل شامل + اقتراح ميزات)\n");
        sb.append("  كلاهما جاهز من تبويب «🗂️ المساعد» أعلاه، بشرط تثبيت محرك ونموذج صالحَين.");
        installedStatusText.setText(sb.toString());
    }

    private String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        if (n < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", n / 1048576.0);
        return String.format(Locale.US, "%.1f GB", n / 1073741824.0);
    }

    // ===================== النماذج =====================

    private void refreshModels() {
        String prevSelected = null;
        int prevPos = modelSpinner.getSelectedItemPosition();
        if (prevPos >= 0 && prevPos < models.size()) prevSelected = models.get(prevPos).getName();
        models.clear();
        File[] fs = modelsDir().listFiles();
        if (fs != null) {
            Arrays.sort(fs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : fs) if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".gguf")) models.add(f);
        }
        String[] labels = new String[models.size()];
        for (int i = 0; i < models.size(); i++) labels[i] = models.get(i).getName() + " (" + human(models.get(i).length()) + ")";
        if (labels.length == 0) labels = new String[]{"لا يوجد نموذج GGUF"};
        modelSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        // نعيد اختيار نفس النموذج الذي كان مختارًا سابقًا (أو آخر نموذج اخترته يدويًا وحُفظ) بدل الرجوع دائمًا لأول نموذج أبجديًا.
        if (!models.isEmpty()) {
            String remembered = prevSelected != null ? prevSelected
                    : getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SELECTED_MODEL, null);
            int restore = -1;
            if (remembered != null) {
                for (int i = 0; i < models.size(); i++) if (models.get(i).getName().equals(remembered)) { restore = i; break; }
            }
            if (restore >= 0) modelSpinner.setSelection(restore);
        }
        refreshInstalledCard();
        refreshModelListContainer();
    }

    /** يعرض كل النماذج المثبَّتة كقائمة، مع زر 🗑️ لحذف أي واحد مباشرة دون الحاجة لاختياره في القائمة المنسدلة أولًا. */
    private void refreshModelListContainer() {
        if (modelListContainer == null) return;
        modelListContainer.removeAllViews();
        if (models.isEmpty()) {
            modelListContainer.addView(hint("لا توجد نماذج مثبّتة بعد."));
            return;
        }
        String selectedName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SELECTED_MODEL, null);
        for (File f : models) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            rowL.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            boolean isSelected = f.getName().equals(selectedName);
            t.setText((isSelected ? "✅ " : "• ") + f.getName() + " (" + human(f.length()) + ")");
            t.setTextColor(TEXT_SUB);
            t.setTextSize(11.5f);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            Button del = secondaryBtn("🗑️");
            if (models.size() <= 1) {
                del.setEnabled(false);
                del.setAlpha(0.4f);
            } else {
                del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                        .setTitle("حذف النموذج")
                        .setMessage((isSelected ? "⚠️ هذا هو النموذج المختار حاليًا.\n\n" : "") + "حذف " + f.getName() + "؟")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("حذف", (d, w) -> {
                            f.delete();
                            if (isSelected) getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_SELECTED_MODEL).apply();
                            refreshModels();
                            Toast.makeText(this, "تم حذف " + f.getName(), Toast.LENGTH_SHORT).show();
                        }).show());
            }
            rowL.addView(t);
            rowL.addView(del);
            modelListContainer.addView(rowL);
        }
    }

    private void pickModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_MODEL);
    }

    private void pickBinary() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_BINARY);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (req == SAVE_MODEL_COPY) pendingModelCopySource = null; // ألغى المستخدم اختيار الوجهة
            return;
        }
        Uri u = data.getData();
        if (req == PICK_MODEL) importModel(u);
        else if (req == PICK_BINARY) importBinary(u);
        else if (req == SAVE_ENGINE_COPY) doSaveEngineCopy(u);
        else if (req == SAVE_MODEL_COPY) doSaveModelCopy(u);
    }

    private void importModel(Uri u) {
        String name = displayName(u);
        if (name == null || !name.toLowerCase(Locale.US).endsWith(".gguf")) {
            Toast.makeText(this, "اختر ملف GGUF", Toast.LENGTH_LONG).show(); return;
        }
        File dst = uniqueFile(modelsDir(), name);
        startProtectedTask("استيراد النموذج", () -> {
            try {
                copyUri(u, dst);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            runOnUiThread(() -> { refreshModels(); Toast.makeText(this, "تم استيراد " + dst.getName(), Toast.LENGTH_LONG).show(); });
        });
    }

    private void importBinary(Uri u) {
        File dst = binaryFile();
        startProtectedTask("استيراد llama-cli", () -> {
            try {
                File tmp = new File(binDir(), "llama-cli.tmp");
                copyUri(u, tmp);
                if (!tmp.renameTo(dst)) {
                    copyFile(tmp, dst); tmp.delete();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            dst.setExecutable(true, false);
            runOnUiThread(() -> { refreshStatus(); Toast.makeText(this, "تم استيراد llama-cli", Toast.LENGTH_LONG).show(); });
        });
    }

    private String displayName(Uri u) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{"_display_name"}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {} finally { if (c != null) c.close(); }
        String p = u.getLastPathSegment();
        return p == null ? "model.gguf" : p;
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        int i = 2;
        while ((f = new File(dir, base + "-" + i++ + ext)).exists()) {}
        return f;
    }

    private void copyUri(Uri u, File dst) throws Exception {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("تعذر فتح الملف");
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[1024 * 1024]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { try { in.close(); } catch (Throwable ignored) {} try { out.close(); } catch (Throwable ignored) {} }
    }

    private void copyFile(File a, File b) throws Exception {
        FileInputStream in = new FileInputStream(a);
        FileOutputStream out = new FileOutputStream(b);
        try {
            byte[] buf = new byte[1024 * 1024]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { in.close(); out.close(); }
    }

    private void deleteSelectedModel() {
        int p = modelSpinner.getSelectedItemPosition();
        if (p < 0 || p >= models.size()) return;
        File f = models.get(p);
        new android.app.AlertDialog.Builder(this).setTitle("حذف النموذج")
                .setMessage("حذف " + f.getName() + "؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (d, w) -> { f.delete(); refreshModels(); }).show();
    }

    // ===================== حفظ نسخة احتياطية (المحرك كـ ZIP / النموذج كملف مباشر) عبر منتقي الملفات =====================

    /** يفتح منتقي "حفظ باسم" ليختار المستخدم مكان حفظ نسخة ZIP من المحرك (llama-cli + كل مكتبات .so). */
    private void saveEngineCopy() {
        File[] binFiles = binDir().listFiles();
        if (binFiles == null || binFiles.length == 0 || !binaryFile().exists()) {
            Toast.makeText(this, "لا يوجد محرك منزّل بعد ليتم حفظ نسخة منه", Toast.LENGTH_LONG).show();
            return;
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, "llama-engine-backup-" + stamp + ".zip");
        startActivityForResult(i, SAVE_ENGINE_COPY);
    }

    private void doSaveEngineCopy(Uri dst) {
        Toast.makeText(this, "جارٍ ضغط ملفات المحرك وحفظها...", Toast.LENGTH_SHORT).show();
        startProtectedTask("حفظ نسخة المحرك", () -> {
            try {
                zipDirToUri(binDir(), dst, "حفظ نسخة المحرك");
                runOnUiThread(() -> {
                    setLog("✔ تم حفظ نسخة كاملة من المحرك (llama-cli + كل مكتبات .so) في الموقع الذي اخترته.\nيمكنك لاحقًا استيراد هذه النسخة (بعد فك ضغطها) بدل إعادة التنزيل من الإنترنت.");
                    Toast.makeText(this, "تم حفظ نسخة المحرك بنجاح", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable e) {
                final String msg = "فشل حفظ نسخة المحرك.\nنوع الخطأ: " + e.getClass().getSimpleName() + "\nالرسالة: " + e.getMessage();
                runOnUiThread(() -> { setLog(msg); Toast.makeText(this, "فشل الحفظ — التفاصيل في السجل", Toast.LENGTH_LONG).show(); });
            }
        });
    }

    /** يفتح منتقي "حفظ باسم" ليختار المستخدم مكان حفظ نسخة من ملف GGUF المحدد حاليًا في القائمة. */
    private void saveModelCopy() {
        int p = modelSpinner.getSelectedItemPosition();
        if (p < 0 || p >= models.size()) {
            Toast.makeText(this, "اختر نموذجًا من القائمة أولًا", Toast.LENGTH_LONG).show();
            return;
        }
        pendingModelCopySource = models.get(p);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, pendingModelCopySource.getName());
        startActivityForResult(i, SAVE_MODEL_COPY);
    }

    private void doSaveModelCopy(Uri dst) {
        final File src = pendingModelCopySource;
        pendingModelCopySource = null;
        if (src == null || !src.exists()) {
            Toast.makeText(this, "تعذّر العثور على النموذج المحدد", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "جارٍ حفظ نسخة من " + src.getName() + " ...", Toast.LENGTH_SHORT).show();
        startProtectedTask("حفظ نسخة النموذج", () -> {
            try {
                copyFileToUriWithProgress(src, dst, "حفظ نسخة النموذج");
                runOnUiThread(() -> {
                    setLog("✔ تم حفظ نسخة من النموذج \"" + src.getName() + "\" (" + human(src.length()) + ") في الموقع الذي اخترته.");
                    Toast.makeText(this, "تم حفظ نسخة النموذج بنجاح", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable e) {
                final String msg = "فشل حفظ نسخة النموذج.\nنوع الخطأ: " + e.getClass().getSimpleName() + "\nالرسالة: " + e.getMessage();
                runOnUiThread(() -> { setLog(msg); Toast.makeText(this, "فشل الحفظ — التفاصيل في السجل", Toast.LENGTH_LONG).show(); });
            }
        });
    }

    /** يضغط كل الملفات المباشرة (بدون مجلدات فرعية) داخل dir إلى أرشيف ZIP واحد يُكتب مباشرة في وجهة SAF. */
    private void zipDirToUri(File dir, Uri dst, String label) throws Exception {
        OutputStream rawOut = getContentResolver().openOutputStream(dst);
        if (rawOut == null) throw new IOException("تعذر فتح الوجهة للكتابة");
        File[] files = dir.listFiles();
        long totalBytes = 0;
        if (files != null) for (File f : files) if (f.isFile()) totalBytes += f.length();
        long done = 0;
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut));
        try {
            if (files != null) {
                byte[] buf = new byte[1024 * 1024];
                for (File f : files) {
                    if (!f.isFile()) continue;
                    zos.putNextEntry(new ZipEntry(f.getName()));
                    FileInputStream in = new FileInputStream(f);
                    try {
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            zos.write(buf, 0, n);
                            done += n;
                            final long fdone = done, ftot = totalBytes;
                            runOnUiThread(() -> setLog(label + ": " + (ftot > 0 ? (fdone * 100 / ftot) + "% — " : "")
                                    + human(fdone) + (ftot > 0 ? "/" + human(ftot) : "")));
                        }
                    } finally { try { in.close(); } catch (Throwable ignored) {} }
                    zos.closeEntry();
                }
            }
        } finally { try { zos.close(); } catch (Throwable ignored) {} }
    }

    /** ينسخ ملفًا واحدًا بايتًا-ببايت إلى وجهة SAF (Uri) مع تحديث حي لصندوق السجل بنسبة التقدّم. */
    private void copyFileToUriWithProgress(File src, Uri dst, String label) throws Exception {
        OutputStream out = getContentResolver().openOutputStream(dst);
        if (out == null) throw new IOException("تعذر فتح الوجهة للكتابة");
        long total = src.length(), done = 0;
        FileInputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[1024 * 1024]; int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n); done += n;
                final long fdone = done, ftot = total;
                runOnUiThread(() -> setLog(label + ": " + (ftot > 0 ? (fdone * 100 / ftot) + "% — " : "")
                        + human(fdone) + (ftot > 0 ? "/" + human(ftot) : "")));
            }
        } finally { try { in.close(); } catch (Throwable ignored) {} try { out.close(); } catch (Throwable ignored) {} }
    }

    // ===================== نظام التنزيل الموحّد (طريقتان: تلقائي شامل / اختيار رابط واحد) =====================

    /** نتيجة محاولة تنزيل واحدة، مع تفاصيل تشخيصية كاملة سواء نجحت أو فشلت. */
    private static class AttemptResult {
        boolean success;
        String detail;
        File output;
    }

    /** طريقة 2: نافذة اختيار رابط واحد محدد من القائمة، للمحرك أو للنموذج. */
    private void pickOneUrlDialog(ArrayList<String> urls, boolean isBinary) {
        if (urls.isEmpty()) {
            Toast.makeText(this, "لا توجد روابط في الحقل لاختيار واحد منها", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            String u = urls.get(i);
            String shortName = u.substring(u.lastIndexOf('/') + 1);
            if (shortName.contains("?")) shortName = shortName.substring(0, shortName.indexOf('?'));
            labels[i] = (i + 1) + ") " + shortName;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("اختر رابطًا واحدًا للتجربة")
                .setItems(labels, (dialog, which) -> {
                    if (isBinary) downloadBinaryUrls(Collections.singletonList(urls.get(which)));
                    else downloadModelUrls(Collections.singletonList(urls.get(which)));
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void downloadAllBinaryUrls() {
        ArrayList<String> urls = collectUrls(binUrlList);
        if (urls.isEmpty()) { Toast.makeText(this, "أضف رابطًا واحدًا على الأقل بزر ➕", Toast.LENGTH_LONG).show(); return; }
        downloadBinaryUrls(urls);
    }

    private void downloadAllModelUrls() {
        ArrayList<String> urls = collectUrls(modelUrlList);
        if (urls.isEmpty()) { Toast.makeText(this, "أضف رابطًا واحدًا على الأقل بزر ➕", Toast.LENGTH_LONG).show(); return; }
        downloadModelUrls(urls);
    }

    private void downloadBinaryUrls(List<String> urls) {
        if (downloadingBin.getAndSet(true)) return;
        setLog("جارٍ البدء — سيتم تجربة " + urls.size() + " رابط بالترتيب حتى ينجح أحدها...");
        startProtectedTask("تنزيل llama-cli", () -> {
            StringBuilder fullLog = new StringBuilder();
            boolean success = false;
            for (int idx = 0; idx < urls.size(); idx++) {
                String u = urls.get(idx);
                AttemptResult r = attemptDownloadBinary(u, idx + 1, urls.size());
                fullLog.append("— رابط ").append(idx + 1).append("/").append(urls.size()).append(" —\n")
                        .append(u).append('\n')
                        .append(r.success ? "✔ نجح\n" : "✘ فشل\n")
                        .append(r.detail).append("\n\n");
                if (r.success) { success = true; break; }
            }
            final String finalLog = fullLog.toString().trim();
            final boolean finalSuccess = success;
            runOnUiThread(() -> {
                setLog(finalLog);
                if (finalSuccess) { refreshStatus(); Toast.makeText(this, "نجح تنزيل المحرك — شغّل فحص الجاهزية الآن", Toast.LENGTH_LONG).show(); }
                else Toast.makeText(this, "فشلت كل الروابط — التفاصيل في السجل", Toast.LENGTH_LONG).show();
            });
            downloadingBin.set(false);
        });
    }

    private void downloadModelUrls(List<String> urls) {
        if (downloading.getAndSet(true)) return;
        setLog("جارٍ البدء — سيتم تجربة " + urls.size() + " رابط بالترتيب حتى ينجح أحدها...");
        startProtectedTask("تنزيل النموذج", () -> {
            StringBuilder fullLog = new StringBuilder();
            boolean success = false;
            for (int idx = 0; idx < urls.size(); idx++) {
                String u = urls.get(idx);
                AttemptResult r = attemptDownloadModel(u, idx + 1, urls.size());
                fullLog.append("— رابط ").append(idx + 1).append("/").append(urls.size()).append(" —\n")
                        .append(u).append('\n')
                        .append(r.success ? "✔ نجح\n" : "✘ فشل\n")
                        .append(r.detail).append("\n\n");
                if (r.success) { success = true; break; }
            }
            final String finalLog = fullLog.toString().trim();
            final boolean finalSuccess = success;
            runOnUiThread(() -> {
                setLog(finalLog);
                if (finalSuccess) { refreshModels(); refreshStatus(); Toast.makeText(this, "نجح تنزيل النموذج", Toast.LENGTH_LONG).show(); }
                else Toast.makeText(this, "فشلت كل الروابط — التفاصيل في السجل", Toast.LENGTH_LONG).show();
            });
            downloading.set(false);
        });
    }

    private AttemptResult attemptDownloadModel(String u, int attemptNum, int total) {
        AttemptResult result = new AttemptResult();
        String name = u.substring(u.lastIndexOf('/') + 1);
        if (name.contains("?")) name = name.substring(0, name.indexOf('?'));
        if (!name.toLowerCase(Locale.US).endsWith(".gguf")) name += ".gguf";
        // مهم: اسم الملف المؤقت أثناء التنزيل ثابت ومبنيّ على اسم النموذج نفسه (وليس "اسمًا فريدًا" جديدًا
        // في كل مرة عبر uniqueFile) — وإلا فكل ضغطة "تنزيل" جديدة بعد انقطاع طويل أو إعادة فتح التطبيق
        // كانت تُنشئ ملفًا باسم مختلف (model (1).gguf.tmp مثلًا)، فيبدأ الاستئناف (Range) من الصفر لأنه
        // لا يرى الجزء المحمّل سابقًا أصلًا. الآن يُعاد استخدام نفس ".partial" في كل محاولة لنفس الرابط.
        File dst = new File(modelsDir(), name + ".partial");
        try {
            runOnUiThread(() -> setLog("تجربة الرابط " + attemptNum + " من " + total + " ...\n" + u));
            DownloadOutcome o = httpDownload(u, dst, attemptNum, total, "تنزيل النموذج");
            if (!o.ok) { result.success = false; result.detail = o.detail; return result; } // الملف الجزئي يبقى محفوظًا للاستئناف لاحقًا
            // اكتمل التنزيل: أعد تسميته إلى اسمه النهائي الآن فقط (بعيدًا عن أي تصادم مع نموذج آخر مكتمل بالاسم نفسه)
            File finalDst = uniqueFile(modelsDir(), name);
            if (!dst.renameTo(finalDst)) { copyFile(dst, finalDst); dst.delete(); }
            result.success = true;
            result.output = finalDst;
            result.detail = "تم حفظ النموذج: " + finalDst.getName() + "\nالحجم: " + human(finalDst.length());
            return result;
        } catch (Throwable e) {
            // لا نحذف الملف الجزئي هنا عمدًا: قد يكون الخطأ عابرًا (مثل انقطاع غير متوقع)، ونريد أن تتمكن
            // محاولة لاحقة بنفس الرابط من استئنافه بدل إعادة التنزيل بالكامل من جديد.
            result.success = false;
            result.detail = "نوع الخطأ: " + e.getClass().getSimpleName() + "\nالرسالة: " + (e.getMessage() == null ? "(بدون رسالة)" : e.getMessage())
                    + (dst.exists() ? "\nتم حفظ التقدم الجزئي (" + human(dst.length()) + ") — أعد الضغط على تنزيل لاستئنافه." : "");
            return result;
        }
    }

    private AttemptResult attemptDownloadBinary(String u, int attemptNum, int total) {
        AttemptResult result = new AttemptResult();
        File tmp = new File(binDir(), "download.tmp");
        try {
            runOnUiThread(() -> setLog("تجربة الرابط " + attemptNum + " من " + total + " ...\n" + u));
            DownloadOutcome o = httpDownload(u, tmp, attemptNum, total, "تنزيل المحرك");
            if (!o.ok) { result.success = false; result.detail = o.detail; return result; }

            String lowerUrl = u.toLowerCase(Locale.US);
            File finalBin;
            if (lowerUrl.endsWith(".tar.gz") || lowerUrl.endsWith(".tgz")) {
                runOnUiThread(() -> setLog("استخراج الأرشيف (" + attemptNum + "/" + total + ")..."));
                List<String> extracted;
                try {
                    extracted = extractTarGz(tmp, binDir());
                } catch (Throwable ex) {
                    result.success = false;
                    result.detail = "تم تنزيل الأرشيف (" + human(tmp.length()) + ") لكن فشل استخراجه.\n"
                            + "نوع الخطأ: " + ex.getClass().getSimpleName() + "\nالرسالة: " + ex.getMessage();
                    tmp.delete();
                    return result;
                }
                tmp.delete();
                File found = null;
                for (String name : extracted) if (name.equals("llama-cli")) { found = new File(binDir(), name); break; }
                if (found == null) {
                    result.success = false;
                    result.detail = "تم استخراج الأرشيف بنجاح لكن لا يحتوي ملفًا باسم llama-cli.\nالملفات المستخرجة (" + extracted.size() + "): " + extracted;
                    return result;
                }
                for (String name : extracted) new File(binDir(), name).setExecutable(true, false);
                if (!isElf(found)) {
                    result.success = false;
                    result.detail = "تم استخراج llama-cli لكنه ليس ملفًا تنفيذيًا صالحًا (لا يحمل توقيع ELF). قد يكون الأرشيف تالفًا.\nحجم الملف: " + human(found.length());
                    return result;
                }
                int soCount = 0;
                for (String name : extracted) if (name.toLowerCase(Locale.US).endsWith(".so")) soCount++;
                finalBin = found;
                result.detail = "نجح الاستخراج.\nالملفات المستخرجة (" + extracted.size() + ")، منها " + soCount + " مكتبة .so\n"
                        + "حجم llama-cli: " + human(finalBin.length()) + " (طبيعي أن يكون صغيرًا؛ هو ملف تشغيل خفيف يعتمد على مكتبات .so المرفقة معه)\n"
                        + "المعمارية: " + elfArchName(finalBin);
            } else {
                File dst = binaryFile();
                if (!tmp.renameTo(dst)) { copyFile(tmp, dst); tmp.delete(); }
                dst.setExecutable(true, false);
                if (!isElf(dst)) {
                    result.success = false;
                    result.detail = "تم تنزيل الملف لكنه ليس ثنائيًا صالحًا (لا يحمل توقيع ELF).\nحجم الملف: " + human(dst.length());
                    return result;
                }
                finalBin = dst;
                result.detail = "تم حفظه كملف مباشر.\nالحجم: " + human(finalBin.length()) + "\nالمعمارية: " + elfArchName(finalBin);
            }
            result.success = true;
            result.output = finalBin;
            return result;
        } catch (Throwable e) {
            // نُبقي download.tmp كما هو عمدًا (اسمه ثابت عبر كل المحاولات) ليتمكن الضغط على تنزيل مرة
            // أخرى من استئنافه بدل البدء من الصفر، بدل حذفه هنا كما كان سابقًا.
            result.success = false;
            result.detail = "نوع الخطأ: " + e.getClass().getSimpleName() + "\nالرسالة: " + (e.getMessage() == null ? "(بدون رسالة)" : e.getMessage())
                    + (tmp.exists() ? "\nتم حفظ التقدم الجزئي (" + human(tmp.length()) + ") — أعد الضغط على تنزيل لاستئنافه." : "");
            return result;
        }
    }

    private static class DownloadOutcome { boolean ok; String detail; }

    /**
     * أقصى عدد محاولات إعادة اتصال متتالية عند انقطاع الشبكة أثناء تنزيل واحد (بخلاف تعدد الروابط نفسها).
     * قابل للتعديل من ⚙️ الإعدادات → "إعدادات متقدمة" بدل كونه رقمًا ثابتًا في الكود — الملفات الكبيرة على
     * شبكة بطيئة قد تحتاج رقمًا أعلى، بينما شبكة سريعة ومستقرة لا تحتاج أكثر من القليل. المحفوظ افتراضيًا 30.
     * حتى لو استُنفدت كل المحاولات، الملف الجزئي يبقى محفوظًا (انظر المعالجة أدناه) فلا يضيع التقدم.
     */
    private static final String KEY_MAX_RESUME_RETRIES = "max_resume_retries";
    private static final int DEFAULT_MAX_RESUME_RETRIES = 30;
    private static final int MIN_MAX_RESUME_RETRIES = 1;
    private static final int MAX_MAX_RESUME_RETRIES = 300;

    private int getMaxResumeRetries() {
        int v = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_MAX_RESUME_RETRIES, DEFAULT_MAX_RESUME_RETRIES);
        return Math.max(MIN_MAX_RESUME_RETRIES, Math.min(MAX_MAX_RESUME_RETRIES, v));
    }

    private void setMaxResumeRetries(int v) {
        v = Math.max(MIN_MAX_RESUME_RETRIES, Math.min(MAX_MAX_RESUME_RETRIES, v));
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_MAX_RESUME_RETRIES, v).apply();
    }

    /**
     * تنزيل HTTP عام مع تقدم حي، واستئناف تلقائي (Range) عند انقطاع الاتصال منتصف التنزيل،
     * وتشخيص مفصّل عند الفشل النهائي (كود HTTP، نوع المحتوى، بداية رد السيرفر).
     */
    private DownloadOutcome httpDownload(String u, File dst, int attemptNum, int total, String label) throws Exception {
        DownloadOutcome o = new DownloadOutcome();
        String lastNetworkError = null;
        final int maxRetries = getMaxResumeRetries(); // من ⚙️ الإعدادات، وليس رقمًا ثابتًا

        for (int retry = 0; retry <= maxRetries; retry++) {
            long haveBytes = dst.exists() ? dst.length() : 0;
            boolean resuming = retry > 0 && haveBytes > 0;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) L2SBuilder-LocalAI");
                if (resuming) c.setRequestProperty("Range", "bytes=" + haveBytes + "-");
                c.connect();
                int code = c.getResponseCode();
                String finalUrl = c.getURL().toString();
                String contentType = c.getContentType();

                if (resuming && code == 200) {
                    // السيرفر لا يدعم الاستئناف (تجاهل Range) — يجب البدء من الصفر لتجنّب ملف مضاعف/تالف.
                    dst.delete();
                    haveBytes = 0;
                    resuming = false;
                }
                if (code < 200 || code >= 300) {
                    String errBody = "";
                    try {
                        InputStream es = c.getErrorStream();
                        if (es != null) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            byte[] b = new byte[512]; int n; int t = 0;
                            while ((n = es.read(b)) != -1 && t < 2000) { bo.write(b, 0, n); t += n; }
                            errBody = bo.toString("UTF-8");
                        }
                    } catch (Throwable ignored) {}
                    o.ok = false;
                    o.detail = "كود HTTP: " + code + "\nنوع المحتوى: " + contentType + "\nالرابط النهائي: " + finalUrl
                            + (errBody.isEmpty() ? "" : "\nبداية الرد:\n" + errBody.substring(0, Math.min(300, errBody.length())));
                    return o;
                }

                long remaining = c.getContentLengthLong();
                long tot = resuming && remaining > 0 ? haveBytes + remaining : (resuming ? -1 : remaining);
                long done = haveBytes;
                final int fretry = retry;
                InputStream in = c.getInputStream();
                FileOutputStream out = new FileOutputStream(dst, resuming);
                byte[] buf = new byte[1024 * 1024]; int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n); done += n;
                        final long fdone = done, ftot = tot;
                        runOnUiThread(() -> setLog(label + " (" + attemptNum + "/" + total + ")"
                                + (fretry > 0 ? " — استئناف بعد انقطاع #" + fretry : "") + ": "
                                + (ftot > 0 ? (fdone * 100 / ftot) + "% — " : "")
                                + human(fdone) + (ftot > 0 ? "/" + human(ftot) : "")));
                    }
                } finally {
                    try { out.close(); } catch (Throwable ignored) {}
                    try { in.close(); } catch (Throwable ignored) {}
                }

                if (dst.length() < 10 * 1024) {
                    String snippet = "";
                    try {
                        byte[] head = new byte[300];
                        try (FileInputStream fis = new FileInputStream(dst)) { int r = fis.read(head); if (r > 0) snippet = new String(head, 0, r, "UTF-8"); }
                    } catch (Throwable ignored) {}
                    o.ok = false;
                    o.detail = "تم التنزيل لكن الحجم صغير جدًا (" + human(dst.length()) + ") — على الأرجح صفحة خطأ.\nنوع المحتوى: " + contentType
                            + (snippet.isEmpty() ? "" : "\nمحتوى الملف المستلم:\n" + snippet);
                    dst.delete();
                    return o;
                }
                o.ok = true;
                return o;
            } catch (IOException e) {
                // انقطاع اتصال أثناء التنزيل (مثل ProtocolException: unexpected end of stream) — نعيد المحاولة مع الاستئناف
                // من نفس النقطة إن كان الملف الجزئي موجودًا، بدل اعتبار الرابط بأكمله فاشلًا.
                lastNetworkError = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "(بدون رسالة)" : e.getMessage());
                if (retry == maxRetries) {
                    // مهم: لا نحذف الملف الجزئي هنا بعد الآن. حذفه كان يعني أن أي انقطاع متكرر (شائع على
                    // شبكات الجوال الضعيفة) يمحو كل التقدم بالكامل ويجبر المستخدم على البدء من الصفر في
                    // كل مرة. الآن يبقى محفوظًا على القرص، وبما أن اسم الملف المؤقت ثابت (انظر
                    // attemptDownloadModel/attemptDownloadBinary)، الضغط على تنزيل مرة أخرى بنفس الرابط
                    // سيستأنف تلقائيًا من نفس النقطة عبر Range بدل إعادة التنزيل بالكامل.
                    long partialSize = dst.exists() ? dst.length() : 0;
                    o.ok = false;
                    o.detail = "انقطع الاتصال أثناء التنزيل وفشلت " + (maxRetries + 1) + " محاولة استئناف متتالية (الشبكة غير مستقرة).\n"
                            + "آخر خطأ شبكة: " + lastNetworkError
                            + (partialSize > 0
                                    ? "\n✔ التقدم محفوظ (" + human(partialSize) + ") — اضغط تنزيل بنفس الرابط مرة أخرى للاستئناف من هذه النقطة بدل البدء من الصفر."
                                    : "")
                            + "\n(يمكنك رفع الحد الأقصى للمحاولات من ⚙️ الإعدادات → إعدادات متقدمة إن كانت شبكتك كثيرة الانقطاع)";
                    return o;
                }
                final int attemptShown = retry + 2;
                final String errShown = lastNetworkError;
                final long haveShown = dst.exists() ? dst.length() : 0;
                final int maxShown = maxRetries;
                runOnUiThread(() -> setLog(label + " (" + attemptNum + "/" + total + "): انقطع الاتصال (" + errShown + ")\n"
                        + "إعادة المحاولة رقم " + attemptShown + " من " + (maxShown + 1)
                        + (haveShown > 0 ? " — استئناف من " + human(haveShown) : " — من البداية") + " بعد قليل..."));
                try { Thread.sleep(Math.min(1500L * (retry + 1), 8000L)); } catch (InterruptedException ignored) {}
                // اللفة التالية تعيد المحاولة تلقائيًا (الملف الجزئي محفوظ على القرص للاستئناف منه)
            } finally { if (c != null) c.disconnect(); }
        }
        // لا يصل الكود إلى هنا عمليًا (كل المسارات تُرجع داخل الحلقة)
        o.ok = false;
        o.detail = "فشل غير متوقع.\n" + (lastNetworkError != null ? "آخر خطأ: " + lastNetworkError : "");
        return o;
    }

    // ===================== فحص ELF (يميّز ثنائيًا حقيقيًا عن ملف تالف مهما كان صغيرًا) =====================

    private boolean isElf(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] h = new byte[20];
            int r = readFully(fis, h);
            if (r < 20) return false;
            return h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        } catch (Throwable e) { return false; }
    }

    /** يقرأ حقل e_machine من رأس ELF لتحديد المعمارية (0xB7 = AArch64، 0x28 = ARM 32-بت). */
    private String elfArchName(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] h = new byte[20];
            int r = readFully(fis, h);
            if (r < 20 || !(h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F')) return "غير معروف";
            boolean is64 = h[4] == 2;
            int machine = (h[18] & 0xFF) | ((h[19] & 0xFF) << 8);
            String archName;
            if (machine == 0xB7) archName = "AArch64 (ARM 64-بت) ✔ متوافق مع أغلب الأجهزة الحديثة";
            else if (machine == 0x28) archName = "ARM 32-بت — قد لا يعمل إن كان جهازك arm64-v8a فقط";
            else if (machine == 0x3E) archName = "x86_64 — غير متوافق مع معالج ARM";
            else archName = "معرّف معمارية غير معروف (" + machine + ")";
            return archName + (is64 ? "" : " [32-بت]");
        } catch (Throwable e) { return "تعذر القراءة"; }
    }

    // ===================== مستخرج TAR.GZ =====================

    private List<String> extractTarGz(File tarGzFile, File destDir) throws Exception {
        ArrayList<String> names = new ArrayList<>();
        try (InputStream fis = new FileInputStream(tarGzFile);
             GZIPInputStream gis = new GZIPInputStream(fis)) {
            byte[] header = new byte[512];
            while (true) {
                int r = readFully(gis, header);
                if (r < 512) break;
                boolean allZero = true;
                for (byte b : header) if (b != 0) { allZero = false; break; }
                if (allZero) break;

                String name = new String(header, 0, 100, "UTF-8");
                int nul = name.indexOf('\0');
                if (nul >= 0) name = name.substring(0, nul);
                String sizeStr = new String(header, 124, 12, "UTF-8").trim();
                long size = 0;
                try { size = sizeStr.isEmpty() ? 0 : Long.parseLong(sizeStr.replace("\0", ""), 8); } catch (Throwable ignored) {}
                char typeFlag = (char) header[156];
                long paddedSize = ((size + 511) / 512) * 512;

                if (typeFlag == '0' || typeFlag == 0) {
                    String baseName = name;
                    int slash = baseName.lastIndexOf('/');
                    if (slash >= 0) baseName = baseName.substring(slash + 1);
                    if (!baseName.isEmpty()) {
                        File outFile = new File(destDir, baseName);
                        // عند تكرار الاسم (بعض الأرشيفات تضع نسخًا بمسارات مختلفة)، نحتفظ بالنسخة الأكبر فقط
                        // لتفادي استبدال الملف الصحيح بملف غلاف/رابط صغير عن طريق الخطأ.
                        boolean keepExisting = outFile.exists() && outFile.length() > size && size > 0;
                        if (keepExisting) {
                            skipFully(gis, paddedSize);
                            if (!names.contains(baseName)) names.add(baseName);
                        } else {
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                long toRead = size;
                                byte[] buf = new byte[65536];
                                while (toRead > 0) {
                                    int n = gis.read(buf, 0, (int) Math.min(buf.length, toRead));
                                    if (n < 0) break;
                                    fos.write(buf, 0, n);
                                    toRead -= n;
                                }
                            }
                            if (!names.contains(baseName)) names.add(baseName);
                            skipFully(gis, paddedSize - size);
                        }
                    } else {
                        skipFully(gis, paddedSize);
                    }
                } else {
                    skipFully(gis, paddedSize);
                }
            }
        }
        return names;
    }

    private int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private void skipFully(InputStream in, long count) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) break;
            remaining -= n;
        }
    }

    // ===================== فحص الجاهزية الحقيقي (ELF + مكتبات + تشغيل تجريبي فعلي) =====================

    private void checkDeviceReadiness() {
        setLog("جارٍ فحص الجاهزية...");
        startProtectedTask("فحص الجاهزية", () -> {
            StringBuilder r = new StringBuilder();
            boolean allGood = true;

            r.append("=== 1) ملف المحرك ===\n");
            File bin = binaryFile();
            if (!bin.exists()) {
                r.append("✘ لا يوجد ملف llama-cli. نزّله أولًا من بطاقة المحرك أعلاه.\n");
                allGood = false;
            } else if (!isElf(bin)) {
                r.append("✘ الملف الموجود ليس ثنائيًا صالحًا (لا يحمل توقيع ELF). أعد التنزيل.\n");
                allGood = false;
            } else {
                r.append("✔ ملف صالح — الحجم: ").append(human(bin.length())).append('\n');
                r.append("  المعمارية: ").append(elfArchName(bin)).append('\n');
                r.append("  معمارية جهازك: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
                boolean archMatches = elfArchName(bin).contains("AArch64") &&
                        Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
                r.append(archMatches ? "  ✔ المعمارية متوافقة مع جهازك\n" : "  ⚠ تحقق يدويًا من توافق المعمارية\n");
            }

            r.append("\n=== 2) المكتبات المشتركة (.so) ===\n");
            File[] binFiles = binDir().listFiles();
            int soCount = 0;
            if (binFiles != null) for (File f : binFiles) if (f.getName().toLowerCase(Locale.US).endsWith(".so")) soCount++;
            if (soCount == 0) {
                r.append("⚠ لا توجد ملفات .so في مجلد المحرك. إن كان محركك من أرشيف tar.gz حديث فهذا غير طبيعي وقد يفشل التشغيل.\n");
            } else {
                r.append("✔ موجود ").append(soCount).append(" ملف .so\n");
            }

            r.append("\n=== 3) النموذج ===\n");
            refreshModelsSync();
            if (models.isEmpty()) {
                r.append("✘ لا يوجد نموذج GGUF بعد. نزّل نموذجًا من بطاقة النموذج أعلاه.\n");
                allGood = false;
            } else {
                r.append("✔ يوجد ").append(models.size()).append(" نموذج، أصغرها: ")
                        .append(minModel().getName()).append(" (").append(human(minModel().length())).append(")\n");
            }

            r.append("\n=== 4) الذاكرة ===\n");
            long freeRam = freeSystemRamBytes();
            r.append("الذاكرة الحرة التقريبية للنظام: ").append(freeRam > 0 ? human(freeRam) : "غير معروفة").append('\n');
            if (!models.isEmpty() && freeRam > 0) {
                long need = minModel().length() * 2; // تقدير تقريبي: حجم النموذج × 2 تقريبًا لتشغيله + الذاكرة المؤقتة
                if (freeRam < need) r.append("⚠ الذاكرة الحرة أقل من التقدير المطلوب (~").append(human(need)).append(") — قد يتوقف التشغيل بسبب نقص الذاكرة.\n");
                else r.append("✔ الذاكرة الحرة تبدو كافية لأصغر نموذج لديك.\n");
            }

            r.append("\n=== 5) تشغيل تجريبي فعلي ===\n");
            if (bin.exists() && isElf(bin)) {
                runOnUiThread(() -> setLog(r.toString() + "\nجارٍ تشغيل llama-cli --version فعليًا على معالج جهازك..."));
                try {
                    ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--version");
                    pb.environment().put("LD_LIBRARY_PATH", binDir().getAbsolutePath());
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = Compat.waitFor(p, 8, TimeUnit.SECONDS);
                    if (!finished) {
                        p.destroy();
                        r.append("⚠ لم يستجب المحرك خلال 8 ثوانٍ (قد يكون طبيعيًا لبعض الأجهزة البطيئة، أو مؤشر مشكلة).\n");
                    } else {
                        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        StringBuilder out = new StringBuilder();
                        String line; int lines = 0;
                        while ((line = br.readLine()) != null && lines < 15) { out.append(line).append('\n'); lines++; }
                        int code = p.exitValue();
                        if (code == 0 || out.length() > 0) {
                            r.append("✔ تم تشغيل المحرك فعليًا على معالج جهازك، رمز الخروج: ").append(code).append('\n');
                            r.append("مخرجات المحرك:\n").append(out.length() > 0 ? out.toString() : "(بدون مخرجات نصية، لكن التشغيل تم دون تعطّل)");
                        } else {
                            r.append("✘ انتهى المحرك برمز خروج ").append(code).append(" بدون مخرجات — على الأرجح مشكلة توافق.\n");
                            allGood = false;
                        }
                    }
                } catch (Throwable e) {
                    r.append("✘ فشل تشغيل المحرك فعليًا.\n");
                    r.append("نوع الخطأ: ").append(e.getClass().getSimpleName()).append('\n');
                    r.append("الرسالة: ").append(e.getMessage() == null ? "(بدون رسالة)" : e.getMessage()).append('\n');
                    r.append("سبب شائع: مكتبة .so ناقصة، أو عدم توافق تعليمات المعالج (NEON/DotProd)، أو قيود أمنية من الشركة المصنّعة (SELinux) تمنع تنفيذ ملفات من مجلد بيانات التطبيق.\n");
                    allGood = false;
                }
            } else {
                r.append("تخطّي — لا يوجد محرك صالح لتجربته.\n");
            }

            r.append("\n=== الخلاصة ===\n");
            r.append(allGood ? "✅ جاهز للعمل — كل الفحوصات نجحت." : "⚠ غير جاهز بعد — راجع البنود المعلّمة بـ ✘ أعلاه.");

            final String finalReport = r.toString();
            final boolean finalOk = allGood;
            runOnUiThread(() -> {
                setLog(finalReport);
                Toast.makeText(this, finalOk ? "الجهاز جاهز للعمل ✅" : "لا يزال هناك ما يحتاج إصلاحًا — راجع السجل", Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        });
    }

    private void refreshModelsSync() {
        models.clear();
        File[] fs = modelsDir().listFiles();
        if (fs != null) {
            for (File f : fs) if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".gguf")) models.add(f);
        }
    }

    private File minModel() {
        File min = null;
        for (File f : models) if (min == null || f.length() < min.length()) min = f;
        return min;
    }

    /** تقدير تقريبي للذاكرة الحرة على مستوى النظام عبر /proc/meminfo (قد لا يكون دقيقًا 100% حسب الجهاز). */
    private long freeSystemRamBytes() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    // ===================== قراءة وتحليل وتعديل المشاريع =====================

    private void refreshProjects() {
        projectList.clear();
        File[] ds = projectsDir().listFiles(File::isDirectory);
        if (ds != null) Arrays.sort(ds, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (ds != null) projectList.addAll(Arrays.asList(ds));
        rebuildProjectSpinner();
    }

    /** يعيد بناء قائمة الـ Spinner للمشاريع بعد تطبيق نص البحث الحالي، مع حفظ التحديد إن أمكن. */
    private void rebuildProjectSpinner() {
        String q = projectSearchEdit != null ? projectSearchEdit.getText().toString().trim().toLowerCase(Locale.US) : "";
        String currentSelected = null;
        int prevIdx = projectSpinner.getSelectedItemPosition();
        if (prevIdx >= 0 && prevIdx < projectShown.size()) currentSelected = projectShown.get(prevIdx).getName();

        projectShown.clear();
        int preselectIdx = -1;
        for (File p : projectList) {
            if (q.isEmpty() || p.getName().toLowerCase(Locale.US).contains(q)) projectShown.add(p);
        }
        String[] labels = new String[projectShown.size()];
        for (int i = 0; i < projectShown.size(); i++) {
            labels[i] = projectShown.get(i).getName();
            if (preselectProject != null && preselectProject.equals(labels[i])) preselectIdx = i;
            else if (preselectIdx < 0 && currentSelected != null && currentSelected.equals(labels[i])) preselectIdx = i;
        }
        if (labels.length == 0) labels = new String[]{"لا توجد مشاريع مطابقة"};
        projectSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        if (preselectIdx >= 0) projectSpinner.setSelection(preselectIdx);
        refreshProjectFiles();
    }

    private void refreshProjectFiles() {
        projectFiles.clear();
        int pi = projectSpinner.getSelectedItemPosition();
        if (pi >= 0 && pi < projectShown.size()) {
            collectTextFiles(projectShown.get(pi), projectFiles);
            Collections.sort(projectFiles, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        }
        rebuildFileSpinner();
    }

    /** يعيد بناء قائمة الـ Spinner لملفات المشروع الحالي بعد تطبيق نص البحث. */
    private void rebuildFileSpinner() {
        int pi = projectSpinner.getSelectedItemPosition();
        File base = (pi >= 0 && pi < projectShown.size()) ? projectShown.get(pi) : null;
        String q = fileSearchEdit != null ? fileSearchEdit.getText().toString().trim().toLowerCase(Locale.US) : "";

        fileShown.clear();
        for (File f : projectFiles) {
            String rel = base != null ? f.getAbsolutePath().substring(base.getAbsolutePath().length() + 1) : f.getName();
            if (q.isEmpty() || rel.toLowerCase(Locale.US).contains(q)) fileShown.add(f);
        }
        String[] labels = new String[fileShown.size()];
        for (int i = 0; i < fileShown.size(); i++) {
            String rel = base != null ? fileShown.get(i).getAbsolutePath().substring(base.getAbsolutePath().length() + 1) : fileShown.get(i).getName();
            labels[i] = rel + " (" + human(fileShown.get(i).length()) + ")";
        }
        if (labels.length == 0) labels = new String[]{"لا توجد ملفات مطابقة"};
        fileSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    }

    /** يجمع الملفات النصية فقط (حسب الامتداد)، متجاهلًا مجلدات البناء الضخمة (build/.gradle/.git). */
    private void collectTextFiles(File dir, List<File> out) {
        if (out.size() > 400) return; // حد أقصى لتفادي بطء الواجهة في المشاريع الضخمة
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.equals("build") || n.equals(".gradle") || n.equals(".git") || n.equals(".idea") || n.equals("node_modules")) continue;
                collectTextFiles(f, out);
            } else {
                String lower = n.toLowerCase(Locale.US);
                for (String ext : TEXT_EXTENSIONS) {
                    if (lower.endsWith(ext) && f.length() < 300 * 1024) { out.add(f); break; }
                }
            }
        }
    }

    private void loadSelectedFile() {
        int fi = fileSpinner.getSelectedItemPosition();
        if (fi < 0 || fi >= fileShown.size()) {
            Toast.makeText(this, "اختر مشروعًا ثم ملفًا أولًا", Toast.LENGTH_LONG).show();
            return;
        }
        loadedFile = fileShown.get(fi);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(loadedFile), "UTF-8"))) {
                char[] buf = new char[4096]; int n;
                while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
            }
            loadedFileContent = sb.toString();
            String preview = loadedFileContent.length() > MAX_FILE_CHARS
                    ? loadedFileContent.substring(0, MAX_FILE_CHARS) + "\n\n... [تم اقتطاع الباقي — الملف أطول من الحد المرسل للنموذج] ..."
                    : loadedFileContent;
            filePreview.setText(preview);
            pendingEditedContent = null;
            applyEditBtn.setEnabled(false);
            diffBtn.setEnabled(false);
            resetSafetyWarningUi();
            loadChatFor(loadedFile);
            Toast.makeText(this, "تم تحميل " + loadedFile.getName(), Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "تعذّرت قراءة الملف: " + e, Toast.LENGTH_LONG).show();
        }
    }

    /** يبدّل نطاق العمل داخل نفس البطاقة: ملف واحد أو المشروع كامل — بدون الحاجة لبطاقة أو شاشة منفصلة. */
    private void setScope(boolean project) {
        scopeProject = project;
        fileScopeSection.setVisibility(project ? View.GONE : View.VISIBLE);
        fileActionsRow.setVisibility(project ? View.GONE : View.VISIBLE);
        assistResultScroll.setVisibility(project ? View.GONE : View.VISIBLE);
        applyEditBtn.setVisibility(project ? View.GONE : View.VISIBLE);
        diffBtn.setVisibility(project ? View.GONE : View.VISIBLE);
        projectActionsCol.setVisibility(project ? View.VISIBLE : View.GONE);
        if (projectChatLabelRef != null) projectChatLabelRef.setVisibility(project ? View.VISIBLE : View.GONE);

        if (scopeFileBtn != null && scopeProjectBtn != null) {
            scopeFileBtn.setBackground(rounded(project ? CARD_ALT : ACCENT, 10));
            scopeProjectBtn.setBackground(rounded(project ? ACCENT : CARD_ALT, 10));
        }
        requestEdit.setHint(project ? "اكتب طلبك على المشروع كله (مثال: حلل المشروع، أعطني نواقصه)..." : "اكتب طلبك على الملف المحمَّل...");

        if (project) {
            int pi = projectSpinner.getSelectedItemPosition();
            if (pi >= 0 && pi < projectShown.size()) loadProjectChatFor(projectShown.get(pi));
        }
    }

    private TextView liveBubble; // الفقاعة الحية التي تُحدَّث أثناء توليد الرد (streaming)

    // ===================== مساعد المشروع الشامل (يعمل على كل المشروع، لا ملف واحد فقط) =====================
    private LinearLayout projectChatContainer;
    private ScrollView projectChatScroll;
    private LinearLayout projectChatLabelRef; // تسمية+حاوية محادثة مساعد المشروع، تُخفى/تُظهر حسب النطاق
    private TextView projectLiveBubble;
    private final AtomicBoolean projectAgentRunning = new AtomicBoolean(false);
    private final ArrayList<String[]> projectConversation = new ArrayList<>();
    private File currentAgentProject;
    // مراجع محفوظة لعناصر واجهة بُنيت داخل buildUi() لكن تحتاجها دوال أخرى (للتمرير التلقائي لبطاقة الملف عند فتحه من المساعد الشامل)
    private ScrollView dScrollRef;
    private LinearLayout page2Ref;
    private LinearLayout assistCardRef;

    private void runAssistant(boolean editMode) {
        if (!binaryFile().exists() || !isElf(binaryFile())) {
            Toast.makeText(this, "نزّل محرك llama-cli أولًا (بطاقة المحرك أعلاه)", Toast.LENGTH_LONG).show(); return;
        }
        int mp = modelSpinner.getSelectedItemPosition();
        if (mp < 0 || mp >= models.size()) { Toast.makeText(this, "اختر نموذج GGUF أولًا", Toast.LENGTH_LONG).show(); return; }
        if (loadedFile == null || loadedFileContent == null) { Toast.makeText(this, "حمّل ملفًا أولًا", Toast.LENGTH_LONG).show(); return; }
        String request = requestEdit.getText().toString().trim();
        if (request.isEmpty()) { Toast.makeText(this, "اكتب طلبك أولًا", Toast.LENGTH_LONG).show(); return; }
        if (assistRunning.getAndSet(true)) return;

        File model = models.get(mp);
        String truncated = loadedFileContent.length() > MAX_FILE_CHARS ? loadedFileContent.substring(0, MAX_FILE_CHARS) : loadedFileContent;
        String relName = loadedFile.getName();

        // نُدرج آخر تبادلين (سؤال+جواب) من المحادثة السابقة على هذا الملف، لتبقى المتابعة حوارًا حقيقيًا لا طلبًا معزولًا.
        StringBuilder historyCtx = new StringBuilder();
        int from = Math.max(0, conversation.size() - 4);
        for (int i = from; i < conversation.size(); i++) {
            String[] m = conversation.get(i);
            if (m[0].equals("system")) continue;
            historyCtx.append(m[0].equals("user") ? "User: " : "Assistant: ").append(m[1]).append("\n");
        }

        String prompt;
        int nPredict;
        String exampleBlock = "";
        {
            int pi0 = projectSpinner.getSelectedItemPosition();
            String projName = (pi0 >= 0 && pi0 < projectShown.size()) ? projectShown.get(pi0).getName() : "";
            ExternalExample ex = pickBestExample(projName, extOf(relName), request);
            if (ex != null) exampleBlock = buildExamplePromptBlock(ex);
        }
        if (editMode) {
            prompt = "You are a careful code editor. Below is the full content of a source file named " + relName + ".\n"
                    + "Apply exactly the requested change and nothing else. Output ONLY the complete modified file content,\n"
                    + "with no explanation and no markdown code fences, starting immediately after the marker below.\n\n"
                    + exampleBlock
                    + "=== ORIGINAL FILE (" + relName + ") ===\n" + truncated + "\n=== END ORIGINAL ===\n\n"
                    + (historyCtx.length() > 0 ? "=== PREVIOUS CONVERSATION ON THIS FILE ===\n" + historyCtx + "=== END CONVERSATION ===\n\n" : "")
                    + "Requested change: " + request + "\n\n===BEGIN_MODIFIED_FILE===\n";
            nPredict = 800;
        } else {
            prompt = "You are a helpful code assistant. Answer in Arabic, briefly and clearly.\n\n"
                    + exampleBlock
                    + "=== FILE (" + relName + ") ===\n" + truncated + "\n=== END FILE ===\n\n"
                    + (historyCtx.length() > 0 ? "=== PREVIOUS CONVERSATION ON THIS FILE ===\n" + historyCtx + "=== END CONVERSATION ===\n\n" : "")
                    + "Question about this file: " + request + "\n\nAnswer in Arabic:";
            nPredict = 350;
        }

        pendingEditedContent = null;
        applyEditBtn.setEnabled(false);
        diffBtn.setEnabled(false);
        resetSafetyWarningUi();
        addBubble("user", request, true);
        appendChatAndSave(loadedFile, "user", request);
        requestEdit.setText("");
        liveBubble = addBubble("assistant", (editMode ? "جارٍ توليد التعديل المقترح" : "جارٍ التحليل") + " باستخدام " + model.getName() + " ...", true);

        startProtectedTask(editMode ? "اقتراح تعديل" : "تحليل ملف", () -> {
            Process proc = null;
            try {
                ArrayList<String> cmd = new ArrayList<>();
                cmd.add(binaryFile().getAbsolutePath());
                cmd.add("-m"); cmd.add(model.getAbsolutePath());
                cmd.add("-p"); cmd.add(prompt);
                cmd.add("-n"); cmd.add(String.valueOf(nPredict));
                cmd.add("--temp"); cmd.add("0.1");
                cmd.add("-ngl"); cmd.add("0");
                addAntiRepeatFlags(cmd);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.environment().put("LD_LIBRARY_PATH", binDir().getAbsolutePath());
                pb.redirectErrorStream(true);
                proc = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                StringBuilder out = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                    String partial = trimRepetition(out.toString());
                    runOnUiThread(() -> { if (liveBubble != null) liveBubble.setText(partial); assistResultScroll.post(() -> assistResultScroll.fullScroll(View.FOCUS_DOWN)); });
                }
                proc.waitFor();
                final String rawOutput = trimRepetition(out.toString());
                runOnUiThread(() -> finishAssistant(rawOutput, editMode, relName));
            } catch (Throwable e) {
                final String err = String.valueOf(e);
                runOnUiThread(() -> {
                    if (liveBubble != null) liveBubble.setText("✘ فشل التشغيل:\n" + err);
                    assistRunning.set(false);
                });
            } finally { if (proc != null && Compat.isAlive(proc)) proc.destroy(); }
        });
    }

    /** يعيد إخفاء تحذير الفحص الآلي وزر التطبيق القسري — يُستدعى كلما أُلغي أو استُهلك تعديل مقترح. */
    private void resetSafetyWarningUi() {
        if (safetyWarningText != null) safetyWarningText.setVisibility(View.GONE);
        if (forceApplyBtn != null) forceApplyBtn.setVisibility(View.GONE);
    }

    /**
     * فحص آلي بحت (بدون أي ذكاء اصطناعي) على التعديل المقترح لملفات .java فقط، قبل السماح بتفعيل زر
     * التطبيق مباشرة. يكشف نفس نوع الأخطاء التي عطّلت بناء هذا المشروع فعليًا سابقًا (أقواس غير متزنة،
     * تعريف مكرر لنفس المتغيّر/الثابت). قائمة فارغة = لا مشاكل مكتشَفة (لا يعني بالضرورة أن الكود سليم 100%،
     * فقط أن أشيع الأخطاء الميكانيكية غير موجودة).
     */
    private List<String> validateEditSafety(String editedContent, String fileExt) {
        List<String> issues = new ArrayList<>();
        if (editedContent == null || editedContent.trim().isEmpty()) {
            issues.add("المحتوى المقترح فارغ تمامًا.");
            return issues;
        }
        if (fileExt == null || !fileExt.equalsIgnoreCase(".java")) return issues; // الفحوصات التالية خاصة بجافا فقط
        int open = 0, close = 0;
        for (int i = 0; i < editedContent.length(); i++) {
            char c = editedContent.charAt(i);
            if (c == '{') open++; else if (c == '}') close++;
        }
        if (open != close) {
            issues.add("الأقواس المعقوفة { } غير متزنة (مفتوح: " + open + " / مغلق: " + close + ") — على الأرجح تعديل ناقص أو زائد عن الحد.");
        }
        // كشف تعريف مكرر لنفس اسم المتغيّر/الثابت على مستوى الصنف — نفس نوع الخطأ الذي منع بناء هذا المشروع سابقًا (PREFS_NAME).
        Map<String, Integer> declCount = new HashMap<>();
        java.util.regex.Pattern declPattern = java.util.regex.Pattern.compile(
                "^\\s*private\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\],.\\s]+?\\s(\\w+)\\s*[=;]");
        for (String line : editedContent.split("\n", -1)) {
            java.util.regex.Matcher m = declPattern.matcher(line);
            if (m.find()) declCount.merge(m.group(1), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : declCount.entrySet()) {
            if (e.getValue() > 1) {
                issues.add("المتغيّر/الثابت \"" + e.getKey() + "\" معرَّف أكثر من مرة (" + e.getValue() + " مرات) — هذا وحده يمنع البناء تمامًا.");
            }
        }
        return issues;
    }

    private void finishAssistant(String rawOutput, boolean editMode, String relName) {
        assistRunning.set(false);
        if (!editMode) {
            // في وضع التحليل: نعرض ما بعد آخر ظهور لعلامة "Answer in Arabic:" إن وُجدت، وإلا الخرج كاملًا.
            int idx = rawOutput.lastIndexOf("Answer in Arabic:");
            String shown = idx >= 0 ? rawOutput.substring(idx + "Answer in Arabic:".length()).trim() : rawOutput.trim();
            String finalText = shown.isEmpty() ? "(لم يُنتج النموذج ردًا — جرّب طلبًا أبسط أو نموذجًا أكبر)" : shown;
            if (liveBubble != null) liveBubble.setText(renderMarkdownLite(finalText));
            appendChatAndSave(loadedFile, "assistant", finalText);
            return;
        }
        int idx = rawOutput.lastIndexOf("===BEGIN_MODIFIED_FILE===");
        if (idx < 0) {
            String finalText = "⚠ لم يتبع النموذج التنسيق المطلوب فلم يمكن استخراج الملف المعدَّل تلقائيًا.\nالخرج الخام:\n\n" + rawOutput.trim();
            if (liveBubble != null) liveBubble.setText(finalText);
            appendChatAndSave(loadedFile, "assistant", finalText);
            return;
        }
        String candidate = rawOutput.substring(idx + "===BEGIN_MODIFIED_FILE===".length());
        int endIdx = candidate.indexOf("===END_MODIFIED_FILE===");
        if (endIdx >= 0) candidate = candidate.substring(0, endIdx);
        candidate = candidate.trim();
        if (candidate.isEmpty()) {
            String finalText = "⚠ الناتج فارغ — جرّب طلبًا أبسط أو ملفًا أصغر.\nالخرج الخام:\n\n" + rawOutput.trim();
            if (liveBubble != null) liveBubble.setText(finalText);
            appendChatAndSave(loadedFile, "assistant", finalText);
            return;
        }
        pendingEditedContent = candidate;
        List<String> safetyIssues = validateEditSafety(candidate, extOf(relName));
        if (safetyIssues.isEmpty()) {
            applyEditBtn.setEnabled(true);
            resetSafetyWarningUi();
        } else {
            applyEditBtn.setEnabled(false); // لا يُفعَّل تلقائيًا حتى تراجع التحذير أدناه
            safetyWarningText.setText("⚠ فحص آلي قبل التطبيق وجد مشاكل محتملة:\n• " + Compat.join("\n• ", safetyIssues));
            safetyWarningText.setVisibility(View.VISIBLE);
            forceApplyBtn.setVisibility(View.VISIBLE);
        }
        diffBtn.setEnabled(true);
        String finalText = "تعديل مقترح للملف \"" + relName + "\" — راجعه جيدًا، ثم استخدم زر «معاينة الفرق» قبل التطبيق.\n\n" + candidate;
        if (liveBubble != null) liveBubble.setText(renderMarkdownLite(finalText));
        appendChatAndSave(loadedFile, "assistant", finalText);
    }

    private void confirmApplyEdit() {
        if (loadedFile == null || pendingEditedContent == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("تطبيق التعديل؟")
                .setMessage("سيُستبدل محتوى الملف:\n" + loadedFile.getName()
                        + "\n\nسيُؤخذ نسخة احتياطية تلقائية بجانبه قبل الاستبدال (" + loadedFile.getName() + ".bak.<وقت>)."
                        + "\nراجعت المحتوى المقترح أعلاه وتأكدت أنه صحيح؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("نعم، طبّق التعديل", (d, w) -> applyEditNow())
                .show();
    }

    private void applyEditNow() {
        if (loadedFile == null || pendingEditedContent == null) return;
        try {
            File backup = new File(loadedFile.getParentFile(), loadedFile.getName() + ".bak." + System.currentTimeMillis());
            copyFile(loadedFile, backup);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(loadedFile), "UTF-8")) {
                w.write(pendingEditedContent);
            }
            loadedFileContent = pendingEditedContent;
            filePreview.setText(pendingEditedContent.length() > MAX_FILE_CHARS
                    ? pendingEditedContent.substring(0, MAX_FILE_CHARS) + "\n\n... [مقتطع للمعاينة فقط] ..."
                    : pendingEditedContent);
            pendingEditedContent = null;
            applyEditBtn.setEnabled(false);
            diffBtn.setEnabled(false);
            resetSafetyWarningUi();
            Toast.makeText(this, "تم تطبيق التعديل، والنسخة الأصلية محفوظة في: " + backup.getName(), Toast.LENGTH_LONG).show();
            refreshProjectFiles();
        } catch (Throwable e) {
            Toast.makeText(this, "فشل تطبيق التعديل: " + e, Toast.LENGTH_LONG).show();
        }
    }

    // ===================== فقاعات المحادثة + Markdown خفيف =====================

    /** يضيف فقاعة رسالة لأي حاوية محادثة (يمين = مستخدم، يسار = مساعد) ويعيد الـ TextView لتحديثه لاحقًا (streaming). */
    private TextView addBubbleTo(LinearLayout container, ScrollView scroll, String role, CharSequence text, boolean scrollDown) {
        boolean isUser = "user".equals(role);
        LinearLayout rowWrap = new LinearLayout(this);
        rowWrap.setOrientation(LinearLayout.HORIZONTAL);
        rowWrap.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextIsSelectable(true);
        bubble.setTextSize(11.5f);
        bubble.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (isUser) {
            bubble.setTypeface(Typeface.DEFAULT);
            bubble.setTextColor(TEXT_MAIN);
            bubble.setBackground(rounded(ACCENT, 12));
        } else if ("system".equals(role)) {
            bubble.setTypeface(Typeface.DEFAULT);
            bubble.setTextColor(TEXT_SUB);
            bubble.setBackground(rounded(CARD_ALT, 12));
        } else {
            bubble.setTypeface(Typeface.MONOSPACE);
            bubble.setTextColor(0xFFCFEFC0);
            bubble.setBackground(rounded(LOG_BG, 12));
        }
        LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(-2, -2);
        bubbleLp.topMargin = dp(4); bubbleLp.bottomMargin = dp(4);
        bubbleLp.rightMargin = isUser ? 0 : dp(40);
        bubbleLp.leftMargin = isUser ? dp(40) : 0;
        bubble.setLayoutParams(bubbleLp);

        // فراغ مرن يدفع فقاعة المستخدم لليمين وفقاعة المساعد لليسار
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        if (isUser) { rowWrap.addView(spacer); rowWrap.addView(bubble); }
        else { rowWrap.addView(bubble); rowWrap.addView(spacer); }

        container.addView(rowWrap);
        if (scrollDown) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return bubble;
    }

    /** فقاعة في محادثة الملف الواحد (القسم الأصلي). */
    private TextView addBubble(String role, CharSequence text, boolean scrollDown) {
        return addBubbleTo(chatContainer, assistResultScroll, role, text, scrollDown);
    }

    /** فقاعة في محادثة مساعد المشروع الشامل. */
    private TextView addProjectBubble(String role, CharSequence text, boolean scrollDown) {
        return addBubbleTo(projectChatContainer, projectChatScroll, role, text, scrollDown);
    }

    /** تحويل Markdown مبسّط (**غامق**، `كود`، أسطر #عناوين، قوائم -) إلى نص منسّق بدون مكتبات خارجية. */
    private CharSequence renderMarkdownLite(String md) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = md.split("\n", -1);
        boolean inCode = false;
        for (String line : lines) {
            if (line.trim().startsWith("```")) { inCode = !inCode; continue; }
            if (inCode) {
                int start = sb.length();
                sb.append(line).append("\n");
                sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), 0);
                sb.setSpan(new BackgroundColorSpan(0x330B0C12), start, sb.length(), 0);
                continue;
            }
            String content = line;
            int headerLevel = 0;
            while (content.startsWith("#")) { headerLevel++; content = content.substring(1); }
            content = content.trim();
            if (content.startsWith("- ") || content.startsWith("* ")) content = "• " + content.substring(2);
            int lineStart = sb.length();
            appendInline(sb, content);
            sb.append("\n");
            if (headerLevel > 0) sb.setSpan(new StyleSpan(Typeface.BOLD), lineStart, sb.length(), 0);
        }
        return sb;
    }

    private void appendInline(SpannableStringBuilder sb, String text) {
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end >= 0) {
                    int s = sb.length();
                    sb.append(text, i + 2, end);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), 0);
                    i = end + 2; continue;
                }
            }
            if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end >= 0) {
                    int s = sb.length();
                    sb.append(text, i + 1, end);
                    sb.setSpan(new TypefaceSpan("monospace"), s, sb.length(), 0);
                    sb.setSpan(new BackgroundColorSpan(0x330B0C12), s, sb.length(), 0);
                    i = end + 1; continue;
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
    }

    // ===================== حفظ سجل المحادثة لكل ملف على حدة =====================

    private File chatHistoryFile(File src) {
        String key = src.getAbsolutePath().replaceAll("[^A-Za-z0-9]", "_");
        File dir = new File(getFilesDir(), "local-ai/chat-history");
        dir.mkdirs();
        return new File(dir, key + ".chat");
    }

    /** يحمّل سجل المحادثة المحفوظ لهذا الملف (إن وُجد) ويعرضه كفقاعات، أو يبدأ محادثة جديدة. */
    private void loadChatFor(File src) {
        chatContainer.removeAllViews();
        conversation.clear();
        File f = chatHistoryFile(src);
        if (f.exists()) {
            try {
                String all = readAllUtf8(f);
                String[] msgs = all.split("\u0002");
                for (String m : msgs) {
                    if (m.isEmpty()) continue;
                    int i = m.indexOf('\u0001');
                    if (i < 0) continue;
                    String role = m.substring(0, i);
                    String text = m.substring(i + 1);
                    conversation.add(new String[]{role, text});
                    addBubble(role, "assistant".equals(role) ? renderMarkdownLite(text) : text, false);
                }
            } catch (Throwable ignored) {}
        }
        if (conversation.isEmpty()) {
            addBubble("system", "تم تحميل الملف. اكتب طلبك ثم اضغط تحليل أو اقتراح تعديل.", false);
        }
        assistResultScroll.post(() -> assistResultScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** يضيف رسالة لسجل المحادثة الحالي في الذاكرة، ويُلحقها بملف السجل الدائم على القرص. */
    private void appendChatAndSave(File src, String role, String text) {
        conversation.add(new String[]{role, text});
        try (FileOutputStream fos = new FileOutputStream(chatHistoryFile(src), true)) {
            fos.write((role + '\u0001' + text + '\u0002').getBytes("UTF-8"));
        } catch (Throwable ignored) {}
    }

    private String readAllUtf8(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            char[] buf = new char[4096]; int n;
            while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ===================== مساعد المشروع الشامل =====================

    /** يبني ملخصًا نصيًا مضغوطًا لبنية المشروع كله (شجرة ملفات + توقيعات دوال/أصناف تقريبية) ليصلح كسياق لنموذج صغير. */
    private String buildProjectIndex(File projectDir) {
        ArrayList<File> files = new ArrayList<>();
        collectTextFiles(projectDir, files);
        Collections.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(projectDir.getName()).append("\n");
        sb.append("Files (").append(files.size()).append("):\n");
        int budget = 3500; // حد حروف لملخص المشروع بأكمله كي يبقى ضمن سياق نموذج صغير (0.5B-1.5B)
        for (File f : files) {
            if (sb.length() > budget) { sb.append("... (تم اقتطاع باقي قائمة الملفات لضيق السياق)\n"); break; }
            String rel = f.getAbsolutePath().substring(projectDir.getAbsolutePath().length() + 1);
            sb.append("- ").append(rel).append(" (").append(human(f.length())).append(")");
            String sig = extractSignatures(f);
            if (!sig.isEmpty()) sb.append(" — ").append(sig);
            sb.append("\n");
        }
        return sb.toString();
    }

    /** استخراج تقريبي (بتعابير نمطية بسيطة) لأسماء الأصناف والدوال من ملف Java/Kotlin، لإعطاء النموذج فكرة عن محتواه دون إرسال الملف كاملًا. */
    private String extractSignatures(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        if (!(name.endsWith(".java") || name.endsWith(".kt"))) return "";
        try {
            String content = readAllUtf8(f);
            if (content.length() > 20000) content = content.substring(0, 20000);
            ArrayList<String> sigs = new ArrayList<>();
            java.util.regex.Matcher mc = java.util.regex.Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z0-9_]+)").matcher(content);
            while (mc.find() && sigs.size() < 6) sigs.add(mc.group(1) + " " + mc.group(2));
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\b(?:public|private|protected)\\s+[\\w<>\\[\\],. ]+?\\s+([A-Za-z0-9_]+)\\s*\\(").matcher(content);
            int count = 0;
            while (mm.find() && count < 8) { sigs.add(mm.group(1) + "()"); count++; }
            return Compat.join(", ", sigs);
        } catch (Throwable e) { return ""; }
    }

    private File projectChatHistoryFile(File proj) {
        String key = proj.getAbsolutePath().replaceAll("[^A-Za-z0-9]", "_");
        File dir = new File(getFilesDir(), "local-ai/project-chat-history");
        dir.mkdirs();
        return new File(dir, key + ".chat");
    }

    /** يحمّل محادثة مساعد المشروع المحفوظة لهذا المشروع (إن وُجدت)، أو يبدأ محادثة جديدة. */
    private void loadProjectChatFor(File proj) {
        if (proj.equals(currentAgentProject)) return; // محمَّلة أصلًا في الواجهة
        currentAgentProject = proj;
        projectChatContainer.removeAllViews();
        projectConversation.clear();
        File f = projectChatHistoryFile(proj);
        if (f.exists()) {
            try {
                String all = readAllUtf8(f);
                String[] msgs = all.split("\u0002");
                for (String m : msgs) {
                    if (m.isEmpty()) continue;
                    int i = m.indexOf('\u0001');
                    if (i < 0) continue;
                    String role = m.substring(0, i);
                    String text = m.substring(i + 1);
                    projectConversation.add(new String[]{role, text});
                    if ("assistant".equals(role)) {
                        addProjectBubble(role, renderMarkdownLite(stripTargetLines(text)), false);
                        String[] parsed = extractTargetFile(text);
                        if (parsed != null) addOpenFileActionButton(proj, parsed[0], parsed[1]);
                    } else {
                        addProjectBubble(role, text, false);
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (projectConversation.isEmpty()) {
            addProjectBubble("system", "اختر ما تريد فعله بهذا المشروع: تحليل شامل، اقتراح ميزات، أو أي طلب حر آخر.", false);
        }
        projectChatScroll.post(() -> projectChatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void appendProjectChatAndSave(File proj, String role, String text) {
        projectConversation.add(new String[]{role, text});
        try (FileOutputStream fos = new FileOutputStream(projectChatHistoryFile(proj), true)) {
            fos.write((role + '\u0001' + text + '\u0002').getBytes("UTF-8"));
        } catch (Throwable ignored) {}
    }

    /** يبحث عن سطري TARGET_FILE: و PLAN: في رد النموذج، ويُعيد {relativePath, plan} أو null إن لم يطلب تعديل ملف. */
    private String[] extractTargetFile(String text) {
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("(?m)^TARGET_FILE:\\s*(.+)$").matcher(text);
        java.util.regex.Matcher mp = java.util.regex.Pattern.compile("(?m)^PLAN:\\s*(.+)$").matcher(text);
        String target = null, plan = null;
        while (mt.find()) target = mt.group(1).trim();
        while (mp.find()) plan = mp.group(1).trim();
        if (target == null || target.isEmpty()) return null;
        return new String[]{target, plan != null ? plan : ""};
    }

    private String stripTargetLines(String text) {
        return text.replaceAll("(?m)^TARGET_FILE:.*$", "").replaceAll("(?m)^PLAN:.*$", "").trim();
    }

    /** يرسل طلبًا حرًّا على مستوى المشروع بأكمله (تحليل، نواقص، ميزات جديدة...) إلى النموذج المحلي. */
    private void sendProjectRequest(String userRequest) {
        if (!binaryFile().exists() || !isElf(binaryFile())) {
            Toast.makeText(this, "نزّل محرك llama-cli أولًا (بطاقة المحرك أعلاه)", Toast.LENGTH_LONG).show(); return;
        }
        int mp = modelSpinner.getSelectedItemPosition();
        if (mp < 0 || mp >= models.size()) { Toast.makeText(this, "اختر نموذج GGUF أولًا", Toast.LENGTH_LONG).show(); return; }
        int pi = projectSpinner.getSelectedItemPosition();
        if (pi < 0 || pi >= projectShown.size()) { Toast.makeText(this, "اختر مشروعًا أولًا من القائمة أعلاه", Toast.LENGTH_LONG).show(); return; }
        if (projectAgentRunning.getAndSet(true)) return;

        File proj = projectShown.get(pi);
        loadProjectChatFor(proj);
        File model = models.get(mp);
        String index = buildProjectIndex(proj);

        StringBuilder historyCtx = new StringBuilder();
        int from = Math.max(0, projectConversation.size() - 4);
        for (int i = from; i < projectConversation.size(); i++) {
            String[] m = projectConversation.get(i);
            if (m[0].equals("system")) continue;
            historyCtx.append(m[0].equals("user") ? "User: " : "Assistant: ").append(m[1]).append("\n");
        }

        ExternalExample projEx = pickBestExample(proj.getName(), "", userRequest);
        String projExampleBlock = projEx != null ? buildExamplePromptBlock(projEx) : "";

        String prompt = "You are a senior Android project architect reviewing a real project. Answer in Arabic, be concrete and practical.\n\n"
                + projExampleBlock
                + "=== PROJECT STRUCTURE SUMMARY (" + proj.getName() + ") ===\n" + index + "=== END SUMMARY ===\n\n"
                + (historyCtx.length() > 0 ? "=== PREVIOUS CONVERSATION ===\n" + historyCtx + "=== END CONVERSATION ===\n\n" : "")
                + "User request: " + userRequest + "\n\n"
                + "If, and only if, fulfilling this request requires editing one specific existing file from the list above, "
                + "add EXACTLY these two lines at the very end of your answer (the path must match the list above exactly):\n"
                + "TARGET_FILE: <relative/path>\nPLAN: <precise description in English of the exact change to make in that file>\n\n"
                + "Answer in Arabic:";

        addProjectBubble("user", userRequest, true);
        appendProjectChatAndSave(proj, "user", userRequest);
        projectLiveBubble = addProjectBubble("assistant", "جارٍ تحليل المشروع باستخدام " + model.getName() + " ...\n(قد يستغرق دقيقة أو أكثر حسب حجم المشروع)", true);

        startProtectedTask("مساعد المشروع", () -> {
            Process proc = null;
            try {
                ArrayList<String> cmd = new ArrayList<>();
                cmd.add(binaryFile().getAbsolutePath());
                cmd.add("-m"); cmd.add(model.getAbsolutePath());
                cmd.add("-p"); cmd.add(prompt);
                cmd.add("-n"); cmd.add("500");
                cmd.add("--temp"); cmd.add("0.2");
                cmd.add("-ngl"); cmd.add("0");
                addAntiRepeatFlags(cmd);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.environment().put("LD_LIBRARY_PATH", binDir().getAbsolutePath());
                pb.redirectErrorStream(true);
                proc = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                StringBuilder out = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                    String partial = trimRepetition(out.toString());
                    runOnUiThread(() -> { if (projectLiveBubble != null) projectLiveBubble.setText(partial); projectChatScroll.post(() -> projectChatScroll.fullScroll(View.FOCUS_DOWN)); });
                }
                proc.waitFor();
                final String rawOutput = trimRepetition(out.toString());
                runOnUiThread(() -> finishProjectAgent(rawOutput, proj));
            } catch (Throwable e) {
                final String err = String.valueOf(e);
                runOnUiThread(() -> {
                    if (projectLiveBubble != null) projectLiveBubble.setText("✘ فشل التشغيل:\n" + err);
                    projectAgentRunning.set(false);
                });
            } finally { if (proc != null && Compat.isAlive(proc)) proc.destroy(); }
        });
    }

    private void finishProjectAgent(String rawOutput, File proj) {
        projectAgentRunning.set(false);
        int idx = rawOutput.lastIndexOf("Answer in Arabic:");
        String shown = idx >= 0 ? rawOutput.substring(idx + "Answer in Arabic:".length()).trim() : rawOutput.trim();
        if (shown.isEmpty()) shown = "(لم يُنتج النموذج ردًا — جرّب طلبًا أبسط أو نموذجًا أكبر)";

        String[] parsed = extractTargetFile(shown);
        String displayText = stripTargetLines(shown);

        if (projectLiveBubble != null) projectLiveBubble.setText(renderMarkdownLite(displayText));
        appendProjectChatAndSave(proj, "assistant", shown);

        if (parsed != null) addOpenFileActionButton(proj, parsed[0], parsed[1]);
    }

    /** يضيف زرًا داخل محادثة المشروع لفتح الملف الذي اقترح النموذج تعديله، مع تمرير خطة التعديل إلى قسم الملف الواحد لمراجعتها وتوليد Diff قبل التطبيق. */
    private void addOpenFileActionButton(File proj, String relPath, String plan) {
        Button openBtn = secondaryBtn("📥 فتح \"" + relPath + "\" لتطبيق هذا التعديل");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2); lp.bottomMargin = dp(8);
        openBtn.setLayoutParams(lp);
        openBtn.setOnClickListener(v -> openFileFromAgent(proj, relPath, plan));
        projectChatContainer.addView(openBtn);
        projectChatScroll.post(() -> projectChatScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** يفتح الملف المقترَح من مساعد المشروع داخل قسم "قراءة وتحليل وتعديل مشروعك"، ويملأ حقل الطلب بخطة التعديل تلقائيًا — دون تطبيق أي شيء تلقائيًا. */
    private void openFileFromAgent(File proj, String relPath, String plan) {
        File target = new File(proj, relPath);
        if (!target.exists() || !target.isFile()) {
            Toast.makeText(this, "تعذّر إيجاد الملف: " + relPath, Toast.LENGTH_LONG).show();
            return;
        }
        int pi = projectShown.indexOf(proj);
        if (pi >= 0 && projectSpinner.getSelectedItemPosition() != pi) projectSpinner.setSelection(pi);
        refreshProjectFiles(); // يضمن أن قائمة ملفات هذا المشروع محدَّثة قبل استخدامها
        loadedFile = target;
        try {
            loadedFileContent = readAllUtf8(target);
            String preview = loadedFileContent.length() > MAX_FILE_CHARS
                    ? loadedFileContent.substring(0, MAX_FILE_CHARS) + "\n\n... [تم اقتطاع الباقي — الملف أطول من الحد المرسل للنموذج] ..."
                    : loadedFileContent;
            filePreview.setText(preview);
            pendingEditedContent = null;
            applyEditBtn.setEnabled(false);
            diffBtn.setEnabled(false);
            resetSafetyWarningUi();
            loadChatFor(loadedFile);
            requestEdit.setText(plan.isEmpty() ? "نفّذ التعديل المقترح من مساعد المشروع أعلاه." : plan);
            Toast.makeText(this, "تم فتح الملف — راجع الطلب واضغط «اقتراح تعديل»، ثم راجع الـ Diff قبل التطبيق", Toast.LENGTH_LONG).show();
            if (dScrollRef != null && page2Ref != null && assistCardRef != null) {
                dScrollRef.post(() -> dScrollRef.smoothScrollTo(0, page2Ref.getTop() + assistCardRef.getTop()));
            }
        } catch (Throwable e) {
            Toast.makeText(this, "تعذّرت قراءة الملف: " + e, Toast.LENGTH_LONG).show();
        }
    }

    // ===================== معاينة الفرق (Diff) قبل التطبيق =====================

    private static class DiffLine { char type; String text; DiffLine(char t, String s) { type = t; text = s; } }

    /** فرق سطر-بسطر باستخدام أطول تتابع مشترك (LCS)؛ يتحوّل لمقارنة مبسّطة تلقائيًا مع الملفات الكبيرة جدًا لتفادي استهلاك ذاكرة زائد. */
    private List<DiffLine> computeDiffSafe(String oldText, String newText) {
        String[] a = oldText.split("\n", -1);
        String[] b = newText.split("\n", -1);
        long product = (long) a.length * (long) b.length;
        if (product > 4_000_000L) {
            List<DiffLine> out = new ArrayList<>();
            int max = Math.max(a.length, b.length);
            for (int k = 0; k < max; k++) {
                String oa = k < a.length ? a[k] : null;
                String ob = k < b.length ? b[k] : null;
                if (oa != null && ob != null && oa.equals(ob)) out.add(new DiffLine(' ', oa));
                else {
                    if (oa != null) out.add(new DiffLine('-', oa));
                    if (ob != null) out.add(new DiffLine('+', ob));
                }
            }
            return out;
        }
        int n = a.length, m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        List<DiffLine> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) { out.add(new DiffLine(' ', a[i])); i++; j++; }
            else if (lcs[i + 1][j] >= lcs[i][j + 1]) { out.add(new DiffLine('-', a[i])); i++; }
            else { out.add(new DiffLine('+', b[j])); j++; }
        }
        while (i < n) { out.add(new DiffLine('-', a[i])); i++; }
        while (j < m) { out.add(new DiffLine('+', b[j])); j++; }
        return out;
    }

    private View diffLineView(DiffLine dl) {
        TextView tv = new TextView(this);
        String prefix = dl.type == '+' ? "+ " : dl.type == '-' ? "- " : "  ";
        tv.setText(prefix + dl.text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(10.5f);
        tv.setTextIsSelectable(false);
        if (dl.type == '+') { tv.setTextColor(0xFF9CF29A); tv.setBackgroundColor(0x2222D3B8); }
        else if (dl.type == '-') { tv.setTextColor(0xFFFF9FAF); tv.setBackgroundColor(0x22FF5C7A); }
        else tv.setTextColor(TEXT_SUB);
        tv.setPadding(dp(4), dp(1), dp(4), dp(1));
        return tv;
    }

    /** يعرض معاينة الفرق سطرًا بسطر بين الملف الأصلي والمحتوى المقترح، مع طيّ الأسطر الطويلة غير المتغيّرة. */
    private void showDiffDialog() {
        if (loadedFileContent == null || pendingEditedContent == null) return;
        List<DiffLine> diff = computeDiffSafe(loadedFileContent, pendingEditedContent);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        int context = 2, added = 0, removed = 0, i = 0;
        while (i < diff.size()) {
            DiffLine dl = diff.get(i);
            if (dl.type == ' ') {
                int start = i;
                while (i < diff.size() && diff.get(i).type == ' ') i++;
                boolean atEdge = (start == 0 || i == diff.size());
                int runLen = i - start;
                if (runLen > context * 2 && !atEdge) {
                    for (int k = start; k < start + context; k++) box.addView(diffLineView(diff.get(k)));
                    TextView gap = new TextView(this);
                    gap.setText("⋯ " + (runLen - context * 2) + " سطر بدون تغيير ⋯");
                    gap.setTextColor(TEXT_SUB); gap.setTextSize(10f); gap.setGravity(Gravity.CENTER);
                    gap.setPadding(0, dp(4), 0, dp(4));
                    box.addView(gap);
                    for (int k = i - context; k < i; k++) box.addView(diffLineView(diff.get(k)));
                } else {
                    for (int k = start; k < i; k++) box.addView(diffLineView(diff.get(k)));
                }
            } else {
                if (dl.type == '+') added++; else removed++;
                box.addView(diffLineView(dl));
                i++;
            }
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackground(rounded(LOG_BG, 10));
        sv.addView(box);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(420)));

        TextView summary = new TextView(this);
        summary.setText("+ " + added + " إضافة    − " + removed + " حذف");
        summary.setTextColor(TEXT_SUB);
        summary.setPadding(dp(4), 0, dp(4), dp(6));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(summary);
        outer.addView(sv);

        new android.app.AlertDialog.Builder(this)
                .setTitle("معاينة الفرق قبل التطبيق")
                .setView(outer)
                .setNegativeButton("إغلاق", null)
                .setPositiveButton("تطبيق الآن", (d, w) -> confirmApplyEdit())
                .show();
    }

    // ===================== بنك الأمثلة الخارجية (Claude/ChatGPT وغيرهما — وليس المساعد المحلي) =====================

    private static class ExternalExample {
        String id, project, ext, description, diffText;
        long ts;
    }

    private File externalBankFile() {
        File dir = new File(getFilesDir(), "local-ai");
        dir.mkdirs();
        return new File(dir, "external-examples.bank");
    }

    private void setBankEnabled(boolean v) {
        bankEnabled = v;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_BANK_ENABLED, v).apply();
        applyBankToggleUi();
        Toast.makeText(this, v ? "تم تفعيل بنك الأمثلة الخارجية" : "تم إيقاف بنك الأمثلة الخارجية", Toast.LENGTH_SHORT).show();
    }

    private void applyBankToggleUi() {
        if (bankToggleBtn == null) return;
        if (bankToggleBtn.isChecked() != bankEnabled) bankToggleBtn.setChecked(bankEnabled);
    }

    /** حقول مفصولة بـ\u0001 لكل سجل، والسجلات مفصولة بـ\u0002 — نفس أسلوب حفظ محادثات الملفات/المشاريع في هذا الملف. */
    private static final char BF = '\u0001';
    private static final char BR = '\u0002';

    private List<ExternalExample> loadBank() {
        List<ExternalExample> out = new ArrayList<>();
        File f = externalBankFile();
        if (!f.exists()) return out;
        boolean migrated = false;
        try {
            String all = readAllUtf8(f);
            String[] recs = all.split(String.valueOf(BR));
            for (String rec : recs) {
                if (rec.isEmpty()) continue;
                String[] parts = rec.split(String.valueOf(BF), -1);
                ExternalExample ex = new ExternalExample();
                if (parts.length >= 7) {
                    // تنسيق قديم: كان يخزّن الملف كاملًا قبل/بعد — نحوّله الآن إلى فرق مختصر فقط ونعلّم للحفظ.
                    ex.id = parts[0];
                    try { ex.ts = Long.parseLong(parts[1]); } catch (Throwable ignored) {}
                    ex.project = parts[2]; ex.ext = parts[3]; ex.description = parts[4];
                    List<DiffLine> full = computeDiffSafe(parts[5], parts[6]);
                    ex.diffText = serializeDiff(extractHunks(full, 3, 400));
                    migrated = true;
                } else if (parts.length >= 6) {
                    ex.id = parts[0];
                    try { ex.ts = Long.parseLong(parts[1]); } catch (Throwable ignored) {}
                    ex.project = parts[2]; ex.ext = parts[3]; ex.description = parts[4];
                    ex.diffText = parts[5];
                } else continue;
                out.add(ex);
            }
        } catch (Throwable ignored) {}
        if (migrated) saveBank(out); // نعيد الكتابة فورًا بالتنسيق الجديد المختصر فنتخلّص من النسخ الكاملة القديمة من القرص
        return out;
    }

    private void saveBank(List<ExternalExample> examples) {
        try (FileOutputStream fos = new FileOutputStream(externalBankFile(), false)) {
            for (ExternalExample ex : examples) {
                String rec = ex.id + BF + ex.ts + BF + ex.project + BF + ex.ext + BF + ex.description + BF + ex.diffText + BR;
                fos.write(rec.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {}
    }

    /** يسلسل قائمة أسطر الفرق إلى نص واحد (سطر لكل عنصر: حرف نوعه ثم محتواه)، صالح للتخزين والاسترجاع. */
    private String serializeDiff(List<DiffLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            DiffLine dl = lines.get(i);
            if (i > 0) sb.append('\n');
            sb.append(dl.type).append(dl.text);
        }
        return sb.toString();
    }

    private List<DiffLine> deserializeDiff(String s) {
        List<DiffLine> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n", -1)) {
            if (line.isEmpty()) continue;
            out.add(new DiffLine(line.charAt(0), line.substring(1)));
        }
        return out;
    }

    /**
     * يستخرج من فرق كامل فقط الأجزاء المتغيّرة (+/-) مع سطور سياق قليلة حولها (context)،
     * ويستبدل أي فراغ كبير غير متغيّر بينها بعلامة اختصار، بدل الاحتفاظ بالملف كاملًا.
     * هذا هو ما يُخزَّن فعليًا على القرص لكل مثال — وليس المحتوى الكامل قبل/بعد.
     */
    private List<DiffLine> extractHunks(List<DiffLine> full, int context, int maxLines) {
        List<Integer> changedIdx = new ArrayList<>();
        for (int i = 0; i < full.size(); i++) if (full.get(i).type != ' ') changedIdx.add(i);
        if (changedIdx.isEmpty()) return new ArrayList<>();
        boolean[] keep = new boolean[full.size()];
        for (int idx : changedIdx) {
            int from = Math.max(0, idx - context), to = Math.min(full.size() - 1, idx + context);
            for (int k = from; k <= to; k++) keep[k] = true;
        }
        List<DiffLine> out = new ArrayList<>();
        int i = 0;
        while (i < full.size()) {
            if (!keep[i]) {
                int start = i;
                while (i < full.size() && !keep[i]) i++;
                out.add(new DiffLine('.', "… (" + (i - start) + " سطرًا غير متغيّر) …"));
                continue;
            }
            out.add(full.get(i));
            i++;
            if (out.size() >= maxLines) { out.add(new DiffLine('.', "… (تم اقتطاع باقي الفرق) …")); break; }
        }
        return out;
    }

    private void appendExample(ExternalExample ex) {
        List<ExternalExample> all = loadBank();
        all.add(ex);
        // الحد الأقصى الذي يحدّده المستخدم بنفسه من الواجهة (0 = بلا حد)؛ نحذف الأقدم فقط عند تجاوزه.
        if (bankMaxExamples > 0) while (all.size() > bankMaxExamples) all.remove(0);
        saveBank(all);
    }

    private void deleteExample(String id) {
        List<ExternalExample> all = loadBank();
        all.removeIf(e -> e.id.equals(id));
        saveBank(all);
        refreshBankList();
    }

    private void updateExample(ExternalExample updated) {
        List<ExternalExample> all = loadBank();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(updated.id)) { all.set(i, updated); break; }
        }
        saveBank(all);
        refreshBankList();
    }

    /** يطبّق حدًا أقصى جديدًا يحدّده المستخدم؛ إن كان العدد الحالي أكبر منه يُقلَّم فورًا (الأقدم أولًا)، و0 تعني بلا حد. */
    private void applyBankMax(int newMax) {
        bankMaxExamples = newMax;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_BANK_MAX, newMax).apply();
        List<ExternalExample> all = loadBank();
        if (newMax > 0 && all.size() > newMax) {
            while (all.size() > newMax) all.remove(0);
            saveBank(all);
        }
        Toast.makeText(this, newMax > 0 ? "تم ضبط الحد الأقصى على " + newMax : "تم إلغاء الحد الأقصى (بلا حد)", Toast.LENGTH_SHORT).show();
        refreshBankList();
    }

    /** يحدّث عدّاد وقائمة الأمثلة المحفوظة في البطاقة. */
    private void refreshBankList() {
        if (bankCountText == null) return;
        List<ExternalExample> all = loadBank();
        String limitTxt = bankMaxExamples > 0 ? (" / الحد: " + bankMaxExamples) : " / بلا حد";
        bankCountText.setText("📦 عدد الأمثلة المحفوظة: " + all.size() + limitTxt + (bankEnabled ? "" : " — البنك معطَّل حاليًا، لن يُستخدم حتى تُفعّله"));
        if (bankListContainer != null) {
            bankListContainer.removeAllViews();
            if (all.isEmpty()) {
                bankListContainer.addView(hint("لا توجد أمثلة محفوظة بعد."));
            } else {
                for (int k = all.size() - 1; k >= 0; k--) {
                    ExternalExample ex = all.get(k);
                    LinearLayout rowL = new LinearLayout(this);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    rowL.setGravity(Gravity.CENTER_VERTICAL);
                    TextView t = new TextView(this);
                    String proj = ex.project.isEmpty() ? "(عام)" : ex.project;
                    t.setText("• [" + proj + (ex.ext.isEmpty() ? "" : " " + ex.ext) + "] " + ex.description);
                    t.setTextColor(TEXT_SUB);
                    t.setTextSize(11.5f);
                    t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                    Button edit = secondaryBtn("✏️");
                    edit.setOnClickListener(v -> showEditExampleDialog(ex));
                    Button del = secondaryBtn("🗑️");
                    del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                            .setTitle("حذف هذا المثال؟")
                            .setMessage(ex.description)
                            .setNegativeButton("إلغاء", null)
                            .setPositiveButton("حذف", (d, w) -> deleteExample(ex.id))
                            .show());
                    rowL.addView(t);
                    rowL.addView(edit);
                    rowL.addView(del);
                    bankListContainer.addView(rowL);
                }
            }
        }
    }

    /** حوار إضافة مثال خارجي: وصف قصير + محتوى الملف قبل التعديل + بعده. يُربط تلقائيًا بالمشروع/الملف المفتوحَين حاليًا إن وُجدا. */
    private void showAddExampleDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(4));

        box.addView(hint("وصف قصير لما فعله المساعد الخارجي (مثال: \"أضاف زر حذف لكل عنصر في القائمة مع تأكيد\"):"));
        EditText descEdit = styledEdit("الوصف...");
        box.addView(descEdit);

        box.addView(hint("محتوى الملف قبل التعديل (الصق كاملًا):"));
        EditText beforeEdit = styledEdit("قبل...");
        beforeEdit.setGravity(Gravity.TOP);
        beforeEdit.setTypeface(Typeface.MONOSPACE);
        beforeEdit.setTextSize(10.5f);
        beforeEdit.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(120)));
        if (loadedFileContent != null) beforeEdit.setText(loadedFileContent); // تعبئة تلقائية إن كان هناك ملف محمَّل حاليًا
        box.addView(beforeEdit);

        box.addView(hint("محتوى الملف بعد التعديل (الصق كاملًا):"));
        EditText afterEdit = styledEdit("بعد...");
        afterEdit.setGravity(Gravity.TOP);
        afterEdit.setTypeface(Typeface.MONOSPACE);
        afterEdit.setTextSize(10.5f);
        afterEdit.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(120)));
        box.addView(afterEdit);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new android.app.AlertDialog.Builder(this)
                .setTitle("➕ إضافة مثال خارجي")
                .setView(sv)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d, w) -> {
                    String desc = descEdit.getText().toString().trim();
                    String before = beforeEdit.getText().toString();
                    String after = afterEdit.getText().toString();
                    if (desc.isEmpty() || before.trim().isEmpty() || after.trim().isEmpty()) {
                        Toast.makeText(this, "يلزم ملء الوصف والمحتوى قبل/بعد", Toast.LENGTH_LONG).show();
                        return;
                    }
                    // نحسب الفرق الآن، ولا نخزّن الملفّين الكاملين أبدًا — فقط الجزء المتغيّر (+ سياق قليل حوله).
                    List<DiffLine> full = computeDiffSafe(before, after);
                    List<DiffLine> hunks = extractHunks(full, 3, 400);
                    if (hunks.isEmpty()) {
                        Toast.makeText(this, "لا يوجد فرق فعلي بين النصّين قبل/بعد", Toast.LENGTH_LONG).show();
                        return;
                    }
                    ExternalExample ex = new ExternalExample();
                    ex.id = String.valueOf(System.currentTimeMillis()) + "-" + (int) (Math.random() * 9999);
                    ex.ts = System.currentTimeMillis();
                    int pi = projectSpinner != null ? projectSpinner.getSelectedItemPosition() : -1;
                    ex.project = (pi >= 0 && pi < projectShown.size()) ? projectShown.get(pi).getName() : "";
                    String extGuess = loadedFile != null ? extOf(loadedFile.getName()) : "";
                    ex.ext = extGuess;
                    ex.description = desc;
                    ex.diffText = serializeDiff(hunks);
                    appendExample(ex);
                    refreshBankList();
                    Toast.makeText(this, "تم حفظ فرق التعديل فقط في البنك (بدون الملف كاملًا)", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** يحوّل أسطر الفرق المخزَّنة إلى نص قابل للقراءة والتعديل اليدوي (كل سطر ببادئة "+ "/"- "/"  "، وسطر الاختصار كما هو). */
    private String diffToEditableText(List<DiffLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (DiffLine dl : lines) {
            String prefix = dl.type == '+' ? "+ " : dl.type == '-' ? "- " : dl.type == '.' ? "" : "  ";
            sb.append(prefix).append(dl.text).append("\n");
        }
        return sb.toString();
    }

    /** يفسّر النص المُعدَّل يدويًا مجددًا إلى أسطر فرق: "+ " إضافة، "- " حذف، "  " سياق ثابت، وأي سطر آخر يُعامَل كعلامة اختصار كما هو. */
    private List<DiffLine> editableTextToDiff(String text) {
        List<DiffLine> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (line.startsWith("+ ")) out.add(new DiffLine('+', line.substring(2)));
            else if (line.startsWith("- ")) out.add(new DiffLine('-', line.substring(2)));
            else if (line.startsWith("  ")) out.add(new DiffLine(' ', line.substring(2)));
            else out.add(new DiffLine('.', line));
        }
        return out;
    }

    /** حوار تصفّح/تعديل مثال محفوظ: يعرض الوصف والفرق المخزَّن فعليًا (وليس أي ملف كامل) بشكل قابل للتعديل مباشرة. */
    private void showEditExampleDialog(ExternalExample ex) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(4));

        box.addView(hint("الوصف:"));
        EditText descEdit = styledEdit("الوصف...");
        descEdit.setText(ex.description);
        box.addView(descEdit);

        box.addView(hint("[" + (ex.project.isEmpty() ? "(عام)" : ex.project) + (ex.ext.isEmpty() ? "" : " " + ex.ext) + "] الفرق المحفوظ (عدّله مباشرة — الأسطر تبدأ بـ \"+ \" إضافة أو \"- \" حذف أو مسافتين سياق ثابت):"));
        EditText diffEdit = styledEdit("الفرق...");
        diffEdit.setGravity(Gravity.TOP);
        diffEdit.setTypeface(Typeface.MONOSPACE);
        diffEdit.setTextSize(10.5f);
        diffEdit.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(220)));
        diffEdit.setText(diffToEditableText(deserializeDiff(ex.diffText)));
        box.addView(diffEdit);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new android.app.AlertDialog.Builder(this)
                .setTitle("✏️ تعديل مثال محفوظ")
                .setView(sv)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d, w) -> {
                    String desc = descEdit.getText().toString().trim();
                    String diffTxt = diffEdit.getText().toString();
                    List<DiffLine> parsed = editableTextToDiff(diffTxt);
                    if (desc.isEmpty() || parsed.isEmpty()) {
                        Toast.makeText(this, "لا يمكن حفظ وصف أو فرق فارغ", Toast.LENGTH_LONG).show();
                        return;
                    }
                    ex.description = desc;
                    ex.diffText = serializeDiff(parsed);
                    updateExample(ex);
                    Toast.makeText(this, "تم حفظ التعديل", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i) : "";
    }

    /** تسجيل تقارُب بسيط بالكلمات المشتركة بين نصّين (بدون حساسية لحالة الأحرف)، يُستخدم لاختيار أقرب مثال لطلب المستخدم الحالي. */
    private int wordOverlapScore(String a, String b) {
        if (a == null || b == null) return 0;
        Set<String> sa = new HashSet<>(Arrays.asList(a.toLowerCase(Locale.US).split("[^\\p{L}\\p{N}]+")));
        Set<String> sb = new HashSet<>(Arrays.asList(b.toLowerCase(Locale.US).split("[^\\p{L}\\p{N}]+")));
        sa.remove(""); sb.remove("");
        int score = 0;
        for (String w : sa) if (w.length() >= 3 && sb.contains(w)) score++;
        return score;
    }

    /**
     * يختار أقرب مثال محفوظ لطلب المستخدم الحالي: مطابقة المشروع (+4)، مطابقة امتداد الملف (+2)،
     * زائد تداخل كلمات الوصف مع الطلب. يُعاد null إن لم يتجاوز أي مثال حدًا أدنى من الصلة، لتفادي
     * إقحام مثال غير ذي علاقة في سياق نموذج صغير محدود.
     */
    private ExternalExample pickBestExample(String project, String ext, String requestText) {
        if (!bankEnabled) return null;
        List<ExternalExample> all = loadBank();
        ExternalExample best = null; int bestScore = 0;
        for (ExternalExample ex : all) {
            int score = wordOverlapScore(requestText, ex.description);
            if (project != null && !project.isEmpty() && project.equals(ex.project)) score += 4;
            if (ext != null && !ext.isEmpty() && ext.equals(ex.ext)) score += 2;
            if (score > bestScore) { bestScore = score; best = ex; }
        }
        return bestScore >= 2 ? best : null; // حد أدنى لتفادي أمثلة ضعيفة الصلة
    }

    /** يبني كتلة الطلب من الفرق المخزَّن مسبقًا مباشرة (لا يعيد حساب أي شيء، ولا وجود لملفات كاملة أصلًا). */
    private String buildExamplePromptBlock(ExternalExample ex) {
        List<DiffLine> diff = deserializeDiff(ex.diffText);
        StringBuilder sb = new StringBuilder();
        sb.append("=== EXAMPLE OF A GOOD EDIT A HUMAN MADE ON A SIMILAR FILE (follow this style) ===\n");
        sb.append("What was requested: ").append(ex.description).append("\n");
        int shown = 0, maxLines = 40;
        for (DiffLine dl : diff) {
            if (dl.type == ' ') continue;
            if (shown >= maxLines) { sb.append("... (تم اقتطاع باقي الفرق لضيق السياق)\n"); break; }
            if (dl.type == '.') { sb.append(dl.text).append("\n"); continue; } // علامة اختصار سياق غير متغيّر
            sb.append(dl.type == '+' ? "+ " : "- ").append(dl.text).append("\n");
            shown++;
        }
        sb.append("=== END EXAMPLE ===\n\n");
        return sb.toString();
    }

    private void setLog(String s) {
        opLog.setText(s);
        opLogScroll.post(() -> opLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ===================== تشغيل المحادثة =====================

    private void runPrompt() {
        if (generating) { Toast.makeText(this, "هناك طلب قيد التنفيذ", Toast.LENGTH_SHORT).show(); return; }
        if (!binaryFile().exists()) {
            Toast.makeText(this, "استورد أو نزّل llama-cli أولًا", Toast.LENGTH_LONG).show(); return;
        }
        int p = modelSpinner.getSelectedItemPosition();
        if (p < 0 || p >= models.size()) { Toast.makeText(this, "اختر نموذج GGUF", Toast.LENGTH_LONG).show(); return; }
        String prompt = promptEdit.getText().toString().trim();
        if (prompt.isEmpty()) return;
        File model = models.get(p);
        if (!chatStarted) { chat.setText(""); chatStarted = true; } // أزل نص الترحيب الأولي مع أول رسالة فعلية
        appendChat((chat.getText().length() == 0 ? "" : "\n\n") + "أنت:\n" + prompt + "\n\nLocal AI:\n");
        promptEdit.setText("");
        generating = true;
        setRunButtonState(true);
        startProtectedTask("تشغيل Local AI", () -> {
            Process proc = null;
            // مجموعات أعلام "وضع الطلب الواحد" مرتبة من الأحدث إلى الأقدم صياغةً — بعض بنايات llama-cli
            // ترفض -no-cnv كعلم غير معروف بالكامل (error: invalid argument: -no-cnv) بينما تقبل -st وحدها،
            // وبعضها العكس. نجرّب كل مجموعة، ونستبعد فقط الطلبات التي فشلت بسبب "علم غير صالح" فورًا،
            // حتى نصل إلى مجموعة تعمل فعليًا مع هذا البناء المحدد. طبقة /dev/null تبقى كحماية أخيرة دائمًا.
            List<List<String>> flagSets = Arrays.asList(
                    Arrays.asList("-no-cnv", "-st"),
                    Arrays.asList("-st"),
                    Arrays.asList("-no-cnv"),
                    Collections.<String>emptyList()
            );
            StringBuilder attemptsLog = new StringBuilder();
            String rawResult = ""; int fc = -1; boolean gotResult = false;
            try {
                for (int i = 0; i < flagSets.size(); i++) {
                    List<String> flags = flagSets.get(i);
                    ArrayList<String> cmd = new ArrayList<>();
                    cmd.add(binaryFile().getAbsolutePath());
                    cmd.add("-m"); cmd.add(model.getAbsolutePath());
                    cmd.add("-p"); cmd.add(prompt);
                    cmd.add("-n"); cmd.add("512");
                    cmd.add("--temp"); cmd.add("0.2");
                    cmd.add("-ngl"); cmd.add("0");
                    addAntiRepeatFlags(cmd);
                    cmd.addAll(flags);
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.environment().put("LD_LIBRARY_PATH", binDir().getAbsolutePath());
                    pb.redirectErrorStream(true);
                    Compat.redirectInput(pb, new File("/dev/null"));
                    proc = pb.start();
                    running = proc;
                    BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    StringBuilder out = new StringBuilder(); String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                        String partial = cleanModelOutput(trimRepetition(out.toString()));
                        runOnUiThread(() -> updateLastReply(partial));
                    }
                    int code = proc.waitFor();
                    running = null;
                    rawResult = out.toString();
                    fc = code;
                    attemptsLog.append("محاولة ").append(i + 1).append(" (أعلام: ")
                            .append(flags.isEmpty() ? "بدون" : Compat.join(" ", flags))
                            .append(") — كود الخروج: ").append(code).append('\n');
                    boolean invalidArgFailure = code != 0 && rawResult.contains("invalid argument");
                    if (!invalidArgFailure) { gotResult = true; break; }
                    // فشل بسبب علم غير مدعوم في هذا البناء تحديدًا: جرّب المجموعة التالية بصمت.
                }
            } catch (Throwable e) {
                running = null;
                final String err = String.valueOf(e);
                generating = false;
                runOnUiThread(() -> { setRunButtonState(false); appendChat("\n[خطأ: " + err + "]\n"); });
                return;
            } finally { if (proc != null && Compat.isAlive(proc)) proc.destroy(); }
            final int finalFc = fc; final String finalRaw = rawResult;
            final String result = cleanModelOutput(trimRepetition(finalRaw));
            final String fullLog = "سجل تشغيل المحرك (خام، لأغراض التشخيص):\n" + attemptsLog + "\n" + finalRaw;
            final boolean finalGotResult = gotResult;
            generating = false;
            runOnUiThread(() -> {
                setRunButtonState(false);
                if (!finalGotResult || finalFc != 0) appendChat("\n[llama-cli exit " + finalFc + "]\n");
                else updateLastReply(result);
                setLog(fullLog);
                refreshStatus();
            });
        });
    }

    /**
     * أعلام مضادة للتكرار (repeat-penalty/repeat-last-n/top-k/top-p) — غيابها هو السبب المباشر لدخول
     * النماذج الصغيرة (0.5B–1.5B) في حلقة تكرار لجمل فارغة ("الخطوة الأولى… الخطوة الثانية…") بلا توقف.
     */
    private void addAntiRepeatFlags(List<String> cmd) {
        cmd.add("--repeat-penalty"); cmd.add("1.3");
        cmd.add("--repeat-last-n"); cmd.add("64");
        cmd.add("--top-k"); cmd.add("40");
        cmd.add("--top-p"); cmd.add("0.9");
    }

    /**
     * حماية إضافية: حتى مع أعلام مضادة التكرار، قد يقع نموذج صغير جدًا في تكرار نفس السطر حرفيًا.
     * إن تكرر نفس السطر (6 أحرف فأكثر بعد التقليم) 4 مرات متتالية، نقصّ الخرج هناك بدل عرض تكرار
     * بلا نهاية. الحد الأدنى للطول والتكرار مقصودان ليبقيا آمنين على كود حقيقي (أقواس/أسطر قصيرة متكررة
     * لا تُقصّ).
     */
    private String trimRepetition(String text) {
        if (text == null || text.length() < 80) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        String lastLine = null; int repeatCount = 0;
        for (String line : lines) {
            String norm = line.trim();
            if (!norm.isEmpty() && norm.length() >= 6 && norm.equals(lastLine)) {
                repeatCount++;
                if (repeatCount >= 4) {
                    out.append("\n… [تم اختصار تكرار متواصل من النموذج هنا] …\n");
                    break;
                }
            } else {
                repeatCount = 0;
            }
            lastLine = norm;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * ينظّف مخرجات llama-cli الخام (الشعار ASCII، مؤشر تحميل النموذج الدوّار، قائمة الأوامر التفاعلية،
     * صدى سؤال المستخدم بعد "> "، وسطر إحصاءات الأداء الأخير) ليبقى نص إجابة النموذج فقط — مناسب لعرضه
     * في فقاعة محادثة نظيفة بدل إغراق المستخدم بتفاصيل تقنية داخلية. السجل الخام الكامل يبقى متاحًا في
     * بطاقة "📋 السجل" داخل الإعدادات لمن يريد التشخيص.
     */
    private String cleanModelOutput(String raw) {
        if (raw == null) return "";
        String s = raw;
        int marker = s.lastIndexOf("\n> ");
        if (marker >= 0) {
            int nl = s.indexOf('\n', marker + 1);
            s = (nl >= 0) ? s.substring(nl + 1) : "";
        } else if (s.startsWith("> ")) {
            int nl = s.indexOf('\n');
            s = (nl >= 0) ? s.substring(nl + 1) : "";
        }
        s = s.replaceAll("(?m)^\\s*\\[\\s*Prompt:.*\\]\\s*$", "");
        s = s.replaceAll("(?m)^>\\s*$", "");
        // llama-cli يطبع هذه الأسطر بنفسه (ليست جزءًا من ردّ النموذج) قبل الخروج في وضع الطلب الواحد،
        // فيجب حذفها حتى لا تظهر ملتصقة بآخر فقاعة رد كما لو كانت من النموذج.
        s = s.replaceAll("(?mi)^\\s*Exiting(\\.\\.\\.)?\\s*$", "");
        s = s.replaceAll("(?mi)^\\s*Interrupted by user\\s*$", "");
        s = s.replaceAll("(?mi)^\\s*llama_perf_.*$", "");
        s = s.trim();
        return s.isEmpty() ? raw.trim() : s; // احتياط: لو لم يُطابَق أي نمط معروف، اعرض الخام بدل نص فارغ
    }

    private void appendChat(String s) {
        chat.append(s);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateLastReply(String s) {
        int marker = chat.getText().toString().lastIndexOf("\n\nLocal AI:\n");
        if (marker >= 0) chat.setText(chat.getText().toString().substring(0, marker + "\n\nLocal AI:\n".length()) + s);
        else appendChat(s);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void stopRunning() {
        Process p = running;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
            running = null;
            generating = false;
            setRunButtonState(false);
            Toast.makeText(this, "تم إيقاف Local AI", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBinaryInfo() {
        File f = binaryFile();
        String msg;
        if (!f.exists()) {
            msg = "لا يوجد محرك.\nنزّله من بطاقة المحرك أعلاه أو استورد ملف llama-cli المبني لأندرويد ARM64.";
        } else {
            File[] binFiles = binDir().listFiles();
            int soCount = 0;
            if (binFiles != null) for (File bf : binFiles) if (bf.getName().toLowerCase(Locale.US).endsWith(".so")) soCount++;
            msg = "المسار:\n" + f.getAbsolutePath()
                    + "\nالحجم: " + human(f.length())
                    + "\nتوقيع ELF صالح: " + (isElf(f) ? "نعم ✔" : "لا ✘")
                    + "\nالمعمارية: " + elfArchName(f)
                    + "\nعدد مكتبات .so المرافقة: " + soCount;
        }
        new android.app.AlertDialog.Builder(this).setTitle("محرك Local AI").setMessage(msg)
                .setPositiveButton("إغلاق", null).show();
    }

    private void startProtectedTask(String title, Runnable work) {
        BuildService.start(this);
        new Thread(() -> {
            try { work.run(); }
            catch (Throwable e) {
                final String m = title + " — " + e;
                runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }
            finally { BuildService.stop(this); }
        }, "LocalAI-" + title).start();
    }

    @Override protected void onDestroy() {
        stopRunning();
        super.onDestroy();
    }
}
