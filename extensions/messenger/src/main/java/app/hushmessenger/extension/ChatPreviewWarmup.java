package app.hushmessenger.extension;

import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Warms up Messenger's own chat preview, so the first press and hold on a chat doesn't pay for loading it.
 *
 * Messenger builds the preview from InboxThreadPreviewDialogFragment. On a patched build the code that makes the
 * first use fast may be missing, so the first long press loads and checks that class and the ones its screens use,
 * all on the UI thread. Doing that at startup, off the critical path, moves the cost away from the long press.
 *
 * Two steps, both after Messenger has had a few seconds to start:
 * 1. A low priority thread loads the fragment class without running its static code, and loads the classes its
 *    methods and fields mention. Nothing runs on the UI thread and nothing of Meta's executes.
 * 2. When the UI thread is idle, the class is initialized there. That's the thread the real preview uses, so a static
 *    initializer behaves the way it always does. Doing this on another thread could fail it for good.
 *
 * Starts only with Preview chats on long press on, in the main process, once per run. Every failure is swallowed:
 * a warm-up that doesn't work leaves Messenger exactly as it was.
 */
final class ChatPreviewWarmup {
    static final String KEY = "chat_preview";
    static final String FRAGMENT = "com.facebook.messaging.threadlist.threaditemmenu.preview.InboxThreadPreviewDialogFragment";
    private static final long DELAY_MS = 4000;
    private static final String TAG = "HushMessenger";
    private static final AtomicBoolean scheduled = new AtomicBoolean();

    private ChatPreviewWarmup() {}

    /** Called once settings are up in the main process. Does nothing unless the control is installed and on. */
    static void schedule() {
        try {
            if (!Settings.installed.contains(KEY) || Settings.preferences == null) return;
            if (!Settings.preferences.getBoolean(KEY, false) || Settings.preferences.getBoolean("paused", false)) return;
            if (!scheduled.compareAndSet(false, true)) return;
            Thread thread = new Thread(ChatPreviewWarmup::load, "HushPreviewWarmup");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        } catch (Throwable error) {
            Log.w(TAG, "Couldn't schedule the chat preview warm-up", error);
        }
    }

    private static void load() {
        try {
            Thread.sleep(DELAY_MS);
            // By now settings have started, so this also honors safe mode and a switch turned off since.
            if (!Settings.wouldUse(KEY) || Settings.appContext == null) return;
            long began = System.nanoTime();
            ClassLoader loader = Settings.appContext.getClassLoader();
            Class<?> fragment = Class.forName(FRAGMENT, false, loader);
            // Resolving each signature loads the classes it names. A class that isn't there is skipped.
            try {
                fragment.getDeclaredConstructors();
                fragment.getDeclaredFields();
                fragment.getDeclaredMethods();
            } catch (LinkageError error) {
                Log.d(TAG, "Chat preview warm-up couldn't load every class it names", error);
            }
            Log.d(TAG, "Chat preview classes loaded in " + (System.nanoTime() - began) / 1_000_000 + " ms");
            Looper.getMainLooper().getQueue().addIdleHandler(new Init(loader));
        } catch (Throwable error) {
            Log.w(TAG, "Chat preview warm-up stopped", error);
        }
    }

    /** Initializes the fragment class on the UI thread the first time it's idle, then removes itself. */
    private static final class Init implements MessageQueue.IdleHandler {
        private final ClassLoader loader;

        Init(ClassLoader loader) {
            this.loader = loader;
        }

        @Override public boolean queueIdle() {
            try {
                if (Settings.wouldUse(KEY)) {
                    long began = System.nanoTime();
                    Class.forName(FRAGMENT, true, loader);
                    Log.d(TAG, "Chat preview class ready in " + (System.nanoTime() - began) / 1_000_000 + " ms");
                }
            } catch (Throwable error) {
                Log.w(TAG, "Chat preview warm-up couldn't initialize the preview", error);
            }
            return false;
        }
    }
}
