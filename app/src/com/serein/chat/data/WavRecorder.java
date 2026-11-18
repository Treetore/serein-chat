package com.serein.chat.data;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 录一段 16kHz / 16bit / 单声道 PCM WAV（ASR 接口要求 16 位 PCM WAV），
 * 最长 120 秒（接口上限），到达上限自动停止。
 */
public final class WavRecorder {

    public static final int SAMPLE_RATE = 16000;
    private static final int MAX_SECONDS = 120;

    private AudioRecord record;
    private Thread worker;
    private File outFile;
    private volatile boolean recording;
    private int totalBytes;

    public boolean isRecording() {
        return recording;
    }

    /** 开始录音；失败返回 false。 */
    public boolean start(File out) {
        if (recording) return true;
        outFile = out;
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SAMPLE_RATE;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
        } catch (Exception e) {
            record = null;
        }
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            release();
            return false;
        }
        totalBytes = 0;
        recording = true;
        record.startRecording();
        worker = new Thread(() -> {
            byte[] buf = new byte[4096];
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                while (recording) {
                    int n = record.read(buf, 0, buf.length);
                    if (n > 0) {
                        fos.write(buf, 0, n);
                        totalBytes += n;
                        if (totalBytes >= SAMPLE_RATE * 2 * MAX_SECONDS) break;
                    }
                }
                fos.flush();
            } catch (Exception ignored) {
            }
        }, "wav-recorder");
        worker.start();
        return true;
    }

    /** 停止录音并补齐 WAV 头；返回文件（无有效音频时返回 null）。 */
    public File stop() {
        if (!recording) return null;
        recording = false;
        try {
            if (record != null) record.stop();
        } catch (Exception ignored) {
        }
        try {
            if (worker != null) worker.join(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        release();
        if (outFile == null || !outFile.exists() || totalBytes < SAMPLE_RATE / 4) {
            return null;
        }
        try {
            writeHeader();
        } catch (IOException e) {
            return null;
        }
        return outFile;
    }

    private void release() {
        try {
            if (record != null) record.release();
        } catch (Exception ignored) {
        }
        record = null;
    }

    /** 在文件头写入标准 44 字节 WAV header。 */
    private void writeHeader() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.seek(0);
            raf.writeBytes("RIFF");
            raf.writeInt(littleEndian(36 + totalBytes));
            raf.writeBytes("WAVEfmt ");
            raf.writeInt(littleEndian(16));
            raf.writeShort(littleEndian((short) 1));
            raf.writeShort(littleEndian((short) 1));
            raf.writeInt(littleEndian(SAMPLE_RATE));
            raf.writeInt(littleEndian(SAMPLE_RATE * 2));
            raf.writeShort(littleEndian((short) 2));
            raf.writeShort(littleEndian((short) 16));
            raf.writeBytes("data");
            raf.writeInt(littleEndian(totalBytes));
        }
    }

    private static int littleEndian(int v) {
        return Integer.reverseBytes(v);
    }

    private static short littleEndian(short v) {
        return Short.reverseBytes(v);
    }
}
