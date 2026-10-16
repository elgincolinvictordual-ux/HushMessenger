package app.hushmessenger.extension;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * iPhone-style peek for the chat list. A long press on a chat calls {@link #launch} just before Messenger builds its
 * bottom sheet menu. When the sheet is shown, {@link #sheetShown} puts a card with the chat's last message in the free
 * space above it. Everything is found by type and shape, never by Messenger's renamed members, and any failure leaves
 * the stock menu alone.
 */
public final class ChatPeek {
    private static final String SESSION = "com.facebook.auth.usersession.FbUserSession";
    private static final String SNIPPET =
        "com.facebook.messaging.threadlist.plugins.core.threadsnippet.lastmessage.LastMessageSnippetImpl";
    private static final long FRESH_MS = 3_000L;
    private static final boolean DEBUG = true;

    private static boolean pending;
    private static CharSequence snippet;
    private static Context appContext;
    private static long stamp;
    private static volatile String report;
    private static Context uiContext;
    private static boolean reportShown;

    private ChatPeek() {}

    /** Called first thing in the method that opens a chat's long press menu. The arguments are Messenger's, in order. */
    public static void launch(View view, Object a1, Object a2, Object summary, Object a4, Object holder, Object a6, Object a7) {
        Context context = null;
        try {
            pending = false;
            snippet = null;
            if (summary == null) return;
            context = view != null ? view.getContext() : (Context) findByType(holder, Context.class.getName());
            if (context == null) return;
            appContext = context;
            stamp = System.currentTimeMillis();
            pending = true;
            startDiscovery(context, summary);
            try {
                snippet = readSnippet(context, findByType(holder, SESSION), summary);
            } catch (Throwable t) {
                snippet = null;
                debug("peek: no text (" + t.getClass().getSimpleName() + ")", t);
            }
            debug("peek: launch ok, text=" + (snippet != null), null);
        } catch (Throwable t) {
            pending = false;
            debug("peek: launch failed " + t.getClass().getSimpleName(), t);
        }
    }

    /** Called at the start of the bottom sheet builder's show method, with the builder. */
    public static void sheetShown(final Object sheet) {
        try {
            if (!pending || System.currentTimeMillis() - stamp > FRESH_MS) return;
            pending = false;
            final CharSequence text = snippet;
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                int tries = 0;
                @Override public void run() {
                    try {
                        Object fragment = lazyValue(sheet);
                        Dialog dialog = fragment == null ? null : findDialog(fragment);
                        Window window = dialog == null ? null : dialog.getWindow();
                        if (window != null && window.getDecorView().isAttachedToWindow() && window.getDecorView().getHeight() > 0) {
                            debug("peek: sheet found", null);
                            final Window w = window;
                            handler.postDelayed(new Runnable() {
                                @Override public void run() {
                                    try {
                                        addCard(w, text);
                                    } catch (Throwable t) {
                                        debug("peek: card failed " + t.getClass().getSimpleName(), t);
                                    }
                                }
                            }, 400);
                        } else if (++tries < 30) {
                            handler.postDelayed(this, 60);
                        } else {
                            debug("peek: sheet window not found", null);
                        }
                    } catch (Throwable t) {
                        debug("peek: waiting failed " + t.getClass().getSimpleName(), t);
                    }
                }
            });
        } catch (Throwable t) {
            debug("peek: sheetShown failed " + t.getClass().getSimpleName(), t);
        }
    }

    private static void debug(String message, Throwable t) {
        if (!DEBUG) return;
        try {
            Log.w("ChatPeek", message, t);
            if (appContext != null) Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Debug output only.
        }
    }

    private static Object findByType(Object holder, String typeName) throws Exception {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !typeName.equals(f.getType().getName())) continue;
                f.setAccessible(true);
                Object value = f.get(holder);
                if (value != null) return value;
            }
        }
        return null;
    }

    /** The sheet builder keeps its fragment in a lazy holder; this finds that holder by its getValue method. */
    private static Object lazyValue(Object sheet) throws Exception {
        for (Field f : sheet.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object holder = f.get(sheet);
            if (holder == null) continue;
            try {
                Method get = holder.getClass().getMethod("getValue");
                get.setAccessible(true);
                Object value = get.invoke(holder);
                if (value != null && findDialogMethod(value.getClass()) != null) return value;
            } catch (NoSuchMethodException ignored) {
                // Not the lazy holder.
            }
        }
        return null;
    }

    /** The thread's last-message text, built by Messenger's snippet maker so it matches what the chat row shows. */
    private static CharSequence readSnippet(Context context, Object session, Object summary) throws Exception {
        if (session == null) return null;
        Class<?> type = Class.forName(SNIPPET, false, context.getClassLoader());
        Object maker = null;
        for (Constructor<?> c : type.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0].isInstance(session) && p[1].isAssignableFrom(context.getClass())) {
                maker = c.newInstance(session, context);
                break;
            }
        }
        if (maker == null) return null;
        for (Method m : type.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) || p.length != 1 || !p[0].isInstance(summary) || m.getReturnType() == void.class) continue;
            CharSequence text = firstText(m.invoke(maker, summary));
            if (text != null && text.length() > 0) return text;
        }
        return null;
    }

    private static CharSequence firstText(Object result) throws Exception {
        if (result == null) return null;
        if (result instanceof CharSequence) return (CharSequence) result;
        for (Class<?> c = result.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !CharSequence.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object value = f.get(result);
                if (value instanceof CharSequence && ((CharSequence) value).length() > 0) return (CharSequence) value;
            }
        }
        return null;
    }

    private static Method findDialogMethod(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == Dialog.class && !Modifier.isStatic(m.getModifiers())) return m;
            }
        }
        return null;
    }

    private static Dialog findDialog(Object fragment) {
        for (Class<?> c = fragment.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0 || m.getReturnType() != Dialog.class || Modifier.isStatic(m.getModifiers())) continue;
                try {
                    m.setAccessible(true);
                    Object value = m.invoke(fragment);
                    if (value instanceof Dialog) return (Dialog) value;
                } catch (Throwable ignored) {
                    // Try the next one: some of these throw while there is no dialog.
                }
            }
        }
        return null;
    }

    /** Top edge of the bottom sheet: the highest wide view that reaches the bottom of the window. */
    private static int sheetTop(View decor) {
        int height = decor.getHeight();
        int[] best = { (int) (height * 0.40f) };
        scan(decor, decor, best);
        return best[0];
    }

    private static void scan(View root, View view, int[] best) {
        int height = root.getHeight();
        int[] a = new int[2];
        int[] b = new int[2];
        root.getLocationInWindow(a);
        view.getLocationInWindow(b);
        int top = b[1] - a[1];
        if (view != root && view.getVisibility() == View.VISIBLE && view.getWidth() >= root.getWidth() * 0.9f
            && view.getHeight() > height * 0.2f && top > height * 0.1f && top + view.getHeight() >= height * 0.95f
            && top < best[0]) {
            best[0] = top;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scan(root, group.getChildAt(i), best);
        }
    }

    private static void addCard(Window window, CharSequence text) {
        uiContext = window.getDecorView().getContext();
        showReport();
        if (text == null || text.length() == 0) {
            debug("peek: no text to show", null);
            return;
        }
        View decor = window.getDecorView();
        Context context = decor.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        int pad = (int) (14 * density);
        int margin = (int) (12 * density);
        int safeTop = (int) (36 * density);

        int sheetTop = sheetTop(decor);
        int allowed = sheetTop - margin - safeTop;
        int lines = Math.max(2, Math.min(8, (int) ((allowed - 2 * pad - 20 * density) / (22 * density))));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(night ? 0xFF242526 : 0xFFFFFFFF);
        background.setCornerRadius(18 * density);
        card.setBackground(background);
        card.setElevation(16 * density);

        TextView label = new TextView(context);
        label.setText("Last message");
        label.setTextSize(12);
        label.setTextColor(night ? 0xFFB0B3B8 : 0xFF65676B);
        card.addView(label);

        TextView body = new TextView(context);
        body.setText(text);
        body.setTextSize(16);
        body.setMaxLines(lines);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setTextColor(night ? 0xFFE4E6EB : 0xFF1C1E21);
        body.setPadding(0, (int) (4 * density), 0, 0);
        card.addView(body);

        int width = decor.getWidth() - 2 * margin;
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int top = Math.max(safeTop, sheetTop - card.getMeasuredHeight() - margin);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = top;
        card.setAlpha(0f);
        window.addContentView(card, params);
        card.animate().alpha(1f).setDuration(160).start();
        debug("peek: card shown", null);
    }

    // ---- Temporary discovery: finds out where recent messages live. Read-only. ----

    private static void startDiscovery(final Context context, final Object summary) {
        report = null;
        reportShown = false;
        uiContext = null;
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    result = discover(context, summary);
                } catch (Throwable t) {
                    result = "discovery failed: " + t;
                }
                report = result;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        showReport();
                    }
                });
            }
        }).start();
    }

    private static void showReport() {
        try {
            if (report == null || uiContext == null || reportShown) return;
            reportShown = true;
            final String text = report;
            final Context ui = uiContext;
            uiContext = null;
            TextView view = new TextView(ui);
            view.setText(text);
            view.setTextIsSelectable(true);
            view.setTypeface(Typeface.MONOSPACE);
            view.setTextSize(11);
            view.setTextColor(0xFF111111);
            view.setPadding(32, 24, 32, 24);
            ScrollView scroll = new ScrollView(ui);
            scroll.setBackgroundColor(0xFFFFFFFF);
            scroll.addView(view);
            new AlertDialog.Builder(ui)
                .setTitle("Peek discovery")
                .setView(scroll)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ClipboardManager clipboard = (ClipboardManager) ui.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("peek discovery", text));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
        } catch (Throwable t) {
            debug("peek: report dialog failed " + t.getClass().getSimpleName(), t);
        }
    }

    private static String discover(Context context, Object summary) throws Exception {
        StringBuilder out = new StringBuilder();
        List<String> ids = new ArrayList<>();

        Object key = findByType(summary, "com.facebook.messaging.model.threadkey.ThreadKey");
        out.append("threadKey: ").append(key).append('\n');
        if (key != null) {
            for (Class<?> c = key.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> type = f.getType();
                    if (type != long.class && type != int.class && type != String.class) continue;
                    f.setAccessible(true);
                    Object value = f.get(key);
                    out.append("  ").append(f.getName()).append('=').append(value).append('\n');
                    if (value != null && !"0".equals(String.valueOf(value)) && !String.valueOf(value).isEmpty()) {
                        addId(ids, String.valueOf(value));
                    }
                }
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{5,}").matcher(String.valueOf(key));
            while (m.find()) addId(ids, m.group());
        }
        out.append("ids to try: ").append(ids).append("\n\n");

        String base = context.getApplicationInfo().dataDir;
        List<File> files = new ArrayList<>();
        File databases = context.getDatabasePath("x").getParentFile();
        if (databases != null) collect(databases, 0, files);
        collect(context.getFilesDir(), 0, files);
        File noBackup = context.getNoBackupFilesDir();
        if (noBackup != null) collect(noBackup, 0, files);

        out.append("database-like files (").append(files.size()).append("):\n");
        for (int i = 0; i < files.size() && i < 40; i++) {
            File f = files.get(i);
            out.append("  ").append(rel(f, base)).append("  ").append(f.length() / 1024).append(" KB\n");
        }
        out.append('\n');

        for (int i = 0; i < files.size() && i < 30; i++) {
            File f = files.get(i);
            if (f.length() == 0) continue;
            SQLiteDatabase db = null;
            try {
                db = SQLiteDatabase.openDatabase(f.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                List<String> tables = new ArrayList<>();
                Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
                try {
                    while (c.moveToNext()) tables.add(c.getString(0));
                } finally {
                    c.close();
                }
                boolean has = tables.contains("mdcore_messages");
                out.append(rel(f, base)).append(": ").append(tables.size()).append(" tables").append(has ? "  HAS mdcore_messages" : "").append('\n');
                if (has) {
                    mdcoreDetails(db, ids, out);
                } else {
                    int shown = 0;
                    for (String t : tables) {
                        String low = t.toLowerCase();
                        if ((low.contains("message") || low.contains("thread")) && shown++ < 6) out.append("    table ").append(t).append('\n');
                    }
                }
            } catch (Throwable t) {
                out.append(rel(f, base)).append(": cannot open (").append(t.getClass().getSimpleName()).append(")\n");
            } finally {
                if (db != null) db.close();
            }
        }
        return out.toString();
    }

    private static void mdcoreDetails(SQLiteDatabase db, List<String> ids, StringBuilder out) {
        try {
            out.append("    messages total: ").append(scalar(db, "SELECT COUNT(*) FROM mdcore_messages", null)).append('\n');
            out.append("    threads total: ").append(scalar(db, "SELECT COUNT(*) FROM mdcore_threads", null)).append('\n');
            Cursor c = db.rawQuery("SELECT thread_id, COUNT(*) n FROM mdcore_messages GROUP BY thread_id ORDER BY n DESC LIMIT 3", null);
            try {
                while (c.moveToNext()) out.append("    sample thread_id ").append(c.getString(0)).append(" has ").append(c.getLong(1)).append('\n');
            } finally {
                c.close();
            }
            for (String id : ids) {
                long n = scalar(db, "SELECT COUNT(*) FROM mdcore_messages WHERE thread_id=?", new String[] { id });
                out.append("    try ").append(id).append(": ").append(n).append(" messages\n");
                if (n > 0) {
                    Cursor r = db.rawQuery("SELECT timestamp, length(data), hex(substr(data,1,24)) FROM mdcore_messages WHERE thread_id=? ORDER BY timestamp DESC LIMIT 3", new String[] { id });
                    try {
                        while (r.moveToNext()) {
                            out.append("      ").append(r.getLong(0)).append(" len=").append(r.getLong(1)).append(' ').append(r.getString(2)).append('\n');
                        }
                    } finally {
                        r.close();
                    }
                }
            }
        } catch (Throwable t) {
            out.append("    details failed (").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(")\n");
        }
    }

    private static long scalar(SQLiteDatabase db, String sql, String[] args) {
        Cursor c = db.rawQuery(sql, args);
        try {
            return c.moveToFirst() ? c.getLong(0) : -1;
        } finally {
            c.close();
        }
    }

    private static void addId(List<String> ids, String id) {
        if (!ids.contains(id) && ids.size() < 8) ids.add(id);
    }

    private static void collect(File dir, int depth, List<File> out) {
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                if (depth < 2) collect(f, depth + 1, out);
                continue;
            }
            String name = f.getName().toLowerCase();
            if (name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith("-journal")) continue;
            if (name.contains(".db") || name.contains("sqlite") || name.contains("mdcore") || name.contains("msys")) out.add(f);
        }
    }

    private static String rel(File f, String base) {
        String path = f.getAbsolutePath();
        return path.startsWith(base) ? path.substring(base.length()) : path;
    }
}
