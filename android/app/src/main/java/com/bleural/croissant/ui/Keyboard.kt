package com.bleural.croissant.ui

import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.flow.drop

/*
 * The on-screen keyboard, drawn by the design instead of the system's (LatinIME takes 383
 * to 450 px of the 640 and brings the navigation bar back). Shared here: which field has
 * it, the keys as data, the touches and the editing. A design provides a [KeyboardState]
 * round its pages with [OwnKeyboard], makes every text field an [OwnTextField], and draws
 * the keys with [Keys] and a [KeySkin] of its own, or the plain one (PlainKeys.kt). The
 * system's keyboard never comes: every design's content is under [NoSystemKeyboard]
 * (Designs.build), and [OwnTextField] is the only text field in the app (KeyboardGuardTest).
 */

/** What a field takes, from its `KeyboardOptions.keyboardType`. */
enum class KeyMode { Text, Password, Uri, Number }

/** The keys' pages: the letters and two of symbols. Number fields have their own pad. */
enum class KeyPage { Letters, Symbols, More }

enum class Shift { Off, Once, Lock }

enum class KeyKind { Char, Shift, Delete, Space, Action, Page }

/** A key: [text] is what it types (a [KeyKind.Char]) or the page it opens (a [KeyKind.Page]); [span] in key widths. */
data class Key(val kind: KeyKind, val text: String = "", val span: Float = 1f)

/** A row of keys; [inset] in key widths at each end, which the end keys' touches still reach. */
data class KeyRow(val keys: List<Key>, val inset: Float = 0f) {
    val width: Float get() = keys.sumOf { it.span.toDouble() }.toFloat() + 2 * inset
}

/** What a design draws for one key: its touch cell [cell] (the whole of it, no gaps), lit while [pressed]. */
data class KeyCap(val key: Key, val label: String, val cell: Rect, val pressed: Boolean)

/** A design's look for the keys. [Keys] lays them out and takes the touches; this draws. */
interface KeySkin {
    /** One row's height, and the keyboard's padding above the first and under the last. */
    val row: Dp
    val top: Dp
    val bottom: Dp
    /** The ground under the keys, then each key. */
    fun DrawScope.ground()
    fun DrawScope.key(cap: KeyCap, state: KeyboardState)
    /** Over everything, for each pressed character key: the character shown above the finger. */
    fun DrawScope.popup(cap: KeyCap)
}

/** The keys' height for [rows] rows, what the pages stand above while they are up. */
fun KeySkin.height(rows: Int): Dp = top + row * rows + bottom

/** A field the keyboard types into, while it has the focus; every key's edit goes through its [input], as the field's own edits do. */
class KeyField(
    val text: TextFieldState,
    val mode: KeyMode,
    val action: ImeAction,
    val caps: KeyboardCapitalization,
    val onAction: () -> Unit,
    val input: () -> InputTransformation? = { null },
)

@Stable
class KeyboardState {
    var field: KeyField? by mutableStateOf(null)
        private set
    private var hidden by mutableStateOf(false)
    var page by mutableStateOf(KeyPage.Letters)
    var shift by mutableStateOf(Shift.Off)
    private var shiftAt = 0L

    /** Up while a field has the focus, unless the page put it away (a search key). */
    val open: Boolean get() = this.field != null && !hidden

    val rows: List<KeyRow>
        get() = this.field?.let { Layouts.rows(it.mode, page) } ?: emptyList()

    fun attach(f: KeyField) {
        field = f
        hidden = false
        page = KeyPage.Letters
        shift = Shift.Off
        autoShift()
    }

    fun detach(f: KeyField) {
        if (field === f) field = null
    }

    fun show() { hidden = false }
    fun hide() { hidden = true }

    /** Where the keys and the fields are on screen, for [tapAwayClosesKeys]. */
    var keysAt = Rect.Zero
    private val fields = HashMap<TextFieldState, Rect>()
    fun place(f: TextFieldState, at: Rect) { fields[f] = at }
    fun unplace(f: TextFieldState) { fields.remove(f) }

    /** On the keys or a text field, or near enough one that a near miss does not close the keys. */
    fun typingAt(p: Offset): Boolean = keysAt.contains(p) || fields.values.any { it.inflate(NEAR_FIELD).contains(p) }

    /** The wheel: the caret a character on for each tick, down to the right; a selection closes to the end it turns towards. */
    fun moveCaret(delta: Int): Boolean {
        val f = field ?: return false
        val sel = f.text.selection
        val to = if (sel.collapsed) stepped(f.text.text, sel.start, delta) else if (delta < 0) sel.min else sel.max
        if (sel.collapsed && to == sel.start) return false
        f.text.edit { selection = TextRange(to) }
        autoShift()
        return true
    }

    fun label(k: Key): String =
        if (k.kind == KeyKind.Char && shift != Shift.Off && field?.mode != KeyMode.Number) k.text.uppercase() else k.text

    /** A key let go (or pressed, for delete and its repeats). */
    fun press(k: Key) {
        val f = field ?: return
        when (k.kind) {
            KeyKind.Char -> {
                apply(f, typed(f.text.selection, label(k)))
                if (shift == Shift.Once) shift = Shift.Off
                autoShift()
            }
            KeyKind.Space -> {
                apply(f, typed(f.text.selection, " "))
                autoShift()
            }
            KeyKind.Delete -> {
                deleted(f.text.text, f.text.selection)?.let { apply(f, it) }
                autoShift()
            }
            KeyKind.Shift -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == Shift.Once && now - shiftAt < DOUBLE_TAP_MS -> Shift.Lock
                    shift == Shift.Off -> Shift.Once
                    else -> Shift.Off
                }
                shiftAt = now
            }
            KeyKind.Page -> page = KeyPage.valueOf(k.text)
            KeyKind.Action -> f.onAction()
        }
    }

    private fun autoShift() {
        val f = field ?: return
        if (shift == Shift.Lock || f.mode != KeyMode.Text) return
        if (capsAt(f.text.text, f.text.selection.min, f.caps)) shift = Shift.Once
        else if (shift == Shift.Once) shift = Shift.Off
    }

    // Written to the state, so not through the field: its input transformation (a length, the
    // characters it takes) is run here, and may take the edit back.
    private fun apply(f: KeyField, e: Edit) = f.text.edit {
        replace(e.start, e.end, e.text)
        selection = TextRange(e.start + e.text.length)
        f.input()?.run { transformInput() }
    }

    companion object {
        const val DOUBLE_TAP_MS = 350L
        const val REPEAT_AFTER_MS = 420L
        const val REPEAT_EVERY_MS = 60L
        const val NEAR_FIELD = 16f
    }
}

/** An edit: [start] to [end] replaced by [text], the caret after it. */
internal data class Edit(val start: Int, val end: Int, val text: String)

internal fun typed(sel: TextRange, s: String) = Edit(sel.min, sel.max, s)

/** Delete: the selection, or the one character (a whole surrogate pair) before the caret. */
internal fun deleted(text: CharSequence, sel: TextRange): Edit? = when {
    !sel.collapsed -> Edit(sel.min, sel.max, "")
    sel.start == 0 -> null
    else -> Edit(sel.start - Character.charCount(Character.codePointBefore(text, sel.start)), sel.start, "")
}

/** [at] moved [delta] characters (a surrogate pair is one), held to the text. */
internal fun stepped(text: CharSequence, at: Int, delta: Int): Int {
    var i = at.coerceIn(0, text.length)
    repeat(abs(delta)) {
        i = if (delta > 0) {
            if (i >= text.length) return i
            i + Character.charCount(Character.codePointAt(text, i))
        } else {
            if (i <= 0) return i
            i - Character.charCount(Character.codePointBefore(text, i))
        }
    }
    return i
}

/** Whether the field's capitalisation asks for a capital at [at]. */
internal fun capsAt(text: CharSequence, at: Int, caps: KeyboardCapitalization): Boolean {
    val before = text.subSequence(0, at.coerceIn(0, text.length)).trimEnd()
    val gap = at > 0 && at <= text.length && text[at - 1] == ' '
    return when (caps) {
        KeyboardCapitalization.Characters -> true
        KeyboardCapitalization.Words -> before.isEmpty() || gap
        KeyboardCapitalization.Sentences -> before.isEmpty() || (gap && before.last() in ".!?")
        else -> false
    }
}

internal fun modeOf(type: KeyboardType): KeyMode = when (type) {
    KeyboardType.Password -> KeyMode.Password
    KeyboardType.Uri, KeyboardType.Email -> KeyMode.Uri
    KeyboardType.Number, KeyboardType.NumberPassword, KeyboardType.Phone, KeyboardType.Decimal -> KeyMode.Number
    else -> KeyMode.Text
}

/** A single-line field's action key: what it asks for, done when it asks for nothing. */
internal fun actionOf(a: ImeAction): ImeAction = if (a == ImeAction.Default || a == ImeAction.Unspecified) ImeAction.Done else a

/** The keys as data. Every row of a page is ten key widths, the number pad's three. */
internal object Layouts {
    private fun chars(s: String) = s.map { Key(KeyKind.Char, it.toString()) }
    private val delete = Key(KeyKind.Delete, span = 1.5f)
    private val action = Key(KeyKind.Action, span = 2.5f)
    private fun page(p: KeyPage, span: Float = 1.5f) = Key(KeyKind.Page, p.name, span)

    private fun bottom(mode: KeyMode, to: KeyPage) = KeyRow(
        if (mode == KeyMode.Uri) listOf(page(to), Key(KeyKind.Char, "-"), Key(KeyKind.Char, "/"), Key(KeyKind.Space, span = 3f), Key(KeyKind.Char, "."), action)
        else listOf(page(to), Key(KeyKind.Char, ","), Key(KeyKind.Space, span = 4f), Key(KeyKind.Char, "."), action),
    )

    private val made = HashMap<Pair<KeyMode, KeyPage>, List<KeyRow>>()

    /** Made once each, so a key is the same object for the touches and the drawing. */
    fun rows(mode: KeyMode, page: KeyPage): List<KeyRow> = made.getOrPut(mode to page) { make(mode, page) }

    private fun make(mode: KeyMode, page: KeyPage): List<KeyRow> = when {
        mode == KeyMode.Number -> listOf(
            KeyRow(chars("123")), KeyRow(chars("456")), KeyRow(chars("789")),
            KeyRow(listOf(Key(KeyKind.Delete), Key(KeyKind.Char, "0"), Key(KeyKind.Action))),
        )
        page == KeyPage.Letters -> listOf(
            KeyRow(chars("qwertyuiop")),
            KeyRow(chars("asdfghjkl"), inset = 0.5f),
            KeyRow(listOf(Key(KeyKind.Shift, span = 1.5f)) + chars("zxcvbnm") + delete),
            bottom(mode, KeyPage.Symbols),
        )
        page == KeyPage.Symbols -> listOf(
            KeyRow(chars("1234567890")),
            KeyRow(chars("@#$%&-+()"), inset = 0.5f),
            KeyRow(listOf(page(KeyPage.More)) + chars("*\"':;!?") + delete),
            bottom(mode, KeyPage.Letters),
        )
        else -> listOf(
            KeyRow(chars("~`|\\/^_=<>")),
            KeyRow(chars("[]{}€£¥°•"), inset = 0.5f),
            KeyRow(listOf(page(KeyPage.Symbols)) + chars("¿¡«»§¶…") + delete),
            bottom(mode, KeyPage.Letters),
        )
    }
}

val LocalKeyboard = staticCompositionLocalOf<KeyboardState?> { null }

/**
 * A design's pages with its own keyboard: [OwnTextField]s attach to [state], and a page's
 * `LocalSoftwareKeyboardController` (a search key putting the keyboard away, a tap on the
 * field bringing it back) works this keyboard rather than the system's.
 */
@Composable
fun OwnKeyboard(state: KeyboardState, content: @Composable () -> Unit) {
    val controller = remember(state) {
        object : SoftwareKeyboardController {
            override fun show() = state.show()
            override fun hide() = state.hide()
        }
    }
    CompositionLocalProvider(LocalKeyboard provides state, LocalSoftwareKeyboardController provides controller, content = content)
}

/** The system's keyboard never starts: the field keeps its focus, caret and selection, and no input method is bound. */
private val NoInputMethod = PlatformTextInputInterceptor { _, _ -> awaitCancellation() }

/**
 * Round a design's whole content (Designs.build): no field under it, an [OwnTextField] or
 * not, ever starts the system's input method.
 */
@Composable
fun NoSystemKeyboard(content: @Composable () -> Unit) = InterceptPlatformTextInput(NoInputMethod, content)

/**
 * The app's one text field: a [BasicTextField] on [state], typed into by the design's own
 * keyboard while it has the focus. Its parameters are BasicTextField's, [keyboardActions]
 * for its `onKeyboardAction`: the keyboard's type and action key come from
 * [keyboardOptions]. Without an [OwnKeyboard] round it, no keyboard at all.
 */
@Composable
fun OwnTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = Grotesk,
    cursorBrush: Brush = SolidColor(Color.White),
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    decorator: TextFieldDecorator? = null,
) {
    OwnKeys(state, keyboardOptions, keyboardActions, inputTransformation) { keys, act ->
        BasicTextField(
            state,
            modifier = modifier.then(keys),
            textStyle = textStyle,
            cursorBrush = cursorBrush,
            lineLimits = lineLimits,
            inputTransformation = inputTransformation,
            outputTransformation = outputTransformation,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = { act() },
            decorator = decorator,
        )
    }
}

/**
 * The state of an [OwnTextField] whose value is the caller's: [value] coming back from the
 * caller is not set again, anything else it is set to replaces the text (the caller
 * filtering what was typed, say), and each change is told to [onValueChange].
 */
@Composable
fun rememberValueText(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val state = remember { TextFieldState(value, TextRange(value.length)) }
    val change by rememberUpdatedState(onValueChange)
    // What the field has told the caller lately: those values coming back are not the caller's.
    val sent = remember { ArrayDeque<String>() }
    LaunchedEffect(value) {
        val i = sent.indexOf(value)
        if (i >= 0) repeat(i + 1) { sent.removeFirst() }
        else if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).collect {
            sent.addLast(it)
            if (sent.size > 16) sent.removeFirst()
            change(it)
        }
    }
    return state
}

/**
 * A text field typed into by the design's own keyboard while it has the focus: [content]
 * gets the modifier for the field and what its action does (for the field's own
 * `onKeyboardAction`). Without an [OwnKeyboard] round it, no keyboard at all.
 */
@Composable
private fun OwnKeys(
    state: TextFieldState,
    options: KeyboardOptions,
    actions: KeyboardActions,
    input: InputTransformation?,
    content: @Composable (Modifier, () -> Unit) -> Unit,
) {
    val keyboard = LocalKeyboard.current
    val focus = LocalFocusManager.current
    val latest by rememberUpdatedState(actions)
    val action = actionOf(options.imeAction)
    val run = remember(action, focus) { { latest.perform(action, focus) } }
    if (keyboard == null) {
        InterceptPlatformTextInput(NoInputMethod) { content(Modifier, run) }
        return
    }
    val latestInput by rememberUpdatedState(input)
    val field = remember(state, options) { KeyField(state, modeOf(options.keyboardType), action, options.capitalization, run) { latestInput } }
    DisposableEffect(keyboard, field) { onDispose { keyboard.detach(field); keyboard.unplace(state) } }
    InterceptPlatformTextInput(NoInputMethod) {
        content(
            Modifier
                .onFocusChanged { if (it.isFocused) keyboard.attach(field) else keyboard.detach(field) }
                .onGloballyPositioned { keyboard.place(state, it.boundsInRoot()) },
            run,
        )
    }
}

/** What a field's [KeyboardActions] say for [action], or what the system's keyboard would do: next and previous move the focus, done lets go of it. */
fun KeyboardActions.perform(action: ImeAction, focus: FocusManager) {
    val scope = object : KeyboardActionScope {
        override fun defaultKeyboardAction(imeAction: ImeAction) {
            when (imeAction) {
                ImeAction.Next -> focus.moveFocus(FocusDirection.Next)
                ImeAction.Previous -> focus.moveFocus(FocusDirection.Previous)
                ImeAction.Done -> focus.clearFocus()
                else -> {}
            }
        }
    }
    val handler = when (action) {
        ImeAction.Go -> onGo
        ImeAction.Done -> onDone
        ImeAction.Search -> onSearch
        ImeAction.Send -> onSend
        ImeAction.Next -> onNext
        ImeAction.Previous -> onPrevious
        else -> null
    }
    if (handler != null) handler(scope) else scope.defaultKeyboardAction(action)
}

/**
 * The keys of [state]'s field, laid out in rows of [KeySkin.row] across the full width and
 * drawn by [skin]. Every touch lands on a key: a row's cells have no gaps, and its end keys
 * reach over its inset. A key types when let go; a second finger coming down types the first
 * at once, so quick overlapping taps keep their order. Delete acts as it goes down and
 * repeats while held. A finger pulled down takes the keys with it and the key it began on
 * types nothing: past a row, or flicked, the field lets go and the keys go; short of that
 * they spring back. The wheel moves the caret while they are up.
 */
@Composable
fun Keys(state: KeyboardState, skin: KeySkin, modifier: Modifier = Modifier) {
    val rows = state.rows
    if (rows.isEmpty()) return
    val pressed = remember { mutableStateMapOf<PointerId, Key>() }
    var pulled by remember { mutableFloatStateOf(0f) }
    val focus = LocalFocusManager.current
    WheelTarget { state.moveCaret(it) }
    DisposableEffect(state) { onDispose { state.keysAt = Rect.Zero } }
    val height = skin.height(rows.size)
    Canvas(
        modifier.height(height).onGloballyPositioned { state.keysAt = it.boundsInRoot() }.pointerInput(rows, skin) {
            val held = HashMap<PointerId, Key>()
            val starts = HashMap<PointerId, Offset>()
            val repeats = HashMap<PointerId, Job>()
            val tracker = VelocityTracker()
            val slop = viewConfiguration.touchSlop
            val away = skin.row.toPx()
            var pulling: PointerId? = null
            var back: Job? = null
            coroutineScope {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (c in event.changes) {
                            if (c.pressed && !c.previousPressed) {
                                val k = keyAt(rows, c.position, size.width.toFloat(), skin.top.toPx(), skin.row.toPx()) ?: continue
                                // Overlapping taps: what was down first types first.
                                held.entries.removeAll { (id, h) ->
                                    if (h.kind == KeyKind.Delete) false else { state.press(h); pressed.remove(id); true }
                                }
                                held[c.id] = k
                                pressed[c.id] = k
                                starts[c.id] = c.position
                                tracker.resetTracking()
                                tracker.addPosition(c.uptimeMillis, c.position)
                                if (k.kind == KeyKind.Delete) {
                                    state.press(k)
                                    repeats[c.id] = launch {
                                        delay(KeyboardState.REPEAT_AFTER_MS)
                                        while (true) { state.press(k); delay(KeyboardState.REPEAT_EVERY_MS) }
                                    }
                                }
                                c.consume()
                            } else if (c.pressed && c.id == pulling) {
                                tracker.addPosition(c.uptimeMillis, c.position)
                                pulled = maxOf(0f, c.position.y - starts.getValue(c.id).y)
                                c.consume()
                            } else if (c.pressed && pulling == null && held.size == 1 && c.id in held) {
                                tracker.addPosition(c.uptimeMillis, c.position)
                                val d = c.position - starts.getValue(c.id)
                                if (d.y > slop && d.y > abs(d.x)) {
                                    pulling = c.id
                                    held.remove(c.id)
                                    pressed.remove(c.id)
                                    repeats.remove(c.id)?.cancel()
                                    back?.cancel()
                                }
                            } else if (!c.pressed && c.previousPressed) {
                                if (c.id == pulling) {
                                    pulling = null
                                    if (pulled > away || tracker.calculateVelocity().y > FLING_AWAY) focus.clearFocus()
                                    else back = launch { animate(pulled, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> pulled = v } }
                                } else {
                                    repeats.remove(c.id)?.cancel()
                                    held.remove(c.id)?.let { if (it.kind != KeyKind.Delete) state.press(it) }
                                    pressed.remove(c.id)
                                }
                                starts.remove(c.id)
                                c.consume()
                            }
                        }
                    }
                }
            }
        },
    ) {
        val top = skin.top.toPx()
        val rowH = skin.row.toPx()
        // Drawn where the finger has pulled them; the touches stay where the keys are laid out.
        translate(top = pulled) {
            with(skin) { ground() }
            val caps = ArrayList<KeyCap>()
            forEachCell(rows, size.width, top, rowH) { k, cell ->
                val cap = KeyCap(k, state.label(k), cell, pressed.values.any { it === k })
                caps += cap
                with(skin) { key(cap, state) }
            }
            for (cap in caps) if (cap.pressed && cap.key.kind == KeyKind.Char) with(skin) { popup(cap) }
        }
    }
}

/** How fast a pull must be let go, in px a second downwards, to put the keys away short of a row. */
private const val FLING_AWAY = 1200f

/**
 * Round a design's pages: a tap anywhere but a text field or the keys lets go of the field,
 * and the keys go with it. A drag (a list scrolling, the edge drag back, the control centre
 * pulled down) is not a tap and leaves them up. It only watches: what was tapped still gets
 * the tap.
 */
fun Modifier.tapAwayClosesKeys(state: KeyboardState, focus: FocusManager): Modifier = pointerInput(state, focus) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (state.field == null || state.typingAt(down.position)) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((c.position - down.position).getDistance() > slop) return@awaitEachGesture
            if (!c.pressed) break
        }
        focus.clearFocus()
    }
}

private inline fun forEachCell(rows: List<KeyRow>, width: Float, top: Float, rowH: Float, each: (Key, Rect) -> Unit) {
    rows.forEachIndexed { i, row ->
        val unit = width / row.width
        var x = row.inset * unit
        val y = top + i * rowH
        for (k in row.keys) {
            val w = k.span * unit
            each(k, Rect(x, y, x + w, y + rowH))
            x += w
        }
    }
}

/** The key under [p]: the row it is in (the first and last take what is above and under them), the cell across, the end keys over the inset. */
internal fun keyAt(rows: List<KeyRow>, p: Offset, width: Float, top: Float, rowH: Float): Key? {
    if (rows.isEmpty()) return null
    val row = rows[((p.y - top) / rowH).toInt().coerceIn(0, rows.lastIndex)]
    val unit = width / row.width
    var x = row.inset * unit
    for ((i, k) in row.keys.withIndex()) {
        x += k.span * unit
        if (p.x < x || i == row.keys.lastIndex) return k
    }
    return null
}
