package io.github.electroheavenvn.zaloxposed.utilities.richtext;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import java.util.Collections;
import java.util.List;


/**
 * Copy and paste that keeps formatting, without putting anything but plain text on the system
 * clipboard.
 *
 * <p>The latest copy of formatted text (a rich-text message bubble, or a selection in the composer)
 * leaves a process-wide, in-memory stash of {@code {text, runs}}. The stash is tied to one clip by
 * its {@link ClipDescription#getTimestamp()} (set by the system on every copy):
 * <ul>
 * <li>Composer copy: we set the clip ourselves, so its timestamp is read and pinned right away.</li>
 * <li>Message copy: Zalo sets the clip after our hook, so the first clip whose timestamp is not older
 *     than the stash and whose text equals the stash text is pinned (a one-shot clip-changed
 *     listener, or the first paste check if the listener never ran). A first clip with other text
 *     drops the stash.</li>
 * </ul>
 * A paste re-applies the runs only when the inserted text equals the stash text and the primary clip
 * is still the pinned one, so a later plain copy of the same text stays plain. Nothing is persisted.
 */
public final class FormatClipboard {
    private FormatClipboard() { }

    private static final String TAG = "FormatClipboard";

    /** Pinned value before the stash is tied to a clip. */
    static final long UNPINNED = -1;

    private static final class Stash {
        final String text;
        final List<FormatRun> runs;
        final String source;
        /** Wall-clock creation time, in the {@link ClipDescription#getTimestamp()} time base. */
        final long createdAt;
        /** Timestamp of the clip this stash belongs to, or {@link #UNPINNED}. Guarded by the class lock. */
        long pinned = UNPINNED;

        Stash(String text, List<FormatRun> runs, String source, long createdAt) {
            this.text = text;
            this.runs = Collections.unmodifiableList(runs);
            this.source = source;
            this.createdAt = createdAt;
        }
    }

    /** The primary clip's first-item text and its timestamp (0 when unknown). */
    private static final class ClipInfo {
        final String text;
        final long timestamp;

        ClipInfo(String text, long timestamp) {
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    private static Stash stash;
    private static ClipboardManager watchedClipboard;
    private static ClipboardManager.OnPrimaryClipChangedListener watcher;
    private static Context appContext;

    /** Supplies an application context from the composer view. */
    public static void setAppContext(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    private static Context appContext() {
        return appContext;
    }

    /**
     * Hook: the message context menu's Copy is about to run for a message with text {@code text}.
     * {@code params} is the message's rich-text params when it is a formatted type-0 text message,
     * otherwise null. Zalo copies the message's display text, which equals {@code text} unless
     * mentions were re-expanded for display; only an equal clip ever picks the styles up at paste.
     */
    public static void onMessageCopy(String text, String params) {
        try {
            if (text == null || params == null || !FormatSend.isEnabled()) {
                clear();
                LogAdapter.i(TAG, FormatLog.CLIP + "message copy: skipped ("
                    + (text == null ? "no text" : params == null ? "not formatted" : "toggle off") + ")"
                    + " textLen=" + (text == null ? "null" : String.valueOf(text.length())) + " params=" + (params != null));
                return;
            }
            List<FormatRun> runs = FormatParser.parse(params, text);
            Stash created = set(text, runs, "message", System.currentTimeMillis());
            if (created != null) watchForMessageClip(created);
            LogAdapter.i(TAG, FormatLog.CLIP + "message copy: "
                + (created == null ? "skipped (no usable styles)" : "stashed, waiting for Zalo's clip")
                + " textLen=" + text.length() + " runs=" + FormatLog.kindCounts(runs)
                + " params=true paramsLen=" + params.length() + " entries=" + FormatLog.paramsEntries(params));
        } catch (Throwable failure) {
            clear();
            LogAdapter.swallowedOnce(TAG, "reading the copied message styles failed", failure);
        }
    }

    /**
     * Composer copy/cut: call right after our own {@code setPrimaryClip(text)}; {@code copiedAt} is
     * {@link System#currentTimeMillis()} taken just before it. Stashes the runs and pins the clip just
     * set; returns false when the stash was not created.
     */
    static boolean onComposerCopy(Context context, String text, List<FormatRun> runs, long copiedAt) {
        Stash created = set(text, runs, "composer", copiedAt);
        if (created == null) return false;
        ClipInfo clip = primaryClip(context);
        if (clip != null && text.equals(clip.text) && clip.timestamp > 0) {
            synchronized (FormatClipboard.class) {
                created.pinned = clip.timestamp;
            }
            logPinned(created, clip.timestamp);
        } else {
            LogAdapter.w(TAG, FormatLog.CLIP + "composer copy: clip not readable after"
                + " setPrimaryClip (" + (clip == null ? "no clip" : clip.timestamp <= 0 ? "no timestamp" : "text differs")
                + "), pinning at the first paste check");
        }
        return true;
    }

    /** Replaces the stash; no runs clears it. Returns the new stash, or null. */
    private static Stash set(String text, List<FormatRun> runs, String source, long createdAt) {
        Stash next = text == null || text.isEmpty() || runs == null || runs.isEmpty() ? null
            : new Stash(text, runs, source, createdAt);
        synchronized (FormatClipboard.class) {
            stash = next;
        }
        unwatch();
        return next;
    }

    static void clear() {
        synchronized (FormatClipboard.class) {
            stash = null;
        }
        unwatch();
    }

    /** Cheap pre-check before a text change: could an insertion of {@code length} chars be a stashed paste? */
    static boolean mayMatch(int length) {
        synchronized (FormatClipboard.class) {
            return stash != null && stash.text.length() == length;
        }
    }

    /**
     * Why the primary clip (text equal to the stash or not, its timestamp) is not the stash's clip, or
     * null when it is. Unpinned, the first clip not older than the stash qualifies (the caller pins it);
     * pinned, only the pinned timestamp does. Pure, for unit tests.
     */
    static String identityRejection(long pinned, long createdAt, boolean sameText, long clipTimestamp) {
        if (!sameText) return "clip text differs";
        if (clipTimestamp <= 0) return "clip has no timestamp";
        if (pinned != UNPINNED) return clipTimestamp == pinned ? null : "clip replaced (timestamp differs from the pinned clip)";
        return clipTimestamp >= createdAt ? null : "clip older than the stash";
    }

    /**
     * The stashed runs when {@code inserted} is a paste of the stash: equal text, and the primary clip
     * is still the stash's clip ({@link #identityRejection}). A stash whose clip is gone is dropped.
     * Null otherwise.
     */
    static List<FormatRun> runsForPaste(Context context, String inserted) {
        Stash current;
        synchronized (FormatClipboard.class) {
            current = stash;
        }
        if (current == null) {
            pasteSkipped("no stash", inserted, null);
            return null;
        }
        if (inserted == null || !current.text.equals(inserted)) {
            pasteSkipped("text differs", inserted, current);
            return null;
        }
        if (!FormatSend.isEnabled()) {
            pasteSkipped("toggle off", inserted, current);
            return null;
        }
        ClipInfo clip = primaryClip(context);
        String clipText = clip == null ? null : clip.text;
        long clipTimestamp = clip == null ? 0 : clip.timestamp;
        long pinned;
        synchronized (FormatClipboard.class) {
            pinned = current.pinned;
        }
        String rejection = identityRejection(pinned, current.createdAt, current.text.equals(clipText), clipTimestamp);
        if (rejection != null) {
            synchronized (FormatClipboard.class) {
                if (stash == current) stash = null;
            }
            pasteSkipped("clip identity: " + rejection + " (clipLen="
                + (clipText == null ? "null" : String.valueOf(clipText.length())) + " pinned=" + (pinned != UNPINNED)
                + " source=" + current.source + "), stash dropped", inserted, current);
            return null;
        }
        if (pinned == UNPINNED) {
            synchronized (FormatClipboard.class) {
                if (current.pinned == UNPINNED) current.pinned = clipTimestamp;
            }
            logPinned(current, clipTimestamp);
        }
        return current.runs;
    }

    /** One-shot: the first clip change after a message copy pins the stash, or drops it if it is another text. */
    private static void watchForMessageClip(Stash target) {
        Context context = appContext();
        ClipboardManager clipboard = context == null ? null
            : (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipboardManager.OnPrimaryClipChangedListener listener = new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                try {
                    unwatch();
                    onFirstClipAfterMessageCopy(context, target);
                } catch (RuntimeException failure) {
                    LogAdapter.swallowedOnce(TAG, "checking the clip after a message copy failed",
                        failure);
                }
            }
        };
        synchronized (FormatClipboard.class) {
            if (stash != target) return;
            watchedClipboard = clipboard;
            watcher = listener;
        }
        clipboard.addPrimaryClipChangedListener(listener);
    }

    private static void onFirstClipAfterMessageCopy(Context context, Stash target) {
        synchronized (FormatClipboard.class) {
            if (stash != target || target.pinned != UNPINNED) return;
        }
        ClipInfo clip = primaryClip(context);
        String clipText = clip == null ? null : clip.text;
        long clipTimestamp = clip == null ? 0 : clip.timestamp;
        String rejection = identityRejection(UNPINNED, target.createdAt, target.text.equals(clipText), clipTimestamp);
        if (rejection == null) {
            synchronized (FormatClipboard.class) {
                if (stash == target && target.pinned == UNPINNED) target.pinned = clipTimestamp;
            }
            logPinned(target, clipTimestamp);
            return;
        }
        synchronized (FormatClipboard.class) {
            if (stash == target) stash = null;
        }
        LogAdapter.i(TAG, FormatLog.CLIP + "message copy: first clip after the copy is not it ("
            + rejection + ") clipLen=" + (clipText == null ? "null" : String.valueOf(clipText.length()))
            + " stashLen=" + target.text.length() + ", stash dropped");
    }

    private static void unwatch() {
        ClipboardManager clipboard;
        ClipboardManager.OnPrimaryClipChangedListener listener;
        synchronized (FormatClipboard.class) {
            clipboard = watchedClipboard;
            listener = watcher;
            watchedClipboard = null;
            watcher = null;
        }
        if (clipboard == null || listener == null) return;
        try {
            clipboard.removePrimaryClipChangedListener(listener);
        } catch (RuntimeException failure) {
            LogAdapter.swallowedOnce(TAG, "removing the clip listener failed", failure);
        }
    }

    /** Lengths and a delay only: never the text or the raw timestamp. */
    private static void logPinned(Stash target, long clipTimestamp) {
        LogAdapter.i(TAG, FormatLog.CLIP + target.source + " copy: stash pinned to its clip"
            + " stashLen=" + target.text.length() + " clipAfterMs=" + (clipTimestamp - target.createdAt));
    }

    /** Same-length insertions that are not the stash can repeat (IME commits): sampled. */
    private static final FormatLog.Sampler DIFFERS = new FormatLog.Sampler();

    /** Logs why an insertion of the stash's length did not get the stashed styles. Lengths only. */
    private static void pasteSkipped(String reason, String inserted, Stash stashed) {
        if ("text differs".equals(reason) && !DIFFERS.next()) return;
        LogAdapter.i(TAG, FormatLog.CLIP + "paste: skipped (" + reason + ")"
            + " insertedLen=" + (inserted == null ? "null" : String.valueOf(inserted.length()))
            + (stashed == null ? "" : " stashLen=" + stashed.text.length() + " stashRuns=" + stashed.runs.size())
            + ("text differs".equals(reason) ? " seen=" + DIFFERS.count() : ""));
    }

    /**
     * The primary clip's first-item text and timestamp, or null. Only {@link ClipData.Item#getText()}:
     * {@code coerceToText} can open a content URI (provider I/O) and this runs on the UI thread.
     */
    private static ClipInfo primaryClip(Context context) {
        try {
            ClipboardManager clipboard = context == null ? null
                : (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).getText();
            ClipDescription description = clip.getDescription();
            return new ClipInfo(text == null ? null : text.toString(), description == null ? 0 : description.getTimestamp());
        } catch (RuntimeException failure) {
            LogAdapter.swallowedOnce(TAG, "reading the clipboard failed", failure);
            return null;
        }
    }
}
