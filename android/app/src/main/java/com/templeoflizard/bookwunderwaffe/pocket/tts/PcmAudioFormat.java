package com.templeoflizard.bookwunderwaffe.pocket.tts;

import java.util.Objects;

/** Describes interleaved PCM frames as stored in the WAV data chunk. */
public final class PcmAudioFormat {
    public static final int WAV_FORMAT_PCM = 1;
    public static final int WAV_FORMAT_IEEE_FLOAT = 3;

    private final int sampleRateHz;
    private final int channelCount;
    private final int bitsPerSample;
    private final int wavFormatCode;

    public static PcmAudioFormat integerPcm(int sampleRateHz, int channelCount,
            int bitsPerSample) {
        return new PcmAudioFormat(sampleRateHz, channelCount, bitsPerSample, WAV_FORMAT_PCM);
    }

    public static PcmAudioFormat floatingPointPcm(int sampleRateHz, int channelCount,
            int bitsPerSample) {
        return new PcmAudioFormat(sampleRateHz, channelCount, bitsPerSample,
                WAV_FORMAT_IEEE_FLOAT);
    }

    public PcmAudioFormat(int sampleRateHz, int channelCount, int bitsPerSample,
            int wavFormatCode) {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive");
        }
        if (channelCount <= 0 || channelCount > 32) {
            throw new IllegalArgumentException("channelCount must be in 1..32");
        }
        if (bitsPerSample <= 0 || bitsPerSample % 8 != 0) {
            throw new IllegalArgumentException("bitsPerSample must be a positive whole byte count");
        }
        if (wavFormatCode != WAV_FORMAT_PCM && wavFormatCode != WAV_FORMAT_IEEE_FLOAT) {
            throw new IllegalArgumentException("Unsupported WAV format code: " + wavFormatCode);
        }
        this.sampleRateHz = sampleRateHz;
        this.channelCount = channelCount;
        this.bitsPerSample = bitsPerSample;
        this.wavFormatCode = wavFormatCode;
    }

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public int getBitsPerSample() {
        return bitsPerSample;
    }

    public int getWavFormatCode() {
        return wavFormatCode;
    }

    public int getFrameSizeBytes() {
        return channelCount * (bitsPerSample / 8);
    }

    public long getByteRate() {
        return (long) sampleRateHz * getFrameSizeBytes();
    }

    public long durationMillisFor(long pcmBytes) {
        if (pcmBytes <= 0) {
            return 0;
        }
        return (pcmBytes * 1_000L) / getByteRate();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PcmAudioFormat)) {
            return false;
        }
        PcmAudioFormat that = (PcmAudioFormat) other;
        return sampleRateHz == that.sampleRateHz
                && channelCount == that.channelCount
                && bitsPerSample == that.bitsPerSample
                && wavFormatCode == that.wavFormatCode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRateHz, channelCount, bitsPerSample, wavFormatCode);
    }

    @Override
    public String toString() {
        return "PcmAudioFormat{" + sampleRateHz + " Hz, channels=" + channelCount
                + ", bits=" + bitsPerSample + ", wavCode=" + wavFormatCode + '}';
    }
}
