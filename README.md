# Modular JPEG decoder

This Java 8 program reads JPEG bytes directly and decodes Huffman-coded baseline
and progressive JPEG images. It parses JPEG markers and quantization/Huffman
tables, decodes the entropy-coded scans, performs an inverse DCT, converts
grayscale/RGB/YCbCr samples to pixels, and displays the result in a Swing
window.

## Build and run

With GNU Make on Windows, build all Java sources into `build/` and launch the
viewer with:

```text
make
java -cp build JpegViewer ex.jpg
```

Run `make clean` to remove the generated build directory. To compile manually,
create `build/` and pass `-d build` to `javac`.

Pass any JPEG path as the single argument to `JpegViewer`.

## Modules

- `JpegDecoder` parses markers/scans, reconstructs coefficients, and renders
  pixels.
- `HuffmanTable` builds and decodes canonical JPEG Huffman tables.
- `EntropyBitReader` reads stuffed entropy bytes and restart markers.
- `JpegIdct` performs the inverse discrete cosine transform.
- `JpegComponent` stores one component's coefficient blocks and samples.
- `JpegViewer` displays the decoded `BufferedImage`.

### Class dependencies

The arrows show which project classes use other classes. The project classes
are in the default package, so their relationships do not appear as explicit
`import` statements.

```mermaid
flowchart LR
    Viewer[JpegViewer] --> Decoder[JpegDecoder]
    Decoder --> Huffman[HuffmanTable]
    Decoder --> Reader[EntropyBitReader]
    Decoder --> Component[JpegComponent]
    Decoder --> IDCT[JpegIdct]
    Huffman --> Reader
    Viewer -. "AWT / Swing" .-> UI["Java UI libraries"]
    Decoder -. "I/O, BufferedImage" .-> JDK["Java standard libraries"]
```

### Runtime calling order

```mermaid
sequenceDiagram
    actor User
    participant Viewer as JpegViewer
    participant Decoder as JpegDecoder
    participant Reader as EntropyBitReader
    participant Huffman as HuffmanTable
    participant Component as JpegComponent
    participant IDCT as JpegIdct
    participant Swing as Swing UI

    User->>Viewer: launch with JPEG path
    Viewer->>Decoder: decode(path)
    Decoder->>Decoder: read file and parse markers
    Decoder->>Decoder: parse tables and frame
    loop Each scan
        Decoder->>Reader: create entropy reader
        loop Each MCU / block
            Decoder->>Huffman: decode(reader)
            Huffman->>Reader: readBit()
            Decoder->>Component: store coefficients
        end
    end
    Decoder->>IDCT: transform each block
    IDCT->>Component: write samples
    Decoder->>Decoder: convert samples to BufferedImage pixels
    Decoder-->>Viewer: return image
    Viewer->>Swing: schedule window on EDT
    Swing-->>User: display decoded image
```

This implementation supports 8-bit Huffman-coded SOF0/SOF1/SOF2 images with
one or three components, including progressive scans and restart intervals.
Arithmetic-coded, lossless, 12-bit, and four-component CMYK/YCCK JPEGs are not
supported.
