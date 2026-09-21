/*
 * MIT License
 *
 * Copyright (c) 2026 Steve Hannah
 */
package ca.weblite.webview;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canvas 7 Safeguards: each script-message channel is connected exactly once
 * per engine-creation function.
 *
 * <p>{@code g_signal_connect} is additive, so a second
 * {@code script-message-received::<channel>} connection on the same
 * {@code WebKitUserContentManager} does not replace the first — both
 * callbacks run and every message the page posts reaches Java twice.  That
 * defect is invisible from every layer above it: the page posts once, the
 * shim carries one id, and {@link FunctionDispatcher} hands the same argument
 * to the handler twice.  It shipped once, in {@code gtk_off_create_engine},
 * where the {@code external} channel was connected by two changes on the same
 * day; because every binding multiplexes through {@code external} it doubled
 * every {@code addJavascriptFunction} call, every
 * {@code addJavascriptCallback}, console capture and evalAsync resolution on
 * Linux — the only platform that defaults to LIGHTWEIGHT.
 *
 * <p>This is a source-shape test rather than a runtime one on purpose: the
 * wiring lives in C, the duplicate is only observable with a live GTK engine
 * and a display, and the failure is a silent doubling rather than a crash.
 * Reading the source is the cheapest check that would have caught it, and it
 * keeps catching it for channels added later.
 */
public class ScriptMessageChannelWiringTest {

    /** {@code g_signal_connect(x, "script-message-received::<channel>"} */
    private static final Pattern CONNECT = Pattern.compile(
        "\"script-message-received::([A-Za-z0-9_]+)\"");

    /** A top-level definition: something starting in column 0 and opening a paren. */
    private static final Pattern FUNCTION = Pattern.compile(
        "^[A-Za-z_][A-Za-z0-9_ *]*\\*?\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*");

    @Test
    public void eachChannelIsConnectedOncePerEngineFunction() throws IOException {
        File src = new File("src_c/webview_embed.cpp");
        assertTrue("expected " + src.getPath() + " relative to the module root; "
            + "run the tests from the repository root", src.isFile());

        List<String> lines = Files.readAllLines(src.toPath(), StandardCharsets.UTF_8);

        // function name -> "channel" -> how many times it is connected there
        Map<String, Map<String, Integer>> counts =
            new LinkedHashMap<String, Map<String, Integer>>();
        String current = "<file scope>";

        for (String line : lines) {
            String trimmed = line.trim();
            if (FUNCTION.matcher(line).matches()
                && !trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                current = line.trim();
            }
            Matcher m = CONNECT.matcher(line);
            while (m.find()) {
                String channel = m.group(1);
                Map<String, Integer> perChannel = counts.get(current);
                if (perChannel == null) {
                    perChannel = new LinkedHashMap<String, Integer>();
                    counts.put(current, perChannel);
                }
                Integer n = perChannel.get(channel);
                perChannel.put(channel, n == null ? 1 : n.intValue() + 1);
            }
        }

        // Guard against the check silently passing because the pattern stopped
        // matching (a rename, a reformat) rather than because the source is sound.
        assertFalse("found no script-message-received:: connections at all — "
            + "the test's pattern has gone stale, not the source", counts.isEmpty());

        List<String> offenders = new ArrayList<String>();
        for (Map.Entry<String, Map<String, Integer>> fn : counts.entrySet()) {
            for (Map.Entry<String, Integer> ch : fn.getValue().entrySet()) {
                if (ch.getValue().intValue() > 1) {
                    offenders.add("channel \"" + ch.getKey() + "\" connected "
                        + ch.getValue() + " times in: " + fn.getKey());
                }
            }
        }
        assertTrue("g_signal_connect is additive, so each of these delivers every"
            + " posted message to Java once per connection:\n  "
            + String.join("\n  ", offenders), offenders.isEmpty());
    }
}
