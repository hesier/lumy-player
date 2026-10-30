package androidx.media3.decoder.ffmpeg;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Exercises the actual packaged JNI using generated, non-media-server fixtures. */
@UnstableApi
public final class TrueHdDecoderTest {
    private static final int SAMPLE_RATE = 48000;
    private static final int SAMPLES_PER_AU = 40;
    private static final int RESET_AU = 48;

    public void runAll(Context context) throws Exception {
        require(FfmpegLibrary.isAvailable(), "FFmpeg library unavailable");
        for (String mime : new String[] {MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC,
                MimeTypes.AUDIO_MLAW, MimeTypes.AUDIO_ALAW, MimeTypes.AUDIO_MPEG,
                MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3,
                MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_TRUEHD}) {
            require(FfmpegLibrary.supportsFormat(mime), "Missing decoder: " + mime);
        }
        for (int channels : new int[] {1, 2, 6}) {
            List<byte[]> units = readUnits(context, channels);
            for (int group : new int[] {1, 16}) {
                decode(units, new Configuration(channels, group, false));
                decode(units, new Configuration(channels, group, true));
            }
        }
        decode(readUnits(context, 2), new Configuration(2, 16, true).withFloatOutput());
        // An absent container count must remain safe rather than creating a negative layout.
        FfmpegAudioDecoder unknown = decoder(Format.NO_VALUE, false);
        unknown.release();
    }

    private static FfmpegAudioDecoder decoder(int channels, boolean floating) throws Exception {
        Format format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
                .setChannelCount(channels).setSampleRate(SAMPLE_RATE).build();
        return new FfmpegAudioDecoder(format, 4, 4, 65536, floating);
    }

    private static void decode(List<byte[]> units, Configuration config) throws Exception {
        FfmpegAudioDecoder decoder = decoder(config.channels, config.floating);
        DecoderInputBuffer input = decoder.createInputBuffer();
        SimpleDecoderOutputBuffer output = decoder.createOutputBuffer();
        long bytes = 0;
        boolean nonzero = false;
        try {
            for (int index = 0; index < units.size(); index += config.group) {
                input.clear();
                output.clear();
                int end = Math.min(index + config.group, units.size());
                int size = 0;
                for (int i = index; i < end; i++) size += units.get(i).length;
                input.ensureSpaceForWrite(size);
                for (int i = index; i < end; i++) input.data.put(units.get(i));
                input.timeUs = (long) index * SAMPLES_PER_AU * C.MICROS_PER_SECOND / SAMPLE_RATE;
                input.flip();
                // Reset before any output and after output, as initial seeks and later seeks do.
                boolean reset = (index == 0 && config.initialReset) || index == RESET_AU;
                FfmpegDecoderException error = decoder.decode(input, output, reset);
                require(error == null, config + ": " + error);
                require(!output.shouldBeSkipped, config + ": no PCM at AU " + index);
                int expected = (end - index) * SAMPLES_PER_AU * config.channels * config.bytesPerSample();
                require(output.data.remaining() == expected, config + ": incorrect PCM size");
                require(output.timeUs == input.timeUs, config + ": incorrect output timestamp");
                bytes += output.data.remaining();
                while (output.data.hasRemaining()) nonzero |= output.data.get() != 0;
            }
            require(bytes == (long) units.size() * SAMPLES_PER_AU * config.channels * config.bytesPerSample(),
                    config + ": missing decoded samples");
            require(nonzero, config + ": PCM is silent");
            require(decoder.getChannelCount() == config.channels, config + ": incorrect channels");
            require(decoder.getSampleRate() == SAMPLE_RATE, config + ": incorrect sample rate");
        } finally {
            // Buffers are decoded synchronously; the decoder's background queue stays idle.
            decoder.release();
        }
    }

    private static List<byte[]> readUnits(Context context, int channels) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = context.getAssets().open("truehd/" + channels + "ch.thd")) {
            byte[] block = new byte[4096];
            int count;
            while ((count = input.read(block)) != -1) bytes.write(block, 0, count);
        }
        byte[] data = bytes.toByteArray();
        List<byte[]> units = new ArrayList<>();
        for (int position = 0; position < data.length;) {
            require(position + 2 <= data.length, "Truncated AU header");
            int size = (((data[position] & 15) << 8) | (data[position + 1] & 255)) * 2;
            require(size >= 4 && position + size <= data.length, "Invalid AU length");
            byte[] unit = new byte[size];
            System.arraycopy(data, position, unit, 0, size);
            units.add(unit);
            position += size;
        }
        require(units.size() == 96, "Unexpected fixture duration");
        return units;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Configuration {
        final int channels;
        final int group;
        final boolean initialReset;
        boolean floating;

        Configuration(int channels, int group, boolean initialReset) {
            this.channels = channels;
            this.group = group;
            this.initialReset = initialReset;
        }

        Configuration withFloatOutput() { floating = true; return this; }

        int bytesPerSample() { return floating ? 4 : 2; }

        @Override
        public String toString() {
            return channels + "ch/group=" + group + "/initialReset=" + initialReset + "/float=" + floating;
        }
    }
}
