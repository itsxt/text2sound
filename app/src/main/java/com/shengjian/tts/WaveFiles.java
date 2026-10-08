package com.shengjian.tts;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Merges actual RIFF chunks; never assumes a fixed 44-byte WAV header. */
public final class WaveFiles {
    private WaveFiles() {}

    private static final class Wave {
        File file;
        byte[] format;
        long offset;
        long length;
    }

    private static long uint32(RandomAccessFile input) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
    }

    private static String tag(RandomAccessFile input) throws IOException {
        byte[] data = new byte[4];
        input.readFully(data);
        return new String(data, StandardCharsets.US_ASCII);
    }

    private static Wave inspect(File file) throws IOException {
        Wave wave = new Wave();
        wave.file = file;
        boolean dataFound = false;
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 12 || !"RIFF".equals(tag(input)))
                throw new IOException("语音引擎未返回 WAV 格式");
            long end = uint32(input) + 8;
            if (!"WAVE".equals(tag(input)) || end > input.length() || end < 12)
                throw new IOException("音频文件不完整");
            while (input.getFilePointer() + 8 <= end) {
                String type = tag(input);
                long size = uint32(input);
                long offset = input.getFilePointer();
                long next = offset + size + (size & 1);
                if (next > end) throw new IOException("音频数据长度错误");
                if ("fmt ".equals(type)) {
                    if (size < 16 || size > 4096) throw new IOException("不支持的音频参数");
                    wave.format = new byte[(int) size];
                    input.readFully(wave.format);
                    int format = (wave.format[0] & 255) | ((wave.format[1] & 255) << 8);
                    if (format != 1 && format != 3) throw new IOException("仅支持 PCM 或浮点 WAV 音频");
                } else if ("data".equals(type)) {
                    if (dataFound) throw new IOException("不支持多个 data 音频块");
                    dataFound = true;
                    wave.offset = offset;
                    wave.length = size;
                }
                input.seek(next);
            }
            if (wave.format == null || !dataFound || wave.length == 0)
                throw new IOException("语音引擎返回了空音频");
            int align = (wave.format[12] & 255) | ((wave.format[13] & 255) << 8);
            if (align == 0 || wave.length % align != 0) throw new IOException("音频采样数据不完整");
        }
        return wave;
    }

    public static void merge(List<File> sources, File destination) throws IOException {
        if (sources.isEmpty()) throw new IOException("没有可导出的音频");
        List<Wave> waves = new ArrayList<>();
        byte[] format = null;
        long size = 0;
        for (File source : sources) {
            Wave wave = inspect(source);
            if (format != null && !Arrays.equals(format, wave.format))
                throw new IOException("语音引擎返回了不同的音频格式，请更换音色后重试");
            format = wave.format;
            size += wave.length;
            waves.add(wave);
        }
        long riffSize = 4 + 8 + format.length + (format.length & 1) + 8 + size + (size & 1);
        if (riffSize > 0xffffffffL) throw new IOException("音频文件过大");
        try (RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
            output.setLength(0);
            output.writeBytes("RIFF");
            output.writeInt(Integer.reverseBytes((int) riffSize));
            output.writeBytes("WAVEfmt ");
            output.writeInt(Integer.reverseBytes(format.length));
            output.write(format);
            if ((format.length & 1) == 1) output.write(0);
            output.writeBytes("data");
            output.writeInt(Integer.reverseBytes((int) size));
            byte[] buffer = new byte[16384];
            for (Wave wave : waves) {
                try (RandomAccessFile input = new RandomAccessFile(wave.file, "r")) {
                    input.seek(wave.offset);
                    long remaining = wave.length;
                    while (remaining > 0) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) throw new IOException("音频读取中断");
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            }
            if ((size & 1) == 1) output.write(0);
        } catch (IOException error) {
            destination.delete();
            throw error;
        }
    }
}
