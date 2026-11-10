import java.io.IOException;

final class HuffmanTable {
    private final int[] minCode = new int[17];
    private final int[] maxCode = new int[18];
    private final int[] valuePointer = new int[17];
    private final int[] symbols;

    HuffmanTable(int[] codeCounts, int[] values) throws IOException {
        symbols = values;
        int code = 0;
        int valueIndex = 0;
        for (int length = 1; length <= 16; length++) {
            int count = codeCounts[length - 1];
            if (count == 0) {
                minCode[length] = -1;
                maxCode[length] = -1;
            } else {
                minCode[length] = code;
                maxCode[length] = code + count - 1;
                valuePointer[length] = valueIndex;
                valueIndex += count;
                code += count;
            }
            if (code > (1 << length)) {
                throw new IOException("Invalid oversubscribed JPEG Huffman table");
            }
            code <<= 1;
        }
        maxCode[17] = Integer.MAX_VALUE;
        if (valueIndex != symbols.length) {
            throw new IOException("Invalid JPEG Huffman symbol count");
        }
    }

    int decode(EntropyBitReader reader) throws IOException {
        int code = 0;
        for (int length = 1; length <= 16; length++) {
            code = (code << 1) | reader.readBit();
            if (minCode[length] >= 0 && code <= maxCode[length]) {
                return symbols[valuePointer[length] + code - minCode[length]];
            }
        }
        throw new IOException("Invalid JPEG Huffman code");
    }
}
