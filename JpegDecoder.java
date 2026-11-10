import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class JpegDecoder {
    private static final int SOF0 = 0xc0;
    private static final int SOF1 = 0xc1;
    private static final int SOF2 = 0xc2;
    private static final int DHT = 0xc4;
    private static final int DQT = 0xdb;
    private static final int DRI = 0xdd;
    private static final int SOS = 0xda;
    private static final int EOI = 0xd9;
    private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10,
            17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34,
            27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36,
            29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46,
            53, 60, 61, 54, 47, 55, 62, 63
    };

    private final int[][] quantizationTables = new int[4][];
    private final HuffmanTable[][] huffmanTables = new HuffmanTable[2][4];
    private final JpegComponent[] components = new JpegComponent[4];
    private final int[] componentLookup = new int[256];
    private int width;
    private int height;
    private int maxHorizontalSampling;
    private int maxVerticalSampling;
    private int restartInterval;
    private int componentCount;
    private boolean progressive;
    private boolean frameRead;

    private JpegDecoder() {
        for (int i = 0; i < componentLookup.length; i++) {
            componentLookup[i] = -1;
        }
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        return new JpegDecoder().decodeImage(data);
    }

    public static BufferedImage decode(String fileName) throws IOException {
        return decode(Files.readAllBytes(Paths.get(fileName)));
    }

    private BufferedImage decodeImage(byte[] data) throws IOException {
        if (data.length < 4 || u8(data, 0) != 0xff || u8(data, 1) != 0xd8) {
            throw new IOException("Not a JPEG image (missing SOI marker)");
        }
        int position = 2;
        while (position < data.length) {
            while (position < data.length && u8(data, position) != 0xff) {
                position++;
            }
            if (position >= data.length) {
                break;
            }
            while (position < data.length && u8(data, position) == 0xff) {
                position++;
            }
            if (position >= data.length) {
                break;
            }
            int marker = u8(data, position++);
            if (marker == EOI) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                continue;
            }
            if (position + 2 > data.length) {
                throw new IOException("Truncated JPEG marker segment");
            }
            int segmentLength = u16(data, position);
            if (segmentLength < 2 || position + segmentLength > data.length) {
                throw new IOException("Invalid JPEG marker segment length");
            }
            int payload = position + 2;
            int end = position + segmentLength;
            switch (marker) {
                case DQT:
                    parseQuantizationTables(data, payload, end);
                    break;
                case DHT:
                    parseHuffmanTables(data, payload, end);
                    break;
                case DRI:
                    if (segmentLength != 4) {
                        throw new IOException("Invalid JPEG restart interval segment");
                    }
                    restartInterval = u16(data, payload);
                    break;
                case SOF0:
                case SOF1:
                case SOF2:
                    parseFrame(data, payload, end, marker);
                    break;
                case SOS:
                    if (!frameRead) {
                        throw new IOException("JPEG scan encountered before frame header");
                    }
                    position = decodeScan(data, payload, end);
                    continue;
                default:
                    break;
            }
            position = end;
        }
        if (!frameRead) {
            throw new IOException("JPEG frame header was not found");
        }
        return renderImage();
    }

    private void parseQuantizationTables(byte[] data, int position, int end) throws IOException {
        while (position < end) {
            int descriptor = u8(data, position++);
            int precision = descriptor >>> 4;
            int tableId = descriptor & 0x0f;
            if (tableId >= quantizationTables.length || precision > 1) {
                throw new IOException("Unsupported JPEG quantization table");
            }
            int bytesPerValue = precision == 0 ? 1 : 2;
            if (position + 64 * bytesPerValue > end) {
                throw new IOException("Truncated JPEG quantization table");
            }
            int[] table = new int[64];
            for (int i = 0; i < 64; i++) {
                int value = precision == 0 ? u8(data, position++) : u16(data, position);
                if (precision == 1) {
                    position += 2;
                }
                table[ZIGZAG[i]] = value;
            }
            quantizationTables[tableId] = table;
        }
    }

    private void parseHuffmanTables(byte[] data, int position, int end) throws IOException {
        while (position < end) {
            int descriptor = u8(data, position++);
            int tableClass = descriptor >>> 4;
            int tableId = descriptor & 0x0f;
            if (tableClass > 1 || tableId >= 4 || position + 16 > end) {
                throw new IOException("Invalid JPEG Huffman table header");
            }
            int[] counts = new int[16];
            int symbolCount = 0;
            for (int i = 0; i < 16; i++) {
                counts[i] = u8(data, position++);
                symbolCount += counts[i];
            }
            if (position + symbolCount > end) {
                throw new IOException("Truncated JPEG Huffman table");
            }
            int[] symbols = new int[symbolCount];
            for (int i = 0; i < symbolCount; i++) {
                symbols[i] = u8(data, position++);
            }
            huffmanTables[tableClass][tableId] = new HuffmanTable(counts, symbols);
        }
    }

    private void parseFrame(byte[] data, int position, int end, int marker) throws IOException {
        if (frameRead || position + 6 > end) {
            throw new IOException("Invalid or duplicate JPEG frame header");
        }
        int precision = u8(data, position++);
        height = u16(data, position);
        position += 2;
        width = u16(data, position);
        position += 2;
        componentCount = u8(data, position++);
        if (precision != 8 || width == 0 || height == 0
                || componentCount < 1 || componentCount > 4
                || position + componentCount * 3 != end) {
            throw new IOException("Unsupported or malformed JPEG frame");
        }
        if (marker != SOF0 && marker != SOF1 && marker != SOF2) {
            throw new IOException("Unsupported JPEG frame type");
        }
        progressive = marker == SOF2;
        int[] ids = new int[componentCount];
        int[] horizontal = new int[componentCount];
        int[] vertical = new int[componentCount];
        int[] quantization = new int[componentCount];
        for (int i = 0; i < componentCount; i++) {
            ids[i] = u8(data, position++);
            int sampling = u8(data, position++);
            horizontal[i] = sampling >>> 4;
            vertical[i] = sampling & 0x0f;
            quantization[i] = u8(data, position++);
            if (horizontal[i] == 0 || vertical[i] == 0
                    || horizontal[i] > 4 || vertical[i] > 4
                    || quantization[i] >= quantizationTables.length
                    || componentLookup[ids[i]] != -1) {
                throw new IOException("Invalid JPEG component parameters");
            }
            maxHorizontalSampling = Math.max(maxHorizontalSampling, horizontal[i]);
            maxVerticalSampling = Math.max(maxVerticalSampling, vertical[i]);
        }
        int mcuColumns = ceilDiv(width, maxHorizontalSampling * 8);
        int mcuRows = ceilDiv(height, maxVerticalSampling * 8);
        for (int i = 0; i < componentCount; i++) {
            int paddedWidth = mcuColumns * horizontal[i];
            int paddedHeight = mcuRows * vertical[i];
            components[i] = new JpegComponent(ids[i], horizontal[i], vertical[i],
                    quantization[i], paddedWidth, paddedHeight);
            componentLookup[ids[i]] = i;
        }
        frameRead = true;
    }

    private int decodeScan(byte[] data, int position, int segmentEnd) throws IOException {
        int scanComponentCount = u8(data, position++);
        if (scanComponentCount < 1 || scanComponentCount > componentCount
                || position + scanComponentCount * 2 + 3 != segmentEnd) {
            throw new IOException("Malformed JPEG scan header");
        }
        JpegComponent[] scanComponents = new JpegComponent[scanComponentCount];
        int[] dcSelectors = new int[scanComponentCount];
        int[] acSelectors = new int[scanComponentCount];
        for (int i = 0; i < scanComponentCount; i++) {
            int componentId = u8(data, position++);
            int tableSelector = u8(data, position++);
            int componentIndex = componentLookup[componentId];
            if (componentIndex < 0) {
                throw new IOException("JPEG scan references an unknown component");
            }
            scanComponents[i] = components[componentIndex];
            dcSelectors[i] = tableSelector >>> 4;
            acSelectors[i] = tableSelector & 0x0f;
            if (dcSelectors[i] >= 4 || acSelectors[i] >= 4) {
                throw new IOException("Invalid JPEG scan Huffman table selector");
            }
        }
        int spectralStart = u8(data, position++);
        int spectralEnd = u8(data, position++);
        int approximation = u8(data, position++);
        int highBit = approximation >>> 4;
        int lowBit = approximation & 0x0f;
        if (progressive) {
            if (spectralStart > spectralEnd || spectralEnd > 63
                    || highBit > 13 || lowBit > 13
                    || (spectralStart == 0 && spectralEnd != 0)
                    || (spectralStart != 0 && scanComponentCount != 1)) {
                throw new IOException("Unsupported progressive JPEG scan parameters");
            }
        } else if (spectralStart != 0 || spectralEnd != 63 || approximation != 0) {
            throw new IOException("Unsupported sequential JPEG scan parameters");
        }

        EntropyBitReader reader = new EntropyBitReader(data, segmentEnd);
        int totalMcus = getMcuCount(scanComponents);
        int expectedRestart = 0;
        int eobRun = 0;
        for (JpegComponent component : scanComponents) {
            component.dcPredictor = 0;
        }
        for (int mcu = 0; mcu < totalMcus; mcu++) {
            if (restartInterval > 0 && mcu > 0 && mcu % restartInterval == 0) {
                reader.consumeRestartMarker(0xd0 + expectedRestart);
                expectedRestart = (expectedRestart + 1) & 7;
                for (JpegComponent component : scanComponents) {
                    component.dcPredictor = 0;
                }
                eobRun = 0;
            }
            if (isInterleaved(scanComponents)) {
                int mcuColumns = ceilDiv(width, maxHorizontalSampling * 8);
                int mcuX = mcu % mcuColumns;
                int mcuY = mcu / mcuColumns;
                for (int c = 0; c < scanComponentCount; c++) {
                    JpegComponent component = scanComponents[c];
                    for (int blockY = 0; blockY < component.verticalSampling; blockY++) {
                        for (int blockX = 0; blockX < component.horizontalSampling; blockX++) {
                            int x = mcuX * component.horizontalSampling + blockX;
                            int y = mcuY * component.verticalSampling + blockY;
                            eobRun = decodeBlock(reader, component, x, y,
                                    dcSelectors[c], acSelectors[c], spectralStart,
                                    spectralEnd, highBit, lowBit, eobRun);
                        }
                    }
                }
            } else {
                JpegComponent component = scanComponents[0];
                int blocksWide = ceilDiv(width * component.horizontalSampling,
                        maxHorizontalSampling * 8);
                int x = mcu % blocksWide;
                int y = mcu / blocksWide;
                eobRun = decodeBlock(reader, component, x, y,
                        dcSelectors[0], acSelectors[0], spectralStart,
                        spectralEnd, highBit, lowBit, eobRun);
            }
        }
        reader.alignToByte();
        return reader.getPosition();
    }

    private int getMcuCount(JpegComponent[] scanComponents) {
        if (isInterleaved(scanComponents)) {
            return ceilDiv(width, maxHorizontalSampling * 8)
                    * ceilDiv(height, maxVerticalSampling * 8);
        }
        JpegComponent component = scanComponents[0];
        return ceilDiv(width * component.horizontalSampling, maxHorizontalSampling * 8)
                * ceilDiv(height * component.verticalSampling, maxVerticalSampling * 8);
    }

    private boolean isInterleaved(JpegComponent[] scanComponents) {
        return scanComponents.length > 1;
    }

    private int decodeBlock(EntropyBitReader reader, JpegComponent component,
                            int blockX, int blockY, int dcSelector, int acSelector,
                            int spectralStart, int spectralEnd, int highBit, int lowBit,
                            int eobRun) throws IOException {
        int[] block = component.coefficients[component.blockIndex(blockX, blockY)];
        if (!progressive) {
            HuffmanTable dcTable = requireHuffmanTable(0, dcSelector);
            HuffmanTable acTable = requireHuffmanTable(1, acSelector);
            component.dcPredictor += reader.receiveAndExtend(dcTable.decode(reader));
            block[0] = component.dcPredictor;
            int k = 1;
            while (k < 64) {
                int symbol = acTable.decode(reader);
                int run = symbol >>> 4;
                int size = symbol & 0x0f;
                if (size == 0) {
                    if (run == 15) {
                        k += 16;
                        continue;
                    }
                    break;
                }
                k += run;
                if (k >= 64) {
                    throw new IOException("Invalid JPEG AC coefficient run");
                }
                block[ZIGZAG[k]] = reader.receiveAndExtend(size);
                k++;
            }
            return eobRun;
        }

        if (spectralStart == 0) {
            if (highBit == 0) {
                component.dcPredictor += reader.receiveAndExtend(
                        requireHuffmanTable(0, dcSelector).decode(reader));
                block[0] = component.dcPredictor << lowBit;
            } else if (reader.readBit() != 0) {
                int correction = 1 << lowBit;
                block[0] += block[0] >= 0 ? correction : -correction;
            }
            return eobRun;
        }

        HuffmanTable acTable = requireHuffmanTable(1, acSelector);
        if (highBit == 0) {
            if (eobRun > 0) {
                return eobRun - 1;
            }
            int k = spectralStart;
            while (k <= spectralEnd) {
                int symbol = acTable.decode(reader);
                int run = symbol >>> 4;
                int size = symbol & 0x0f;
                if (size == 0) {
                    if (run < 15) {
                        eobRun = (1 << run) + reader.readBits(run) - 1;
                        break;
                    }
                    k += 16;
                    continue;
                }
                k += run;
                if (k > spectralEnd) {
                    throw new IOException("Invalid progressive JPEG AC run");
                }
                block[ZIGZAG[k]] = reader.receiveAndExtend(size) << lowBit;
                k++;
            }
            return eobRun;
        }
        return refineAcBlock(reader, block, acTable, spectralStart, spectralEnd,
                lowBit, eobRun);
    }

    private int refineAcBlock(EntropyBitReader reader, int[] block, HuffmanTable acTable,
                              int spectralStart, int spectralEnd, int lowBit,
                              int eobRun) throws IOException {
        int correction = 1 << lowBit;
        int k = spectralStart;
        if (eobRun == 0) {
            while (k <= spectralEnd) {
                int symbol = acTable.decode(reader);
                int run = symbol >>> 4;
                int size = symbol & 0x0f;
                int newCoefficient = 0;
                if (size == 0) {
                    if (run < 15) {
                        eobRun = (1 << run) + reader.readBits(run);
                        break;
                    }
                    run = 16;
                } else {
                    if (size != 1) {
                        throw new IOException("Invalid progressive JPEG refinement symbol");
                    }
                    newCoefficient = reader.readBit() == 0 ? -correction : correction;
                }
                while (k <= spectralEnd) {
                    int index = ZIGZAG[k];
                    if (block[index] != 0) {
                        refineCoefficient(reader, block, index, correction);
                    } else if (run == 0) {
                        break;
                    } else {
                        run--;
                    }
                    k++;
                }
                if (newCoefficient != 0) {
                    if (k > spectralEnd) {
                        throw new IOException("Progressive JPEG refinement exceeded spectral band");
                    }
                    block[ZIGZAG[k++]] = newCoefficient;
                }
            }
        }
        if (eobRun > 0) {
            while (k <= spectralEnd) {
                int index = ZIGZAG[k++];
                if (block[index] != 0) {
                    refineCoefficient(reader, block, index, correction);
                }
            }
            eobRun--;
        }
        return eobRun;
    }

    private void refineCoefficient(EntropyBitReader reader, int[] block, int index,
                                   int correction) throws IOException {
        if (reader.readBit() != 0 && (Math.abs(block[index]) & correction) == 0) {
            block[index] += block[index] > 0 ? correction : -correction;
        }
    }

    private HuffmanTable requireHuffmanTable(int tableClass, int tableId) throws IOException {
        HuffmanTable table = huffmanTables[tableClass][tableId];
        if (table == null) {
            throw new IOException("JPEG scan uses an undefined Huffman table");
        }
        return table;
    }

    private BufferedImage renderImage() throws IOException {
        for (int i = 0; i < componentCount; i++) {
            JpegComponent component = components[i];
            int[] quantization = quantizationTables[component.quantizationTableId];
            if (quantization == null) {
                throw new IOException("JPEG component uses an undefined quantization table");
            }
            for (int blockY = 0; blockY < component.blocksHigh; blockY++) {
                for (int blockX = 0; blockX < component.blocksWide; blockX++) {
                    int[] coefficients = component.coefficients[component.blockIndex(blockX, blockY)];
                    JpegIdct.transform(coefficients, quantization, component.samples,
                            blockX * 8, blockY * 8);
                }
            }
        }
        if (componentCount != 1 && componentCount != 3) {
            throw new IOException("Only grayscale and three-component JPEG images are supported");
        }
        boolean rgb = componentCount == 3
                && components[0].id == 'R' && components[1].id == 'G' && components[2].id == 'B';
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int first = sample(components[0], x, y);
                int red;
                int green;
                int blue;
                if (componentCount == 1) {
                    red = green = blue = first;
                } else if (rgb) {
                    red = first;
                    green = sample(components[1], x, y);
                    blue = sample(components[2], x, y);
                } else {
                    double cb = sample(components[1], x, y) - 128.0;
                    double cr = sample(components[2], x, y) - 128.0;
                    red = clamp((int) Math.round(first + 1.402 * cr));
                    green = clamp((int) Math.round(first - 0.344136 * cb - 0.714136 * cr));
                    blue = clamp((int) Math.round(first + 1.772 * cb));
                }
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    private int sample(JpegComponent component, int x, int y) {
        int sampleX = x * component.horizontalSampling / maxHorizontalSampling;
        int sampleY = y * component.verticalSampling / maxVerticalSampling;
        return component.samples[sampleX][sampleY];
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int u8(byte[] data, int index) throws IOException {
        if (index < 0 || index >= data.length) {
            throw new IOException("Truncated JPEG data");
        }
        return data[index] & 0xff;
    }

    private static int u16(byte[] data, int index) throws IOException {
        return (u8(data, index) << 8) | u8(data, index + 1);
    }
}
