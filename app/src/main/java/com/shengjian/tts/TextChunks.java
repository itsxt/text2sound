package com.shengjian.tts;

import java.util.ArrayList;
import java.util.List;

/** Splits by UTF-16 length, preferring natural pauses and preserving every character. */
public final class TextChunks {
    private TextChunks() {}

    public static List<String> split(String text, int limit) {
        if (limit < 2) throw new IllegalArgumentException("Chunk limit must be at least 2");
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + limit, text.length());
            if (end < text.length()) {
                if (Character.isHighSurrogate(text.charAt(end - 1))
                        && Character.isLowSurrogate(text.charAt(end))) end--;
                for (int i = end - 1; i >= start + limit / 2; i--) {
                    char c = text.charAt(i);
                    if ("。！？；.!?;\n".indexOf(c) >= 0 || Character.isWhitespace(c)) {
                        end = i + 1;
                        break;
                    }
                }
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }
}
