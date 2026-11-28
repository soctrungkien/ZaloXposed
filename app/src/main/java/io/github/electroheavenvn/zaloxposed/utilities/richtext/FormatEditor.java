package io.github.electroheavenvn.zaloxposed.utilities.richtext;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ScaleXSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatRanges.Run;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatRun.Kind;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatSpans.IndentMarker;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatSpans.ListMarker;
import io.github.electroheavenvn.zaloxposed.utilities.richtext.FormatSpans.ToolbarSpan;

/**
 * Editing semantics of the formatting toolbar on Zalo's composer EditText.
 *
 * <ul>
 * <li>Inline styles are toolbar spans ({@link FormatSpans}), {@code SPAN_EXCLUSIVE_EXCLUSIVE}. A
 *     {@link TextWatcher} gives inserted text the caret's style (the character before it on the
 *     same line, web-editor style) or the pending typing style set by a toolbar tap with no
 *     selection. IME recomposition (Telex/VNI diacritics, backspace in a composing word) keeps the
 *     styles of the unchanged prefix and suffix and styles only the differing middle
 *     ({@link FormatRecompose}); the pending style survives until it is typed or the caret moves.</li>
 * <li>Line styles are one {@link ListMarker} and/or {@link IndentMarker} per paragraph, covering
 *     the paragraph and its line break ({@code SPAN_INCLUSIVE_EXCLUSIVE}), rebuilt after every edit.
 *     A paragraph takes the marker covering its first character. An empty last paragraph has no
 *     span (Android would apply a zero-length paragraph span to the previous line too), so its line
 *     style is kept here until text is typed into it.</li>
 * <li>A line break typed in a list or indented paragraph continues it; a line break typed on an
 *     empty list item removes the item's bullet instead. This only happens when a newline really
 *     reaches the text, so Zalo's "Enter to send" keeps working unchanged.</li>
 * <li>Formatting spans that arrive inside pasted or inserted text (framework span classes) are
 *     removed, so only toolbar spans are visible and sent.</li>
 * <li>Copy and cut of a selection that has toolbar styles put plain text on the clipboard and stash
 *     the selection's styles ({@link FormatClipboard}). Pasting that same text back (or the text
 *     of a copied formatted message) re-creates the styles as toolbar spans; any other paste stays
 *     plain.</li>
 * </ul>
 */
public final class FormatEditor {
    /** Called (posted, coalesced) when the caret, selection, text or styles change. */
    public interface Listener {
        void onStateChanged();
    }

    /** What the toolbar shows for the current caret or selection. */
    public static final class State {
        public final FormatStyle style;
        public final boolean bulletList;
        public final boolean numberedList;
        public final boolean canIndent;
        public final boolean canOutdent;

        State(FormatStyle style, boolean bulletList, boolean numberedList, boolean canIndent, boolean canOutdent) {
            this.style = style;
            this.bulletList = bulletList;
            this.numberedList = numberedList;
            this.canIndent = canIndent;
            this.canOutdent = canOutdent;
        }
    }

    private static final String TAG = "FormatEditor";
    static final Kind[] INLINE_KINDS = { Kind.BOLD, Kind.ITALIC, Kind.UNDERLINE, Kind.STRIKE, Kind.SIZE, Kind.COLOR };
    private static final int INLINE_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
    private static final int MARKER_FLAGS = Spanned.SPAN_INCLUSIVE_EXCLUSIVE;
    /** Indent markers sort before list markers, so the bullet is drawn after the indent. */
    private static final int INDENT_PRIORITY = 10 << Spanned.SPAN_PRIORITY_SHIFT;

    /** Framework span classes that only arrive with pasted or foreign styled text. Subclasses (Zalo's mention span, toolbar spans) are kept. */
    private static final Set<Class<?>> FOREIGN_FORMATTING = new HashSet<>(Arrays.asList(
        StyleSpan.class, UnderlineSpan.class, StrikethroughSpan.class, AbsoluteSizeSpan.class,
        RelativeSizeSpan.class, ForegroundColorSpan.class, BackgroundColorSpan.class, TypefaceSpan.class,
        LeadingMarginSpan.Standard.class, BulletSpan.class, QuoteSpan.class, SubscriptSpan.class,
        SuperscriptSpan.class, ScaleXSpan.class));

    private static final FormatLog.Sampler STRIPS = new FormatLog.Sampler();

    private final EditText edit;
    private final Listener listener;
    private final SelectionWatcher selectionWatcher = new SelectionWatcher();

    private FormatStyle pending;
    private int pendingAnchor = -1;
    /** Line style of the empty last paragraph: its start, or -1, and its list type and indent level. */
    private int memoryStart = -1;
    private int memoryList;
    private int memoryIndent;
    private boolean internal;
    private boolean notifyPosted;

    // The change in flight, captured in beforeTextChanged.
    private CharSequence changeText;
    private boolean changeStyled;
    private String changeOld;
    private List<List<Run>> changeOldRuns;
    private FormatStyle changeTyping;
    private FormatStyle changeReplaced;
    /** Caret (selection end) before the change, and whether {@link #changeTyping} is the pending style. */
    private int changeCaret = -1;
    private boolean changeFromPending;
    private int[] enterValues;
    private boolean enterParagraphEmpty;
    private int enterAt = -1;
    // A possible paste of the stashed copy: captured in beforeTextChanged, located in onTextChanged.
    private boolean pasteCandidate;
    private String pasteReplaced;
    private int pasteAt = -1;
    private String pasteText;

    public FormatEditor(EditText edit, Listener listener) {
        this.edit = edit;
        this.listener = listener;
        edit.addTextChangedListener(new Watcher());
        attachSelectionWatcher(edit.getText());
        installCopyCallback();
    }

    // ---- Toolbar actions ----

    /** B/I/U/S: toggles on the selection, or toggles the pending typing style at the caret. */
    public void toggleInline(Kind kind) {
        Editable text = edit.getText();
        int from = selectionFrom();
        int to = selectionTo();
        if (from < to) {
            List<Run> runs = readRuns(text, kind);
            boolean removing = FormatRanges.covers(runs, from, to);
            boolean changed = writeIfChanged(text, kind, runs, FormatRanges.toggle(runs, from, to));
            logAction(kind.name(), removing ? "off" : "on", from, to, changed);
        } else {
            setPending(typingStyleAt(text, from).toggled(kind), from);
            logAction(kind.name(), "toggle", from, to, true);
        }
        notifyChanged();
    }

    /** Size token, or {@link FormatStyle#NORMAL_SIZE} to remove the size. */
    public void setSize(int token) {
        Editable text = edit.getText();
        int from = selectionFrom();
        int to = selectionTo();
        if (from < to) {
            List<Run> runs = readRuns(text, Kind.SIZE);
            boolean clear = token == FormatStyle.NORMAL_SIZE;
            boolean changed = writeIfChanged(text, Kind.SIZE, runs, FormatRanges.assign(runs, from, to, clear, token));
            logAction("SIZE", clear ? "normal" : "f_" + token, from, to, changed);
        } else {
            setPending(typingStyleAt(text, from).withSize(token), from);
            logAction("SIZE", token == FormatStyle.NORMAL_SIZE ? "normal" : "f_" + token, from, to, true);
        }
        notifyChanged();
    }

    /** 0xRRGGBB, or {@link FormatStyle#NO_COLOR} to remove the color. */
    public void setColor(int rgb) {
        Editable text = edit.getText();
        int from = selectionFrom();
        int to = selectionTo();
        if (from < to) {
            List<Run> runs = readRuns(text, Kind.COLOR);
            boolean clear = rgb == FormatStyle.NO_COLOR;
            boolean changed = writeIfChanged(text, Kind.COLOR, runs, FormatRanges.assign(runs, from, to, clear, rgb & 0xFFFFFF));
            logAction("COLOR", clear ? "none" : "c_" + FormatConfig.hex(rgb), from, to, changed);
        } else {
            setPending(typingStyleAt(text, from).withColor(rgb == FormatStyle.NO_COLOR ? rgb : rgb & 0xFFFFFF), from);
            logAction("COLOR", rgb == FormatStyle.NO_COLOR ? "none" : "c_" + FormatConfig.hex(rgb), from, to, true);
        }
        notifyChanged();
    }

    /** Selection: removes every toolbar style in it and the list/indent of its lines. Caret: plain typing style. */
    public void clearFormatting() {
        Editable text = edit.getText();
        int from = selectionFrom();
        int to = selectionTo();
        if (from < to) {
            boolean changed = false;
            for (Kind kind : INLINE_KINDS) {
                List<Run> runs = readRuns(text, kind);
                changed |= writeIfChanged(text, kind, runs, FormatRanges.assign(runs, from, to, true, 0));
            }
            updateLines(text, (lists, indents, index) -> {
                lists[index] = 0;
                indents[index] = 0;
            });
            logAction("CLEAR", "all", from, to, changed);
        } else {
            setPending(FormatStyle.PLAIN, from);
            logAction("CLEAR", "all", from, to, true);
        }
        notifyChanged();
    }

    /** Bullet or numbered list on the touched lines: removed when they all have it, else applied. */
    public void toggleList(int type) {
        Editable text = edit.getText();
        List<int[]> paragraphs = FormatRanges.paragraphs(text);
        int[][] values = lineValues(text, paragraphs);
        int[] touched = touched(text, paragraphs);
        int next = FormatRanges.toggledList(values[0], touched[0], touched[1], type);
        updateLines(text, (lists, indents, index) -> lists[index] = next);
        LogAdapter.i(TAG, FormatLog.UI + "action LIST value=lst_" + type
            + " lines=" + touched[0] + ".." + touched[1] + "/" + paragraphs.size() + " -> " + (next == 0 ? "removed" : "lst_" + next));
        notifyChanged();
    }

    /** Indent (+1) or outdent (-1) the touched lines, within 0 and {@link FormatRanges#MAX_INDENT}. */
    public void changeIndent(int delta) {
        updateLines(edit.getText(), (lists, indents, index) ->
            indents[index] = FormatRanges.steppedIndent(indents[index], delta));
        LogAdapter.i(TAG, FormatLog.UI + "action INDENT delta=" + (delta > 0 ? "+" : "") + delta
            + " selection=" + selectionFrom() + ".." + selectionTo());
        notifyChanged();
    }

    /** The toolbar state for the current caret or selection. */
    public State state() {
        Editable text = edit.getText();
        int from = selectionFrom();
        int to = selectionTo();
        FormatStyle style = from < to ? selectionStyle(text, from, to) : typingStyleAt(text, from);
        List<int[]> paragraphs = FormatRanges.paragraphs(text);
        int[][] values = lineValues(text, paragraphs);
        int[] touched = touched(text, paragraphs);
        boolean bullet = true;
        boolean numbered = true;
        boolean canIndent = false;
        boolean canOutdent = false;
        for (int index = touched[0]; index <= touched[1]; index++) {
            bullet &= values[0][index] == FormatRun.LIST_BULLET;
            numbered &= values[0][index] == FormatRun.LIST_NUMBERED;
            canIndent |= values[1][index] < FormatRanges.MAX_INDENT;
            canOutdent |= values[1][index] > 0;
        }
        return new State(style, bullet, numbered, canIndent, canOutdent);
    }

    // ---- Selection and pending style ----

    private int selectionFrom() {
        int start = edit.getSelectionStart();
        int end = edit.getSelectionEnd();
        int length = edit.getText().length();
        if (start < 0 || end < 0) return length;
        return Math.min(Math.min(start, end), length);
    }

    private int selectionTo() {
        int start = edit.getSelectionStart();
        int end = edit.getSelectionEnd();
        int length = edit.getText().length();
        if (start < 0 || end < 0) return length;
        return Math.min(Math.max(start, end), length);
    }

    private void setPending(FormatStyle style, int anchor) {
        if (!style.equals(pending) || anchor != pendingAnchor) {
            LogAdapter.i(TAG, FormatLog.EDIT + "pending style set: "
                + FormatLog.styleTokens(style) + " at=" + anchor);
        }
        pending = style;
        pendingAnchor = anchor;
    }

    /** Drops the pending typing style; logs only when there was one (a state change). */
    private void clearPending(String reason) {
        if (pending == null) return;
        LogAdapter.i(TAG, FormatLog.EDIT + "pending style cleared (" + reason + "): was "
            + FormatLog.styleTokens(pending));
        pending = null;
    }

    /** One toolbar action: kind, value, and whether it hit a selection (its range) or the pending typing style. */
    private void logAction(String kind, String value, int from, int to, boolean changed) {
        LogAdapter.i(TAG, FormatLog.UI + "action " + kind + " value=" + value
            + (from < to ? " target=selection[" + from + "," + to + ") len=" + (to - from) + " changed=" + changed
                : " target=pending at=" + from + " -> " + FormatLog.styleTokens(pending)));
    }

    private void onSelectionChanged() {
        if (internal) return;
        if (pending != null && (selectionFrom() != selectionTo() || selectionFrom() != pendingAnchor)) clearPending("caret moved");
        notifyChanged();
    }

    private void notifyChanged() {
        if (notifyPosted) return;
        notifyPosted = true;
        edit.post(() -> {
            notifyPosted = false;
            try {
                listener.onStateChanged();
            } catch (RuntimeException failure) {
                LogAdapter.swallowedOnce(TAG, "toolbar refresh failed", failure);
            }
        });
    }

    private void attachSelectionWatcher(Editable text) {
        if (text != null && text.getSpanStart(selectionWatcher) < 0) {
            text.setSpan(selectionWatcher, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
    }

    /** Spans carry selection changes; a separate object so the Editable does not also call it as a TextWatcher. */
    private final class SelectionWatcher implements SpanWatcher, NoCopySpan {
        @Override
        public void onSpanAdded(Spannable text, Object what, int start, int end) {
            if (what == Selection.SELECTION_START || what == Selection.SELECTION_END) onSelectionChanged();
        }

        @Override
        public void onSpanRemoved(Spannable text, Object what, int start, int end) {
        }

        @Override
        public void onSpanChanged(Spannable text, Object what, int oldStart, int oldEnd, int newStart, int newEnd) {
            if (what == Selection.SELECTION_START || what == Selection.SELECTION_END) onSelectionChanged();
        }
    }

    // ---- Inline styles ----

    /** Normalized runs of {@code kind} from the toolbar spans in {@code text}. */
    static List<Run> readRuns(Spanned text, Kind kind) {
        Object[] spans = text.getSpans(0, text.length(), FormatSpans.spanClass(kind));
        List<Run> runs = new ArrayList<>(spans.length);
        for (Object span : spans) {
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            if (start < 0 || start >= end) continue;
            int value = kind == Kind.SIZE || kind == Kind.COLOR ? ((ToolbarSpan) span).value() : 1;
            runs.add(new Run(start, end, value));
        }
        return FormatRanges.normalize(runs);
    }

    /** Replaces the {@code kind} spans with {@code next}; false when nothing differed. */
    private static boolean writeIfChanged(Editable text, Kind kind, List<Run> old, List<Run> next) {
        if (FormatRanges.sameRuns(old, next)) return false;
        for (Object span : text.getSpans(0, text.length(), FormatSpans.spanClass(kind))) text.removeSpan(span);
        int length = text.length();
        for (Run run : next) {
            int start = Math.max(0, run.start);
            int end = Math.min(length, run.end);
            if (start < end) text.setSpan(FormatSpans.newInlineSpan(kind, run.value), start, end, INLINE_FLAGS);
        }
        return true;
    }

    private static FormatStyle styleOfChar(Spanned text, int index) {
        return new FormatStyle(
            FormatRanges.valueAt(readRuns(text, Kind.BOLD), index) != FormatRanges.NONE,
            FormatRanges.valueAt(readRuns(text, Kind.ITALIC), index) != FormatRanges.NONE,
            FormatRanges.valueAt(readRuns(text, Kind.UNDERLINE), index) != FormatRanges.NONE,
            FormatRanges.valueAt(readRuns(text, Kind.STRIKE), index) != FormatRanges.NONE,
            orDefault(FormatRanges.valueAt(readRuns(text, Kind.SIZE), index), FormatStyle.NORMAL_SIZE),
            orDefault(FormatRanges.valueAt(readRuns(text, Kind.COLOR), index), FormatStyle.NO_COLOR));
    }

    private static FormatStyle selectionStyle(Spanned text, int from, int to) {
        return new FormatStyle(
            FormatRanges.covers(readRuns(text, Kind.BOLD), from, to),
            FormatRanges.covers(readRuns(text, Kind.ITALIC), from, to),
            FormatRanges.covers(readRuns(text, Kind.UNDERLINE), from, to),
            FormatRanges.covers(readRuns(text, Kind.STRIKE), from, to),
            orDefault(FormatRanges.uniformValue(readRuns(text, Kind.SIZE), from, to), FormatStyle.NORMAL_SIZE),
            orDefault(FormatRanges.uniformValue(readRuns(text, Kind.COLOR), from, to), FormatStyle.NO_COLOR));
    }

    private static int orDefault(int value, int fallback) {
        return value == FormatRanges.NONE ? fallback : value;
    }

    /** The style the caret at {@code position} continues. */
    private static FormatStyle caretStyle(Spanned text, int position) {
        int source = FormatRanges.caretSource(text, position);
        return source < 0 ? FormatStyle.PLAIN : styleOfChar(text, source);
    }

    private FormatStyle typingStyleAt(Spanned text, int position) {
        return pending != null && pendingAnchor == position ? pending : caretStyle(text, position);
    }

    private static boolean hasInlineSpans(Spanned text) {
        for (ToolbarSpan span : text.getSpans(0, text.length(), ToolbarSpan.class)) {
            if (!span.kind().isLineLevel()) return true;
        }
        return false;
    }

    private static boolean hasMarkers(Spanned text) {
        return text.getSpans(0, text.length(), ListMarker.class).length > 0
            || text.getSpans(0, text.length(), IndentMarker.class).length > 0;
    }

    // ---- Text changes ----

    private final class Watcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            changeText = null;
            enterValues = null;
            enterAt = -1;
            pasteCandidate = false;
            pasteReplaced = null;
            pasteAt = -1;
            pasteText = null;
            if (internal || !(text instanceof Spanned)) return;
            try {
                Spanned spanned = (Spanned) text;
                changeText = text;
                if (after > 0 && FormatClipboard.mayMatch(after)) {
                    pasteCandidate = true;
                    pasteReplaced = text.subSequence(start, start + count).toString();
                }
                changeStyled = after > 0 && (pending != null || hasInlineSpans(spanned));
                if (changeStyled) {
                    int end = start + count;
                    changeOld = text.subSequence(start, end).toString();
                    changeOldRuns = new ArrayList<>(INLINE_KINDS.length);
                    for (Kind kind : INLINE_KINDS) {
                        changeOldRuns.add(count == 0 ? Collections.emptyList()
                            : FormatRanges.assign(FormatRanges.assign(readRuns(spanned, kind), 0, start, true, 0),
                                end, Integer.MAX_VALUE, true, 0));
                    }
                    changeFromPending = pending != null && pendingAnchor == end;
                    changeTyping = changeFromPending ? pending : caretStyle(spanned, end);
                    changeCaret = Selection.getSelectionEnd(spanned);
                    changeReplaced = count > 0 ? styleOfChar(spanned, start) : changeTyping;
                }
                if (after == 1 && count == 0 && (memoryStart >= 0 || hasMarkers(spanned))) {
                    List<int[]> paragraphs = FormatRanges.paragraphs(text);
                    int index = FormatRanges.paragraphIndex(paragraphs, start);
                    int[][] values = lineValues(spanned, paragraphs);
                    int[] paragraph = paragraphs.get(index);
                    int contentEnd = paragraph[1] > paragraph[0] && text.charAt(paragraph[1] - 1) == '\n'
                        ? paragraph[1] - 1 : paragraph[1];
                    enterValues = new int[] { values[0][index], values[1][index] };
                    enterParagraphEmpty = contentEnd == paragraph[0];
                }
            } catch (RuntimeException failure) {
                changeText = null;
                LogAdapter.swallowedOnce(TAG, "capturing styles before an edit failed", failure);
            }
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
            if (internal || changeText == null) return;
            try {
                if (text != changeText) {
                    // setText(): a new Editable (send clears the composer, draft restore, ...).
                    LogAdapter.i(TAG, FormatLog.EDIT + "composer reset by setText: newLen="
                        + text.length() + " pendingCleared=" + (pending != null) + " lineMemoryCleared=" + (memoryStart >= 0));
                    changeText = null;
                    pending = null;
                    memoryStart = -1;
                    attachSelectionWatcher(edit.getText());
                    return;
                }
                shiftMemory(start, before, count);
                if (enterValues != null && count == 1 && before == 0 && text.charAt(start) == '\n') enterAt = start;
                if (!(text instanceof Editable)) return;
                Editable editable = (Editable) text;
                if (count > 0) {
                    String stripped = stripForeignFormatting(editable, start, start + count);
                    // Sampled: some keyboards attach framework spans to every commit.
                    if (stripped != null && STRIPS.next()) {
                        LogAdapter.i(TAG, FormatLog.EDIT + "stripped foreign spans from inserted text: "
                            + stripped + " insertedLen=" + count + " at=" + start + " seen=" + STRIPS.count());
                    }
                }
                boolean consumed = changeStyled && count > 0 && applyInsertedStyle(editable, start, before, count);
                if (pasteCandidate && count > 0) notePaste(editable, start, before, count);
                // IME recomposition that did not type at the pending anchor keeps the pending style.
                if (consumed) clearPending("consumed by inserted text");
            } catch (RuntimeException failure) {
                LogAdapter.swallowedOnce(TAG, "styling inserted text failed", failure);
            }
        }

        @Override
        public void afterTextChanged(Editable text) {
            if (internal) return;
            boolean tracked = changeText != null;
            changeText = null;
            try {
                Map<Integer, int[]> pasted = tracked && pasteAt >= 0 ? applyPaste(text) : null;
                if (tracked && enterAt >= 0 && pasted == null) handleEnter(text);
                normalizeLines(text, pasted);
                // Zalo clears the composer after a send with setText("") (handled in onTextChanged);
                // emptying it by hand also drops the pending typing style, like web editors.
                if (text.length() == 0) clearPending("composer emptied");
                attachSelectionWatcher(text);
            } catch (RuntimeException failure) {
                LogAdapter.swallowedOnce(TAG, "updating list lines failed", failure);
            } finally {
                enterAt = -1;
                enterValues = null;
                pasteAt = -1;
                pasteText = null;
                pasteCandidate = false;
                pasteReplaced = null;
            }
            notifyChanged();
        }
    }

    // ---- Copy and paste ----

    /**
     * Remembers {@code [start, start + count)} as a possible paste of the stash. IME input is not a
     * paste: text inside the composing region, and recomposition that only extends the replaced text.
     */
    private void notePaste(Editable text, int start, int before, int count) {
        if (!FormatClipboard.mayMatch(count)) return;
        int composingStart = BaseInputConnection.getComposingSpanStart(text);
        int composingEnd = BaseInputConnection.getComposingSpanEnd(text);
        if (composingStart >= 0 && composingStart < start + count && composingEnd > start) {
            if (IME_SKIPS.next()) logPasteSkip("composing", count);
            return;
        }
        String inserted = text.subSequence(start, start + count).toString();
        if (before > 0 && pasteReplaced != null && inserted.startsWith(pasteReplaced)) {
            if (IME_SKIPS.next()) logPasteSkip("IME recomposition", count);
            return;
        }
        pasteAt = start;
        pasteText = inserted;
    }

    /** IME insertions of the stash's length can repeat while typing: sampled. */
    private static final FormatLog.Sampler IME_SKIPS = new FormatLog.Sampler();

    private static void logPasteSkip(String reason, int count) {
        LogAdapter.i(TAG, FormatLog.CLIP + "paste: skipped (" + reason + ") insertedLen=" + count
            + " seen=" + IME_SKIPS.count());
    }

    /**
     * Re-applies the stashed styles to a paste of the stashed text: inline runs replace the inserted
     * range's inline styles; returns the line styles for {@link #normalizeLines}, or null when the
     * insertion is not a stashed paste.
     */
    private Map<Integer, int[]> applyPaste(Editable text) {
        int at = pasteAt;
        String pasted = pasteText;
        if (at < 0 || pasted == null || at + pasted.length() > text.length()
                || !pasted.contentEquals(text.subSequence(at, at + pasted.length()))) {
            LogAdapter.w(TAG, FormatLog.CLIP + "paste: skipped (inserted text changed before"
                + " afterTextChanged) at=" + at + " pastedLen=" + (pasted == null ? "null" : String.valueOf(pasted.length()))
                + " composerLen=" + text.length());
            return null;
        }
        List<FormatRun> runs = FormatClipboard.runsForPaste(edit.getContext(), pasted);
        if (runs == null) return null;
        int end = at + pasted.length();
        for (Kind kind : INLINE_KINDS) {
            List<Run> old = readRuns(text, kind);
            List<Run> next = FormatRanges.assign(old, at, end, true, 0);
            for (FormatRun run : runs) {
                if (run.kind != kind) continue;
                int value = FormatRuns.isOnOff(kind) ? 1 : run.value;
                next = FormatRanges.assign(next, at + run.start, Math.min(end, at + run.end), false, value);
            }
            writeIfChanged(text, kind, old, next);
        }
        clearPending("paste");
        Map<Integer, int[]> lines = FormatRuns.pasteLineOverrides(text, at, pasted, runs);
        LogAdapter.i(TAG, FormatLog.CLIP + "paste: applied runs=" + FormatLog.kindCounts(runs)
            + " len=" + pasted.length() + " at=" + at + " lineOverrides=" + (lines == null ? 0 : lines.size()));
        return lines;
    }

    /** Wraps any existing selection action-mode callback, which Android consults before its own Copy/Cut. */
    private void installCopyCallback() {
        ActionMode.Callback existing = edit.getCustomSelectionActionModeCallback();
        if (existing instanceof CopyCallback) return;
        edit.setCustomSelectionActionModeCallback(new CopyCallback(existing));
    }

    private final class CopyCallback implements ActionMode.Callback {
        private final ActionMode.Callback delegate;

        CopyCallback(ActionMode.Callback delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return delegate == null || delegate.onCreateActionMode(mode, menu);
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return delegate != null && delegate.onPrepareActionMode(mode, menu);
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (delegate != null && delegate.onActionItemClicked(mode, item)) return true;
            int id = item.getItemId();
            if (id != android.R.id.copy && id != android.R.id.cut) return false;
            try {
                return copySelection(mode, id == android.R.id.cut);
            } catch (RuntimeException failure) {
                LogAdapter.swallowedOnce(TAG, "copying formatted text failed, copying plain", failure);
                return false;
            }
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (delegate != null) delegate.onDestroyActionMode(mode);
        }
    }

    /**
     * Copy or cut of the selection. Without toolbar styles in it, Android does it as usual (returns
     * false) and the stash is dropped. With styles, the clipboard gets the plain text, the stash the
     * styles, and a cut deletes the selection, like TextView's own Copy/Cut.
     */
    private boolean copySelection(ActionMode mode, boolean cut) {
        Editable text = edit.getText();
        int from = selectionFrom();
        int to = selectionTo();
        if (text == null || from >= to) return false;
        List<FormatRun> runs = FormatRuns.slice(text.toString(), FormatSpans.collectRuns(text), from, to);
        ClipboardManager clipboard = (ClipboardManager) edit.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (runs.isEmpty() || clipboard == null || !FormatSend.isEnabled()) {
            FormatClipboard.clear();
            LogAdapter.i(TAG, FormatLog.CLIP + "composer " + (cut ? "cut" : "copy") + ": skipped ("
                + (runs.isEmpty() ? "no styles in selection" : clipboard == null ? "no clipboard service" : "toggle off")
                + "), Android copies plain selLen=" + (to - from));
            return false;
        }
        String selected = text.subSequence(from, to).toString();
        long copiedAt = System.currentTimeMillis();
        clipboard.setPrimaryClip(ClipData.newPlainText(null, selected));
        FormatClipboard.onComposerCopy(edit.getContext(), selected, runs, copiedAt);
        LogAdapter.i(TAG, FormatLog.CLIP + "composer " + (cut ? "cut" : "copy") + ": stashed selLen="
            + selected.length() + " selection=[" + from + "," + to + ") runs=" + FormatLog.kindCounts(runs));
        if (cut) text.delete(from, to);
        mode.finish();
        return true;
    }

    /** IME recompositions repeat on every key: sampled. */
    private static final FormatLog.Sampler RECOMPOSITIONS = new FormatLog.Sampler();

    /**
     * Gives the {@code count} chars that replaced {@code before} chars at {@code start} the style
     * they were typed with ({@link FormatRecompose}): the common prefix and suffix with the
     * replaced text keep their old styles, and only the differing middle is styled, with the typing
     * style (pending included) when it is at the caret or pending anchor, else with the style of the
     * old chars it replaced. Returns true when the pending style was applied to inserted chars.
     */
    private boolean applyInsertedStyle(Editable text, int start, int before, int count) {
        String inserted = text.subSequence(start, start + count).toString();
        FormatRecompose.Plan plan = FormatRecompose.plan(changeOld, inserted, start, changeCaret,
            pending != null ? pendingAnchor : -1, changeFromPending);
        FormatRecompose.Split split = plan.split;
        for (int index = 0; index < INLINE_KINDS.length; index++) {
            Kind kind = INLINE_KINDS[index];
            List<Run> old = changeOldRuns.get(index);
            int value = FormatRecompose.middleValue(plan, changeTyping.valueOf(kind), old, changeReplaced.valueOf(kind));
            List<Run> runs = readRuns(text, kind);
            writeIfChanged(text, kind, runs, FormatRecompose.remap(runs, old, start, before, count, split, value));
        }
        boolean keptPending = !plan.consumesPending && pending != null;
        // An edit before the anchor (a tone mark earlier in the word) moves the anchor with the text.
        if (keptPending) pendingAnchor = FormatRecompose.shiftedAnchor(plan, start, pendingAnchor);
        if (before > 0 && RECOMPOSITIONS.next()) {
            LogAdapter.i(TAG, FormatLog.EDIT + "recomposition kind=" + split.kind()
                + " prefix=" + split.prefix + " middle=" + split.oldMiddle + "->" + split.newMiddle + " suffix=" + split.suffix
                + " middleStyle=" + (plan.atCaret ? (changeFromPending ? "pending" : "caret") : plan.source >= 0 ? "old" : "replaced")
                + " pendingConsumed=" + plan.consumesPending + " pendingKept=" + keptPending
                + " seen=" + RECOMPOSITIONS.count());
        }
        return plan.consumesPending;
    }

    /** Removes foreign formatting spans inside {@code [from, to)}; returns {@code count {classes}} removed, or null. */
    private static String stripForeignFormatting(Editable text, int from, int to) {
        int removed = 0;
        Set<String> classes = null;
        for (Object span : text.getSpans(from, to, Object.class)) {
            if (!FOREIGN_FORMATTING.contains(span.getClass())) continue;
            if ((text.getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0) continue;
            if (text.getSpanStart(span) >= from && text.getSpanEnd(span) <= to) {
                text.removeSpan(span);
                removed++;
                if (classes == null) classes = new java.util.TreeSet<>();
                classes.add(span.getClass().getSimpleName());
            }
        }
        return removed == 0 ? null : removed + " {" + String.join(",", classes) + "}";
    }

    /** Keeps the empty-last-paragraph line style at its paragraph through edits before it. */
    private void shiftMemory(int start, int before, int count) {
        if (memoryStart < 0 || start >= memoryStart) return;
        memoryStart = start + before <= memoryStart ? memoryStart + count - before : -1;
    }

    /** A newline typed at {@link #enterAt}: continue the list/indent, or leave the list on an empty item. */
    private void handleEnter(Editable text) {
        int at = enterAt;
        int[] values = enterValues;
        if (values == null || at < 0 || at >= text.length() || text.charAt(at) != '\n') return;
        int list = values[0];
        int indent = values[1];
        if (list == 0 && indent == 0) return;
        Map<Integer, int[]> overrides = new HashMap<>();
        LogAdapter.i(TAG, FormatLog.EDIT + "enter: "
            + (list != 0 && enterParagraphEmpty ? "list exit on empty item" : "continue") + " list=" + list + " indent=" + indent
            + " at=" + at);
        if (list != 0 && enterParagraphEmpty) {
            internal = true;
            try {
                text.delete(at, at + 1);
            } finally {
                internal = false;
            }
            List<int[]> paragraphs = FormatRanges.paragraphs(text);
            int[] paragraph = paragraphs.get(FormatRanges.paragraphIndex(paragraphs, at));
            overrides.put(paragraph[0], new int[] { 0, indent });
        } else {
            overrides.put(at + 1, new int[] { list, indent });
        }
        normalizeLines(text, overrides);
    }

    // ---- Line styles ----

    private interface LineUpdate {
        void apply(int[] lists, int[] indents, int index);
    }

    private void updateLines(Editable text, LineUpdate update) {
        List<int[]> paragraphs = FormatRanges.paragraphs(text);
        int[][] values = lineValues(text, paragraphs);
        int[] touched = touched(text, paragraphs);
        Map<Integer, int[]> overrides = new HashMap<>();
        for (int index = touched[0]; index <= touched[1]; index++) {
            update.apply(values[0], values[1], index);
            overrides.put(paragraphs.get(index)[0], new int[] { values[0][index], values[1][index] });
        }
        normalizeLines(text, overrides);
    }

    private int[] touched(CharSequence text, List<int[]> paragraphs) {
        return FormatRanges.touchedParagraphs(paragraphs, text, selectionFrom(), selectionTo());
    }

    /** {@code [lists, indents]} per paragraph, from the covering markers and the empty-last-paragraph memory. */
    private int[][] lineValues(Spanned text, List<int[]> paragraphs) {
        ListMarker[] lists = sortedByStart(text, ListMarker.class);
        IndentMarker[] indents = sortedByStart(text, IndentMarker.class);
        int[][] values = new int[2][paragraphs.size()];
        for (int index = 0; index < paragraphs.size(); index++) {
            int start = paragraphs.get(index)[0];
            boolean empty = start == paragraphs.get(index)[1];
            values[0][index] = coveringValue(text, lists, start, empty);
            values[1][index] = coveringValue(text, indents, start, empty);
            if (values[0][index] == 0 && values[1][index] == 0 && start == memoryStart) {
                values[0][index] = memoryList;
                values[1][index] = memoryIndent;
            }
        }
        return values;
    }

    private static <T> T[] sortedByStart(Spanned text, Class<T> type) {
        T[] spans = text.getSpans(0, text.length(), type);
        Arrays.sort(spans, (a, b) -> Integer.compare(text.getSpanStart(a), text.getSpanStart(b)));
        return spans;
    }

    /**
     * Value of the first marker covering the paragraph start. An emptied last paragraph (its text was
     * deleted) is recognized by the zero-length marker left at its start; normalizing then moves that
     * line style to the empty-paragraph memory and drops the span.
     */
    private static int coveringValue(Spanned text, ToolbarSpan[] markers, int paragraphStart, boolean emptyParagraph) {
        for (ToolbarSpan marker : markers) {
            int start = text.getSpanStart(marker);
            int end = text.getSpanEnd(marker);
            if (FormatRanges.markerCovers(start, end, paragraphStart)
                    || (emptyParagraph && start == paragraphStart && end == paragraphStart)) {
                return marker.value();
            }
        }
        return 0;
    }

    /**
     * Rewrites the line markers to exactly one per formatted paragraph, applying {@code overrides}
     * (paragraph start to {@code [list, indent]}). Only markers that differ are replaced.
     */
    private void normalizeLines(Editable text, Map<Integer, int[]> overrides) {
        boolean hasOverrides = overrides != null && !overrides.isEmpty();
        if (!hasOverrides && memoryStart < 0 && !hasMarkers(text)) return;
        List<int[]> paragraphs = FormatRanges.paragraphs(text);
        int[][] values = lineValues(text, paragraphs);
        int count = paragraphs.size();
        if (hasOverrides) {
            for (int index = 0; index < count; index++) {
                int[] override = overrides.get(paragraphs.get(index)[0]);
                if (override == null) continue;
                values[0][index] = override[0];
                values[1][index] = FormatRanges.steppedIndent(override[1], 0);
            }
        }
        int[] last = paragraphs.get(count - 1);
        memoryStart = -1;
        if (last[0] == last[1] && (values[0][count - 1] != 0 || values[1][count - 1] != 0)) {
            memoryStart = last[0];
            memoryList = values[0][count - 1];
            memoryIndent = values[1][count - 1];
        }
        // An empty item restarts the numbering after it, like Zalo's receiver (the wire skips empty lines).
        int[] numbers = FormatRanges.listNumbers(values[0], FormatRanges.emptyParagraphs(text, paragraphs),
            FormatRun.LIST_NUMBERED);

        List<ListMarker> keptLists = new ArrayList<>();
        List<IndentMarker> keptIndents = new ArrayList<>();
        ListMarker[] existingLists = text.getSpans(0, text.length(), ListMarker.class);
        IndentMarker[] existingIndents = text.getSpans(0, text.length(), IndentMarker.class);
        for (int index = 0; index < count; index++) {
            int start = paragraphs.get(index)[0];
            int end = paragraphs.get(index)[1];
            if (start >= end) continue;
            int list = values[0][index];
            int indent = values[1][index];
            if (list != 0) {
                ListMarker match = null;
                for (ListMarker marker : existingLists) {
                    if (!keptLists.contains(marker) && marker.value() == list && marker.number() == Math.max(1, numbers[index])
                            && text.getSpanStart(marker) == start && text.getSpanEnd(marker) == end) {
                        match = marker;
                        break;
                    }
                }
                if (match != null) keptLists.add(match);
                else keptLists.add(null);
            }
            if (indent != 0) {
                IndentMarker match = null;
                for (IndentMarker marker : existingIndents) {
                    if (!keptIndents.contains(marker) && marker.value() == indent
                            && text.getSpanStart(marker) == start && text.getSpanEnd(marker) == end) {
                        match = marker;
                        break;
                    }
                }
                if (match != null) keptIndents.add(match);
                else keptIndents.add(null);
            }
        }
        for (ListMarker marker : existingLists) if (!keptLists.contains(marker)) text.removeSpan(marker);
        for (IndentMarker marker : existingIndents) if (!keptIndents.contains(marker)) text.removeSpan(marker);
        int listSlot = 0;
        int indentSlot = 0;
        for (int index = 0; index < count; index++) {
            int start = paragraphs.get(index)[0];
            int end = paragraphs.get(index)[1];
            if (start >= end) continue;
            if (values[1][index] != 0) {
                if (keptIndents.get(indentSlot) == null) {
                    text.setSpan(new IndentMarker(values[1][index]), start, end, MARKER_FLAGS | INDENT_PRIORITY);
                }
                indentSlot++;
            }
            if (values[0][index] != 0) {
                if (keptLists.get(listSlot) == null) {
                    text.setSpan(new ListMarker(values[0][index], numbers[index]), start, end, MARKER_FLAGS);
                }
                listSlot++;
            }
        }
    }
}
