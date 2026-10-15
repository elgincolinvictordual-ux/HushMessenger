package app.hushmessenger.extension;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * iPhone-style peek for the chat list. A long press on a chat row calls {@link #launch} just before Messenger opens its
 * menu. When the menu's fragment starts, {@link #menuStarted} puts a card in the menu's window: a picture of the pressed
 * row, plus the chat's last message in full. Everything is found by type and shape, never by Messenger's renamed
 * members, and any failure leaves the stock menu alone.
 */
public final class ChatPeek {
    private static final String SESSION = "com.facebook.auth.usersession.FbUserSession";
    private static final String SNIPPET =
        "com.facebook.messaging.threadlist.plugins.core.threadsnippet.lastmessage.LastMessageSnippetImpl";
    private static final long FRESH_MS = 3_000L;
    private static final boolean DEBUG = true;

    private static WeakReference<View> row;
    private static CharSequence snippet;
    private static int rowTop;
    private static long stamp;

    private ChatPeek() {}

    /** Called first thing in the method that opens a chat's long press menu. The arguments are Messenger's, in order. */
    public static void launch(View view, Object a1, Object a2, Object summary, Object a4, Object holder, Object a6, Object a7) {
        try {
            row = null;
            snippet = null;
            if (view == null || summary == null) return;
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            rowTop = at[1];
            row = new WeakReference<>(view);
            stamp = System.currentTimeMillis();
            try {
                snippet = readSnippet(view.getContext(), findSession(holder), summary);
            } catch (Throwable t) {
                snippet = null;
                debug(view.getContext(), "peek: no text (" + t.getClass().getSimpleName() + ")", t);
            }
            debug(view.getContext(), "peek: launch ok, text=" + (snippet != null), null);
        } catch (Throwable t) {
            row = null;
            if (view != null) debug(view.getContext(), "peek: launch failed " + t.getClass().getSimpleName(), t);
        }
    }

    private static void debug(Context context, String message, Throwable t) {
        if (!DEBUG) return;
        try {
            Log.w("ChatPeek", message, t);
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Debug output only.
        }
    }

    /** Called at the start of the menu fragment's onStart with the fragment. */
    public static void menuStarted(Object fragment) {
        try {
            View pressed = row == null ? null : row.get();
            if (pressed == null || System.currentTimeMillis() - stamp > FRESH_MS) {
                Log.w("ChatPeek", "menu started but no fresh row");
                return;
            }
            row = null;
            Dialog dialog = findDialog(fragment);
            if (dialog == null || dialog.getWindow() == null) {
                debug(pressed.getContext(), "peek: no dialog window", null);
                return;
            }
            debug(pressed.getContext(), "peek: menu found", null);
            final Window window = dialog.getWindow();
            final Bitmap picture = capture(pressed);
            final CharSequence text = snippet;
            final int top = rowTop;
            window.getDecorView().post(new Runnable() {
                @Override public void run() {
                    try {
                        addCard(window, picture, text, top);
                    } catch (Throwable t) {
                        debug(window.getDecorView().getContext(), "peek: card failed " + t.getClass().getSimpleName(), t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w("ChatPeek", "menuStarted failed", t);
        }
    }

    private static Object findSession(Object holder) throws Exception {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !SESSION.equals(f.getType().getName())) continue;
                f.setAccessible(true);
                Object value = f.get(holder);
                if (value != null) return value;
            }
        }
        return null;
    }

    /** The row's own last-message text, built by Messenger's snippet maker so it matches what the row shows. */
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

    private static Dialog findDialog(Object fragment) throws Exception {
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

    private static Bitmap capture(View view) {
        try {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return null;
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addCard(Window window, Bitmap picture, CharSequence text, int rowTop) {
        View decor = window.getDecorView();
        Context context = decor.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        int pad = (int) (12 * density);
        int margin = (int) (12 * density);
        int safeTop = (int) (32 * density);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(night ? 0xFF242526 : 0xFFFFFFFF);
        background.setCornerRadius(18 * density);
        card.setBackground(background);
        card.setElevation(16 * density);

        if (picture != null) {
            ImageView image = new ImageView(context);
            image.setImageBitmap(picture);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (text != null && text.length() > 0) {
            TextView body = new TextView(context);
            body.setText(text);
            body.setTextSize(15);
            body.setMaxLines(10);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setTextColor(night ? 0xFFE4E6EB : 0xFF1C1E21);
            body.setPadding(0, picture != null ? pad / 2 : 0, 0, 0);
            card.addView(body);
        }
        if (card.getChildCount() == 0) return;

        int width = decor.getWidth() - 2 * margin;
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // Above the pressed row when there is room, else at the top of the screen.
        int top = Math.max(safeTop, rowTop - card.getMeasuredHeight() - margin);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = top;
        card.setAlpha(0f);
        window.addContentView(card, params);
        card.animate().alpha(1f).setDuration(140).start();
    }
    }
