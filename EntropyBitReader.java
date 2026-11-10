import java.io.IOException;

final class EntropyBitReader {
    private final byte[] data;
    private int position;
    private int currentByte;
    private int bitsRemaining;

    EntropyBitReader(byte[] data, int position) {
        this.data = data;
        this.position = position;
    }

    int readBit() throws IOException {
        if (bitsRemaining == 0) {
            currentByte = readEntropyByte();
            bitsRemaining = 8;
        }
        bitsRemaining--;
        return (currentByte >>> bitsRemaining) & 1;
    }

    int readBits(int count) throws IOException {
        int result = 0;
        for (int i = 0; i < count; i++) {
            result = (result << 1) | readBit();
        }
        return result;
    }

    int receiveAndExtend(int bitCount) throws IOException {
        if (bitCount == 0) {
            return 0;
        }
        int value = readBits(bitCount);
        int threshold = 1 << (bitCount - 1);
        return value < threshold ? value - ((1 << bitCount) - 1) : value;
    }

    void consumeRestartMarker(int expectedMarker) throws IOException {
        bitsRemaining = 0;
        if (position >= data.length || (data[position++] & 0xff) != 0xff) {
            throw new IOException("Expected JPEG restart marker");
        }
        while (position < data.length && (data[position] & 0xff) == 0xff) {
            position++;
        }
        if (position >= data.length || (data[position++] & 0xff) != expectedMarker) {
            throw new IOException("Unexpected JPEG restart marker");
        }
    }

    void alignToByte() {
        bitsRemaining = 0;
    }

    int getPosition() {
        return position;
    }

    private int readEntropyByte() throws IOException {
        if (position >= data.length) {
            throw new IOException("Unexpected end of JPEG entropy data");
        }
        int value = data[position++] & 0xff;
        if (value != 0xff) {
            return value;
        }
        if (position >= data.length) {
            throw new IOException("Truncated JPEG entropy data");
        }
        int next = data[position++] & 0xff;
        while (next == 0xff) {
            if (position >= data.length) {
                throw new IOException("Truncated JPEG entropy data");
            }
            next = data[position++] & 0xff;
        }
        if (next != 0) {
            throw new IOException(String.format("Unexpected marker FF%02X inside entropy data", next));
        }
        return 0xff;
    }
}
