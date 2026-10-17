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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * iPhone-style peek for the chat list. A long press on a chat calls {@link #launch} just before Messenger builds its
 * bottom sheet menu. The recent messages of that chat are read, read-only, from Messenger's own message database on the
 * phone. When the sheet is shown, {@link #sheetShown} puts them as chat bubbles in the free space above it. Everything is
 * found by type and shape, never by Messenger's renamed members, and any failure leaves the stock menu alone.
 */
public final class ChatPeek {
    private static final String SESSION = "com.facebook.auth.usersession.FbUserSession";
    private static final String THREAD_KEY = "com.facebook.messaging.model.threadkey.ThreadKey";
    private static final String SNIPPET =
        "com.facebook.messaging.threadlist.plugins.core.threadsnippet.lastmessage.LastMessageSnippetImpl";
    private static final long FRESH_MS = 3_000L;
    private static final int MAX_BUBBLES = 8;
    private static final boolean DEBUG = false;

    private static final class Bubble {
        String text;
        String sender;
        boolean mine;
        boolean unsent;
        long time;
        String messageId;
        boolean attachmentOnly;
        String imageUrl;
        boolean video;
        volatile Bitmap image;
        ImageView view;
    }

    private static final class Peek {
        String title;
        boolean group;
        int themeColor;
        final List<Bubble> bubbles = new ArrayList<>();
    }

    private static boolean pending;
    private static CharSequence snippet;
    private static Context appContext;
    private static long stamp;
    private static volatile Peek peek;
    private static volatile boolean loaded;
    private static volatile int generation;
    private static volatile String diag = "";
    private static volatile List<String> dumpKeys = new ArrayList<>();
    private static String[] attachmentInfo;
    private static boolean attachmentChecked;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ChatPeek() {}

    /** Called first thing in the method that opens a chat's long press menu. The arguments are Messenger's, in order. */
    public static void launch(View view, Object a1, Object a2, Object summary, Object a4, Object holder, Object a6, Object a7) {
        try {
            pending = false;
            snippet = null;
            peek = null;
            loaded = false;
            diag = "";
            final int mine = ++generation;
            if (summary == null) return;
            final Context context = view != null ? view.getContext() : (Context) findByType(holder, Context.class.getName());
            if (context == null) return;
            appContext = context;
            stamp = System.currentTimeMillis();
            pending = true;
            final Object thread = summary;
            new Thread(new Runnable() {
                @Override public void run() {
                    Peek result = null;
                    try {
                        result = load(context, thread);
                    } catch (Throwable t) {
                        debug("peek: messages failed " + t.getClass().getSimpleName(), t);
                    }
                    if (mine == generation) {
                        peek = result;
                        loaded = true;
                        if (result != null) fetchImages(result, mine);
                    }
                }
            }).start();
            try {
                snippet = readSnippet(context, findByType(holder, SESSION), summary);
            } catch (Throwable t) {
                snippet = null;
            }
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
            final int mine = generation;
            final CharSequence fallback = snippet;
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                int tries = 0;
                @Override public void run() {
                    try {
                        Object fragment = lazyValue(sheet);
                        Dialog dialog = fragment == null ? null : findDialog(fragment);
                        Window window = dialog == null ? null : dialog.getWindow();
                        if (window != null && window.getDecorView().isAttachedToWindow() && window.getDecorView().getHeight() > 0) {
                            final Window w = window;
                            handler.postDelayed(new Runnable() {
                                int waits = 0;
                                @Override public void run() {
                                    try {
                                        if (mine != generation) return;
                                        if (!loaded && waits++ < 10) {
                                            handler.postDelayed(this, 100);
                                            return;
                                        }
                                        addCard(w, peek, fallback);
                                    } catch (Throwable t) {
                                        debug("peek: card failed " + t.getClass().getSimpleName(), t);
                                    }
                                }
                            }, 400);
                        } else if (++tries < 30) {
                            handler.postDelayed(this, 60);
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

    // ---- Reading the recent messages. Read-only; nothing is written and nothing is marked as read. ----

    private static Peek load(Context context, Object summary) {
        List<String> ids = threadIds(summary);
        File file = messageDatabase(context);
        if (ids.isEmpty() || file == null) {
            diag = "ids=" + ids + (file == null ? " no database" : "");
            return null;
        }
        String name = file.getName();
        String own = name.substring(name.lastIndexOf('_') + 1);
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            List<String> keys = new ArrayList<>(ids);
            for (String id : ids) {
                try {
                    Cursor m = db.rawQuery("SELECT thread_key FROM threads WHERE consistent_thread_fbid=?", new String[] { id });
                    try {
                        while (m.moveToNext()) {
                            String k = m.getString(0);
                            if (k != null && !keys.contains(k)) keys.add(k);
                        }
                    } finally {
                        m.close();
                    }
                } catch (Throwable ignored) {
                    // No second id for this one.
                }
            }
            diag = "ids=" + ids + " keys=" + keys;
            dumpKeys = new ArrayList<>(keys);
            for (String id : keys) {
                Cursor c = db.rawQuery("SELECT timestamp_ms, text, sender_id, is_unsent, sticker_id, message_id FROM messages"
                    + " WHERE thread_key=? AND is_admin_message=0 ORDER BY timestamp_ms DESC LIMIT " + MAX_BUBBLES,
                    new String[] { id });
                Peek found = new Peek();
                Map<String, String> names = new HashMap<>();
                Set<String> others = new HashSet<>();
                try {
                    while (c.moveToNext()) {
                        Bubble b = new Bubble();
                        b.time = c.getLong(0);
                        String text = c.isNull(1) ? null : c.getString(1);
                        String sender = c.getString(2);
                        b.mine = own.equals(sender);
                        b.unsent = c.getLong(3) != 0;
                        String sticker = c.isNull(4) ? null : c.getString(4);
                        boolean hasSticker = sticker != null && !sticker.isEmpty() && !"0".equals(sticker);
                        b.messageId = c.isNull(5) ? null : c.getString(5);
                        if (b.unsent) text = null;
                        else if (text == null || text.isEmpty()) {
                            text = hasSticker ? "Sticker" : "Attachment";
                            b.attachmentOnly = !hasSticker;
                        }
                        b.text = text;
                        if (!b.mine && sender != null) {
                            others.add(sender);
                            if (!names.containsKey(sender)) names.put(sender, textOf(db, "SELECT name FROM contacts WHERE id=?", sender));
                            String full = names.get(sender);
                            b.sender = full == null ? null : (full.indexOf(' ') > 0 ? full.substring(0, full.indexOf(' ')) : full);
                            if (found.title == null) found.title = full;
                        }
                        found.bubbles.add(b);
                    }
                } finally {
                    c.close();
                }
                if (found.bubbles.isEmpty()) continue;
                Collections.reverse(found.bubbles);
                dumpKeys = new ArrayList<>(keys);
                dumpKeys.remove(id);
                dumpKeys.add(0, id);
                findAttachments(db, found);
                found.themeColor = themeColor(db, id);
                found.group = others.size() > 1;
                String threadName = textOf(db, "SELECT thread_name FROM threads WHERE thread_key=?", id);
                if (threadName != null && !threadName.isEmpty()) found.title = threadName;
                return found;
            }
            return null;
        } catch (Throwable t) {
            debug("peek: database failed " + t.getClass().getSimpleName(), t);
            return null;
        } finally {
            if (db != null) db.close();
        }
    }

    private static String textOf(SQLiteDatabase db, String sql, String arg) {
        try {
            Cursor c = db.rawQuery(sql, new String[] { arg });
            try {
                return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** The chat's ids: every positive number in its real thread key (the folder placeholder keys are skipped). */
    private static List<String> threadIds(Object summary) {
        List<String> ids = new ArrayList<>();
        try {
            for (Field sf : summary.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(sf.getModifiers()) || !THREAD_KEY.equals(sf.getType().getName())) continue;
                sf.setAccessible(true);
                Object key = sf.get(summary);
                if (key == null || String.valueOf(key).startsWith("FOLDER")) continue;
                for (Class<?> c = key.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType() != long.class) continue;
                        f.setAccessible(true);
                        long value = f.getLong(key);
                        if (value > 0 && !ids.contains(String.valueOf(value))) ids.add(String.valueOf(value));
                    }
                }
            }
        } catch (Throwable ignored) {
            // No ids, no peek.
        }
        return ids;
    }

    /** Messenger's main message database: the biggest file named msys_database_<account id>. */
    private static File messageDatabase(Context context) {
        File folder = context.getDatabasePath("x").getParentFile();
        File[] files = folder == null ? null : folder.listFiles();
        File best = null;
        if (files == null) return null;
        for (File f : files) {
            String name = f.getName();
            if (!name.startsWith("msys_database_") || !name.substring("msys_database_".length()).matches("\\d+")) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }


    // ---- Attachments, images and theme ----

    /** Finds the table that lists attachments by message, once, by looking at table and column names. */
    private static synchronized String[] attachmentTable(SQLiteDatabase db) {
        if (attachmentChecked) return attachmentInfo;
        attachmentChecked = true;
        try {
            String[] urls = { "preview_url", "playable_url", "thumbnail_url", "image_url", "url" };
            String[] types = { "attachment_type", "mime_type", "preview_url_mime_type", "content_type" };
            Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%attachment%'", null);
            List<String> tables = new ArrayList<>();
            try {
                while (c.moveToNext()) tables.add(c.getString(0));
            } finally {
                c.close();
            }
            for (String t : tables) {
                String low = t.toLowerCase(Locale.ROOT);
                if (low.contains("cta") || low.contains("range") || low.contains("generated") || low.contains("fts")
                    || low.contains("index") || low.contains("error") || low.contains("pending") || low.contains("extra")) continue;
                List<String> cols = columns(db, t);
                if (!cols.contains("message_id")) continue;
                String url = firstPresent(cols, urls);
                if (url == null) continue;
                String[] pick = { t, url, firstPresent(cols, types) };
                if (attachmentInfo == null || "attachments".equals(t)) attachmentInfo = pick;
            }
        } catch (Throwable ignored) {
            attachmentInfo = null;
        }
        return attachmentInfo;
    }

    private static String firstPresent(List<String> cols, String[] wanted) {
        for (String w : wanted) if (cols.contains(w)) return w;
        return null;
    }

    private static boolean trustedHost(String host) {
        host = host.toLowerCase(Locale.ROOT);
        return host.endsWith(".fbcdn.net") || host.endsWith(".fbsbx.com") || host.endsWith(".facebook.com")
            || host.endsWith(".messenger.com");
    }

    private static void findAttachments(SQLiteDatabase db, Peek found) {
        try {
            String[] info = attachmentTable(db);
            if (info == null) return;
            String sql = "SELECT " + info[1] + ", " + (info[2] == null ? "''" : info[2]) + " FROM " + info[0] + " WHERE message_id=? LIMIT 1";
            for (Bubble b : found.bubbles) {
                if (!b.attachmentOnly || b.messageId == null) continue;
                Cursor c = db.rawQuery(sql, new String[] { b.messageId });
                try {
                    if (!c.moveToFirst() || c.isNull(0)) continue;
                    String url = c.getString(0);
                    String type = c.getString(1) == null ? "" : c.getString(1).toLowerCase(Locale.ROOT);
                    if (url == null || !url.startsWith("https://")) continue;
                    if (!trustedHost(new URL(url).getHost())) continue;
                    b.imageUrl = url;
                    b.video = type.contains("video") || url.contains(".mp4");
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            debug("peek: attachments failed " + t.getClass().getSimpleName(), t);
        }
    }

    private static void fetchImages(Peek data, int mine) {
        int fetched = 0;
        for (int i = data.bubbles.size() - 1; i >= 0 && fetched < 3; i--) {
            final Bubble b = data.bubbles.get(i);
            if (b.imageUrl == null) continue;
            fetched++;
            final Bitmap bitmap = download(b.imageUrl);
            if (bitmap == null || mine != generation) continue;
            b.image = bitmap;
            MAIN.post(new Runnable() {
                @Override public void run() {
                    if (b.view != null) b.view.setImageBitmap(bitmap);
                }
            });
        }
    }

    /** Downloads one picture from Facebook's own servers, the same ones the app uses. Small, https only. */
    private static Bitmap download(String link) {
        HttpURLConnection con = null;
        try {
            URL url = new URL(link);
            if (!"https".equals(url.getProtocol()) || !trustedHost(url.getHost())) return null;
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(3000);
            con.setReadTimeout(5000);
            con.setInstanceFollowRedirects(false);
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > 4_000_000) return null;
                out.write(buffer, 0, n);
            }
            byte[] data = out.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 720) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Throwable t) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** The chat's own bubble colour, if it has a theme. 0 means none. */
    private static int themeColor(SQLiteDatabase db, String id) {
        try {
            Cursor c = db.rawQuery("SELECT outgoing_bubble_color FROM threads WHERE thread_key=?", new String[] { id });
            try {
                if (!c.moveToFirst() || c.isNull(0)) return 0;
                if (c.getType(0) == Cursor.FIELD_TYPE_INTEGER) {
                    long value = c.getLong(0);
                    if (value == 0) return 0;
                    return ((int) value) | 0xFF000000;
                }
                String text = c.getString(0).trim();
                return Color.parseColor(text.startsWith("#") ? text : "#" + text) | 0xFF000000;
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int blend(int base, int tint, float amount) {
        int r = (int) (Color.red(base) * (1 - amount) + Color.red(tint) * amount);
        int g = (int) (Color.green(base) * (1 - amount) + Color.green(tint) * amount);
        int b = (int) (Color.blue(base) * (1 - amount) + Color.blue(tint) * amount);
        return Color.rgb(r, g, b);
    }

    private static boolean light(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255 > 0.72;
    }

    // ---- Long press on the card: a read-only report of what Messenger stores about attachments and themes ----

    private static void startDump(final Context context) {
        new Thread(new Runnable() {
            @Override public void run() {
                String text;
                try {
                    text = dump(context);
                } catch (Throwable t) {
                    text = "report failed: " + t;
                }
                final String result = text;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        showReport(context, result);
                    }
                });
            }
        }).start();
    }

    private static void showReport(final Context ui, final String text) {
        try {
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
                .setTitle("Peek report")
                .setView(scroll)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ClipboardManager clipboard = (ClipboardManager) ui.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("peek report", text));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
        } catch (Throwable t) {
            debug("peek: report dialog failed " + t.getClass().getSimpleName(), t);
        }
    }

    private static String dump(Context context) throws Exception {
        StringBuilder out = new StringBuilder();
        File file = messageDatabase(context);
        List<String> keys = dumpKeys;
        if (file == null || keys.isEmpty()) return "no database or no keys " + keys;
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
            SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        try {
            String key = keys.get(0);
            out.append("thread key: ").append(key).append('\n');
            String[] info = attachmentTable(db);
            out.append("attachment table chosen: ").append(info == null ? "none" : info[0] + " url=" + info[1] + " type=" + info[2]).append("\n\n");
            out.append("thread row:\n");
            try {
                printRows(db, "SELECT thread_type, theme_fbid, outgoing_bubble_color, custom_emoji, theme_payload_cache_key, mailbox_type FROM threads WHERE thread_key=?", new String[] { key }, out, 100);
            } catch (Throwable t) {
                out.append("  failed: ").append(t.getMessage()).append('\n');
            }
            Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE '%attachment%' OR name LIKE '%theme%' OR name LIKE '%xma%' OR name LIKE '%media%' OR name LIKE '%blob%') LIMIT 40", null);
            List<String> tables = new ArrayList<>();
            try {
                while (c.moveToNext()) tables.add(c.getString(0));
            } finally {
                c.close();
            }
            int shown = 0;
            for (String t : tables) {
                String low = t.toLowerCase(Locale.ROOT);
                if (low.contains("fts") || low.contains("generated") || low.contains("range")) continue;
                if (shown++ >= 14) break;
                List<String> cols = columns(db, t);
                String list = cols.toString();
                out.append('\n').append(t).append(": ").append(list.length() > 500 ? list.substring(0, 500) + "..." : list).append('\n');
                try {
                    if (cols.contains("thread_key")) {
                        printRows(db, "SELECT * FROM " + t + " WHERE thread_key=? ORDER BY rowid DESC LIMIT 2", new String[] { key }, out, 100);
                    } else if (cols.contains("message_id")) {
                        printRows(db, "SELECT * FROM " + t + " WHERE message_id IN (SELECT message_id FROM messages WHERE thread_key=? ORDER BY timestamp_ms DESC LIMIT 8) LIMIT 2", new String[] { key }, out, 100);
                    }
                } catch (Throwable e) {
                    out.append("  sample failed: ").append(e.getMessage()).append('\n');
                }
            }
        } finally {
            db.close();
        }
        return out.toString();
    }

    private static List<String> columns(SQLiteDatabase db, String table) {
        List<String> names = new ArrayList<>();
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) names.add(c.getString(1));
        } finally {
            c.close();
        }
        return names;
    }

    private static void printRows(SQLiteDatabase db, String sql, String[] args, StringBuilder out, int maxLength) {
        Cursor c = db.rawQuery(sql, args);
        try {
            int row = 0;
            while (c.moveToNext() && row++ < 3) {
                out.append("  row ").append(row).append(":\n");
                for (int i = 0; i < c.getColumnCount(); i++) {
                    int type = c.getType(i);
                    if (type == Cursor.FIELD_TYPE_NULL) continue;
                    String value = type == Cursor.FIELD_TYPE_BLOB ? "[blob " + c.getBlob(i).length + "]" : c.getString(i);
                    if (value != null && value.length() > maxLength) value = value.substring(0, maxLength) + "...";
                    out.append("    ").append(c.getColumnName(i)).append('=').append(value).append('\n');
                }
            }
        } finally {
            c.close();
        }
    }

    // ---- Finding things by shape ----

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

    /** The thread's last-message text, built by Messenger's snippet maker. Only a fallback now. */
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

    // ---- Drawing the card ----

    private static void addCard(Window window, Peek data, CharSequence fallback) {
        boolean bubbles = data != null && !data.bubbles.isEmpty();
        if (!bubbles && (fallback == null || fallback.length() == 0)) return;
        View decor = window.getDecorView();
        Context context = decor.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        int margin = (int) (12 * density);
        int safeTop = (int) (36 * density);
        int sheetTop = sheetTop(decor);
        int allowed = sheetTop - margin - safeTop;
        int width = decor.getWidth() - 2 * margin;

        LinearLayout card = null;
        if (bubbles) {
            for (int count = data.bubbles.size(); count >= 1; count--) {
                card = bubbleCard(context, data, count, width, night, density);
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                if (card.getMeasuredHeight() <= allowed) break;
            }
        } else {
            card = snippetCard(context, fallback, night, density);
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        }
        int top = Math.max(safeTop, sheetTop - card.getMeasuredHeight() - margin);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = top;
        final Context ui = context;
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                startDump(ui);
                return true;
            }
        });
        card.setAlpha(0f);
        window.addContentView(card, params);
        card.animate().alpha(1f).setDuration(160).start();
    }

    private static LinearLayout baseCard(Context context, boolean night, float density, int theme) {
        int pad = (int) (12 * density);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        int base = night ? 0xFF242526 : 0xFFFFFFFF;
        if (theme != 0) {
            background.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            background.setColors(new int[] { blend(base, theme, night ? 0.16f : 0.10f), blend(base, theme, night ? 0.38f : 0.24f) });
        } else {
            background.setColor(base);
        }
        background.setCornerRadius(18 * density);
        card.setBackground(background);
        card.setElevation(16 * density);
        return card;
    }

    private static LinearLayout snippetCard(Context context, CharSequence text, boolean night, float density) {
        LinearLayout card = baseCard(context, night, density, 0);
        TextView label = new TextView(context);
        label.setText("Last message");
        label.setTextSize(12);
        label.setTextColor(night ? 0xFFB0B3B8 : 0xFF65676B);
        card.addView(label);
        TextView body = new TextView(context);
        body.setText(text);
        body.setTextSize(16);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setTextColor(night ? 0xFFE4E6EB : 0xFF1C1E21);
        card.addView(body);
        if (!diag.isEmpty()) {
            TextView note = new TextView(context);
            note.setText(diag);
            note.setTextSize(9);
            note.setTextColor(night ? 0xFF8A8D91 : 0xFF8A8D91);
            card.addView(note);
        }
        return card;
    }

    private static LinearLayout bubbleCard(Context context, Peek data, int count, int width, boolean night, float density) {
        LinearLayout card = baseCard(context, night, density, data.themeColor);
        int gray = night ? 0xFFB0B3B8 : 0xFF65676B;
        int maxBubble = (int) ((width - 24 * density) * 0.78f);
        List<Bubble> shown = data.bubbles.subList(data.bubbles.size() - count, data.bubbles.size());

        if (data.title != null && !data.title.isEmpty()) {
            TextView title = new TextView(context);
            title.setText(data.title);
            title.setTextSize(15);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextColor(night ? 0xFFE4E6EB : 0xFF1C1E21);
            card.addView(title);
        }

        TextView time = new TextView(context);
        time.setText(new SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(new Date(shown.get(0).time)).toUpperCase(Locale.getDefault()));
        time.setTextSize(11);
        time.setTextColor(gray);
        time.setGravity(Gravity.CENTER_HORIZONTAL);
        time.setPadding(0, (int) (4 * density), 0, (int) (2 * density));
        card.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String previous = null;
        for (int i = 0; i < shown.size(); i++) {
            Bubble b = shown.get(i);
            if (data.group && !b.mine && b.sender != null && !b.sender.equals(previous)) {
                TextView who = new TextView(context);
                who.setText(b.sender);
                who.setTextSize(11);
                who.setTextColor(gray);
                who.setPadding((int) (8 * density), (int) (4 * density), 0, 0);
                card.addView(who, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            previous = b.mine ? "" : b.sender;

            if (b.imageUrl != null) {
                ImageView picture = new ImageView(context);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                picture.setBackgroundColor(night ? 0xFF3A3B3C : 0xFFD0D3D8);
                final float radius = 16 * density;
                picture.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                    }
                });
                picture.setClipToOutline(true);
                b.view = picture;
                if (b.image != null) picture.setImageBitmap(b.image);
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Math.min(maxBubble, (int) (230 * density)), (int) (130 * density));
                pl.gravity = b.mine ? Gravity.END : Gravity.START;
                pl.topMargin = (int) (3 * density);
                card.addView(picture, pl);
                continue;
            }

            TextView bubble = new TextView(context);
            bubble.setMaxWidth(maxBubble);
            bubble.setTextSize(15);
            bubble.setMaxLines(4);
            bubble.setEllipsize(TextUtils.TruncateAt.END);
            int hp = (int) (12 * density);
            int vp = (int) (7 * density);
            bubble.setPadding(hp, vp, hp, vp);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(18 * density);
            if (b.unsent) {
                bubble.setText(b.mine ? "You unsent a message" : "Message unsent");
                bubble.setTypeface(null, Typeface.ITALIC);
                bubble.setTextColor(gray);
                shape.setColor(0x00000000);
                shape.setStroke((int) Math.max(1, density), gray);
            } else {
                bubble.setText(b.text);
                int mineColor = data.themeColor != 0 ? data.themeColor : 0xFF7B3FF2;
                bubble.setTextColor(b.mine ? (light(mineColor) ? 0xFF1C1E21 : 0xFFFFFFFF) : (night ? 0xFFE4E6EB : 0xFF1C1E21));
                shape.setColor(b.mine ? mineColor : (night ? 0xFF3A3B3C : 0xFFE4E6EB));
            }
            bubble.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = b.mine ? Gravity.END : Gravity.START;
            lp.topMargin = (int) (3 * density);
            card.addView(bubble, lp);
        }
        return card;
    }
}
