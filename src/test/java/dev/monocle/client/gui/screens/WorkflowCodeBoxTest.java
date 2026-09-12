package dev.monocle.client.gui.screens;

import java.util.List;

/** Pure editor input/highlighting checks; does not create a Minecraft UI or execute a script. */
public final class WorkflowCodeBoxTest {
    public static void run() {
        assert WorkflowCodeBox.normalize("a\r\n\tb\rc").equals("a\n\tb\nc") : "Literal tabs in Lua strings must survive import";
        assert WorkflowCodeBox.normalize("x".repeat(WorkflowCodeBox.LIMIT)).length() == WorkflowCodeBox.LIMIT;
        boolean rejected = false;
        try { WorkflowCodeBox.normalize("x".repeat(WorkflowCodeBox.LIMIT + 1)); }
        catch (IllegalArgumentException expected) { rejected = true; }
        assert rejected : "Oversized source must not be silently truncated";
        var tokens = WorkflowCodeBox.TOKENS.matcher("local x = '-- not a comment' -- real comment").results().map(result -> result.group()).toList();
        assert tokens.equals(List.of("local", "'-- not a comment'", "-- real comment"));
        tokens = WorkflowCodeBox.TOKENS.matcher("return \"a\\\"b\"").results().map(result -> result.group()).toList();
        assert tokens.equals(List.of("return", "\"a\\\"b\"")) : "Escaped quotes must remain part of their string token";
        assert WorkflowCodeBox.TOKENS.matcher("\"" + "x".repeat(WorkflowCodeBox.LIMIT - 2) + "\"").matches() : "A maximum-length string must not overflow the highlighter stack";
    }
}
