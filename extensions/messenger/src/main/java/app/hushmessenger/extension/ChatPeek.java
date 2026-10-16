package app.hushmessenger.extension;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
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
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
    }
