package com.shengjian.tts;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Dependency-free regression checks for long text and exported audio integrity. */
public final class CoreTests {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private interface IoAction { void run() throws IOException; }
    private static void rejects(IoAction action, String message) throws IOException {
        boolean rejected = false;
        try { action.run(); } catch (IOException expected) { rejected = true; }
        check(rejected, message);
    }

    public static void main(String[] args) throws Exception {
        check(TextChunks.split("", 3000).isEmpty(), "Empty input");
        check(TextChunks.split("你好。世界！", 4).equals(Arrays.asList("你好。", "世界！")), "Sentence boundary");
        check(TextChunks.split("ab😀cd", 3).equals(Arrays.asList("ab", "😀c", "d")), "Surrogate boundary");
        Random random = new Random(42);
        String[] alphabet = {"a", "中", "文", "😀", "\n", "。", " ", "！", "e\u0301"};
        for (int round = 0; round < 500; round++) {
            StringBuilder input = new StringBuilder();
            for (int i = 0; i < round; i++) input.append(alphabet[random.nextInt(alphabet.length)]);
            int limit = 2 + random.nextInt(60);
            List<String> parts = TextChunks.split(input.toString(), limit);
            check(String.join("", parts).equals(input.toString()), "No lost or duplicated text");
            for (String part : parts) {
                check(!part.isEmpty() && part.length() <= limit, "Every chunk fits the engine budget");
                check(!Character.isHighSurrogate(part.charAt(part.length() - 1)), "No split emoji tail");
                check(!Character.isLowSurrogate(part.charAt(0)), "No split emoji head");
            }
        }
        File directory = Files.createTempDirectory("shengjian-wave-test-").toFile();
        try {
            File a = new File(directory, "a.wav");
            File b = new File(directory, "b.wav");
            File output = new File(directory, "merged.wav");
            writeWave(a, 16000, new byte[]{1, 2, 3, 4}, true);
            writeWave(b, 16000, new byte[]{5, 6}, false);
            WaveFiles.merge(Arrays.asList(a, b), output);
            byte[] bytes = Files.readAllBytes(output.toPath());
            check(bytes.length == 50, "Merged header + PCM length");
            check(Arrays.equals(Arrays.copyOfRange(bytes, 44, 50), new byte[]{1, 2, 3, 4, 5, 6}), "PCM kept in order, metadata excluded");
            try (RandomAccessFile file = new RandomAccessFile(output, "r")) {
                file.seek(4);
                check(Integer.reverseBytes(file.readInt()) == 42, "RIFF size updated");
                file.seek(40);
                check(Integer.reverseBytes(file.readInt()) == 6, "Data size updated");
            }
            writeWave(b, 22050, new byte[]{1, 2}, false);
            rejects(() -> WaveFiles.merge(Arrays.asList(a, b), output), "Mixed sample rates rejected");
            rejects(() -> WaveFiles.merge(Collections.emptyList(), output), "Empty export rejected");
            writeWave(b, 16000, new byte[0], false);
            rejects(() -> WaveFiles.merge(Collections.singletonList(b), output), "Empty audio rejected");
            writeWave(b, 16000, new byte[]{1, 2}, false);
            try (RandomAccessFile broken = new RandomAccessFile(b, "rw")) { broken.setLength(broken.length() - 1); }
            rejects(() -> WaveFiles.merge(Collections.singletonList(b), output), "Truncated audio rejected");
            Files.write(b.toPath(), new byte[]{1, 2, 3, 4});
            rejects(() -> WaveFiles.merge(Collections.singletonList(b), output), "Non-WAV output rejected");
        } finally {
            File[] entries = directory.listFiles();
            if (entries != null) for (File entry : entries) Files.deleteIfExists(entry.toPath());
            Files.deleteIfExists(directory.toPath());
        }
        System.out.println("PASS: " + assertions + " assertions (text chunking and WAV export)");
    }

    private static void writeWave(File file, int sampleRate, byte[] pcm, boolean metadata) throws IOException {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(0);
            output.writeBytes("RIFF");
            little(output, 36 + pcm.length + (metadata ? 12 : 0));
            output.writeBytes("WAVE");
            if (metadata) {
                output.writeBytes("JUNK"); little(output, 3);
                output.write(new byte[]{9, 8, 7, 0}); // Odd-sized metadata with RIFF padding.
            }
            output.writeBytes("fmt "); little(output, 16);
            output.writeShort(Short.reverseBytes((short) 1));
            output.writeShort(Short.reverseBytes((short) 1));
            little(output, sampleRate); little(output, sampleRate * 2);
            output.writeShort(Short.reverseBytes((short) 2));
            output.writeShort(Short.reverseBytes((short) 16));
            output.writeBytes("data"); little(output, pcm.length); output.write(pcm);
        }
    }

    private static void little(RandomAccessFile output, int value) throws IOException {
        output.writeInt(Integer.reverseBytes(value));
    }
}
