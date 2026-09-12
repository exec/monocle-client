package dev.monocle.client.gui.screens;

import com.mojang.blaze3d.platform.MacosUtil;
import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.widgets.input.WMonocleTextBox;
import dev.monocle.client.systems.bots.BotLua;
import dev.monocle.client.utils.render.color.Color;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayDeque;
import java.util.regex.Pattern;

import static com.mojang.blaze3d.platform.InputConstants.*;
import static dev.monocle.client.MonocleClient.mc;

/** Native Minecraft selection/clipboard/navigation, with Monocle rendering and bounded undo. */
public final class WorkflowCodeBox extends WMonocleTextBox {
    public static final int LIMIT = BotLua.MAX_SOURCE;
    static final Pattern TOKENS = Pattern.compile("--.*$|\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'|\\b(?:and|break|do|else|elseif|end|false|for|function|if|in|local|nil|not|or|repeat|return|then|true|until|while)\\b");
    private record Edit(String text, int cursor) {}
    private record Range(int beginIndex, int endIndex) {}
    /** The vanilla StringView return type is protected; expose only its integer range. */
    private static final class TextModel extends MultilineTextField {
        TextModel() { super(mc.font, 1_000_000); }
        Range line(int index) { var line = getLineView(index); return new Range(line.beginIndex(), line.endIndex()); }
        Range selected() { var selected = getSelected(); return new Range(selected.beginIndex(), selected.endIndex()); }
    }
    private final TextModel field = new TextModel();
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final boolean readOnly;
    private final int rows;
    private int firstLine;
    private double horizontal;
    private boolean dragging;

    public WorkflowCodeBox(String source, boolean readOnly, int rows) {
        super("", null, (text, codepoint) -> true, null);
        this.readOnly = readOnly; this.rows = rows;
        field.setCharacterLimit(LIMIT);
        set(source);
        tooltip = readOnly ? "Read-only example. Select and copy code, or duplicate it to edit."
            : "Multiline Lua editor · Tab: four spaces · Ctrl/Cmd+Z: undo · Ctrl/Cmd+Y: redo";
    }

    @Override public String get() { return field.value(); }
    @Override public void set(String value) {
        field.setValue(normalize(value));
        firstLine = 0; horizontal = 0;
    }
    static String normalize(String value) {
        if (value.length() > LIMIT) throw new IllegalArgumentException("Code exceeds the 32 KiB source limit.");
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }
    public void replace(String value) { change(() -> set(value)); }
    private static String display(String value) { return value.replace("\t", "    "); }
    private double textWidth(String value) { return theme.textWidth(display(value)); }
    @Override public void setCursorMax() { field.seekCursor(Whence.END, 0); revealCursor(); }
    @Override protected void onCalculateSize() {
        width = theme.scale(300);
        height = pad() * 2 + theme.textHeight() * rows;
    }
    private double gutter() { return theme.textWidth(Integer.toString(field.getLineCount())) + theme.scale(18); }
    private int visibleRows() { return Math.max(1, (int) ((height - pad() * 2) / theme.textHeight())); }
    private void revealCursor() {
        if (theme == null) return;
        int line = field.getLineAtCursor();
        firstLine = Math.clamp(firstLine, Math.max(0, line - visibleRows() + 1), line);
        var view = field.line(line);
        double cursorX = textWidth(get().substring(view.beginIndex(), Math.min(field.cursor(), view.endIndex())));
        double space = Math.max(theme.scale(30), width - pad() * 2 - gutter() - theme.scale(8));
        horizontal = Math.max(0, Math.min(horizontal, cursorX));
        if (cursorX > horizontal + space) horizontal = cursorX - space;
    }
    private void change(Runnable edit) {
        Edit before = new Edit(get(), field.cursor()); edit.run();
        if (!before.text().equals(get())) {
            undo.addLast(before); if (undo.size() > 32) undo.removeFirst(); redo.clear();
            if (action != null) action.run();
        }
        revealCursor();
    }
    private void restore(ArrayDeque<Edit> from, ArrayDeque<Edit> to) {
        if (from.isEmpty()) return;
        to.addLast(new Edit(get(), field.cursor()));
        Edit edit = from.removeLast(); field.setValue(edit.text()); field.seekCursor(Whence.ABSOLUTE, edit.cursor());
        revealCursor(); if (action != null) action.run();
    }
    @Override public boolean onKeyPressed(KeyEvent input) {
        if (!focused) return false;
        boolean control = (input.modifiers() & (MacosUtil.IS_MACOS ? MOD_SUPER : MOD_CONTROL)) != 0;
        if (input.key() == KEY_ESCAPE) { setFocused(false); return true; }
        if (!readOnly && control && input.key() == KEY_Z) {
            if ((input.modifiers() & MOD_SHIFT) != 0) restore(redo, undo); else restore(undo, redo);
            return true;
        }
        if (!readOnly && control && input.key() == KEY_Y) { restore(redo, undo); return true; }
        boolean navigation = input.key() == KEY_LEFT || input.key() == KEY_RIGHT || input.key() == KEY_UP || input.key() == KEY_DOWN
            || input.key() == KEY_HOME || input.key() == KEY_END || input.key() == KEY_PAGEUP || input.key() == KEY_PAGEDOWN;
        if (readOnly && !navigation && !(control && (input.key() == KEY_C || input.key() == KEY_A))) return true;
        if (!readOnly && control && input.key() == KEY_V) {
            String pasted = mc.keyboardHandler.getClipboard();
            if (pasted.length() + get().length() - field.getSelectedText().length() > LIMIT) return true;
            change(() -> {
                var selected = field.selected(); String insertion = normalize(pasted);
                field.setValue(get().substring(0, selected.beginIndex()) + insertion + get().substring(selected.endIndex()));
                field.seekCursor(Whence.ABSOLUTE, selected.beginIndex() + insertion.length());
            });
            return true;
        }
        change(() -> { if (input.key() == KEY_TAB) field.insertText("    "); else field.keyPressed(input); });
        return true;
    }
    @Override public boolean onKeyRepeated(KeyEvent input) { return onKeyPressed(input); }
    @Override public boolean onCharTyped(CharacterEvent input) {
        if (!focused) return false;
        if (!readOnly && input.isAllowedChatCharacter()) change(() -> field.insertText(input.codepointAsString()));
        return true;
    }
    private void point(double mouseX, double mouseY, boolean select) {
        int line = Math.clamp(firstLine + (int) Math.floor((mouseY - y - pad()) / theme.textHeight()), 0, field.getLineCount() - 1);
        var view = field.line(line);
        double target = mouseX - x - pad() - gutter() + horizontal, best = Double.MAX_VALUE;
        int offset = view.endIndex(); double textX = 0;
        for (int i = view.beginIndex(); i <= view.endIndex();) {
            double distance = Math.abs(textX - target);
            if (distance < best) { best = distance; offset = i; }
            else if (textX > target) break;
            if (i == view.endIndex()) break;
            int next = i + Character.charCount(get().codePointAt(i));
            textX += textWidth(get().substring(i, next)); i = next;
        }
        field.setSelecting(select); field.seekCursor(Whence.ABSOLUTE, offset); revealCursor();
    }
    @Override public boolean onMouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!mouseOver) { setFocused(false); return false; }
        setFocused(true);
        if (click.button() == MOUSE_BUTTON_LEFT) { point(click.x(), click.y(), false); if (doubled) field.selectWordAtCursor(); dragging = true; }
        return true;
    }
    @Override public void onMouseMoved(double mouseX, double mouseY, double lastX, double lastY) { if (dragging) point(mouseX, mouseY, true); }
    @Override public boolean onMouseReleased(MouseButtonEvent click) { dragging = false; field.setSelecting(false); return false; }
    @Override public boolean onMouseScrolled(double amount) {
        if (!mouseOver) return false;
        firstLine = Math.clamp(firstLine - (int) Math.copySign(Math.max(1, Math.abs(amount) * 3), amount), 0, Math.max(0, field.getLineCount() - visibleRows()));
        return true;
    }
    @Override protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        renderInset(renderer, this, focused, mouseOver);
        double lineHeight = theme.textHeight(), startX = x + pad() + gutter();
        renderer.scissorStart(x + pad(), y + pad(), width - pad() * 2, height - pad() * 2);
        String source = get(); var selected = field.selected();
        for (int line = firstLine; line < Math.min(field.getLineCount(), firstLine + visibleRows()); line++) {
            var view = field.line(line); String value = source.substring(view.beginIndex(), view.endIndex());
            double lineY = y + pad() + (line - firstLine) * lineHeight;
            renderer.text(Integer.toString(line + 1), x + pad(), lineY, theme.textSecondaryColor(), false);
            renderer.scissorStart(startX, lineY, Math.max(1, x + width - pad() - startX), lineHeight);
            int a = Math.max(view.beginIndex(), selected.beginIndex()), b = Math.min(view.endIndex(), selected.endIndex());
            if (focused && a < b) renderer.quad(startX - horizontal + textWidth(source.substring(view.beginIndex(), a)), lineY,
                textWidth(source.substring(a, b)), lineHeight, MonocleStyle.alpha(theme().accentColor.get(), .25));
            double textX = startX - horizontal; int previous = 0;
            var matcher = TOKENS.matcher(value);
            while (matcher.find()) {
                String plain = value.substring(previous, matcher.start()); renderer.text(display(plain), textX, lineY, theme.textColor(), false); textX += textWidth(plain);
                String token = matcher.group(); Color color = token.startsWith("--") ? new Color(125, 167, 143) : token.startsWith("\"") || token.startsWith("'") ? new Color(226, 197, 144) : new Color(166, 169, 228);
                renderer.text(display(token), textX, lineY, color, false); textX += textWidth(token); previous = matcher.end();
            }
            renderer.text(display(value.substring(previous)), textX, lineY, theme.textColor(), false);
            if (focused && field.getLineAtCursor() == line && System.currentTimeMillis() % 1000 < 600)
                renderer.quad(startX - horizontal + textWidth(source.substring(view.beginIndex(), Math.min(field.cursor(), view.endIndex()))), lineY, theme.scale(1), lineHeight, theme().accentColor.get());
            renderer.scissorEnd();
        }
        renderer.scissorEnd();
    }
}
