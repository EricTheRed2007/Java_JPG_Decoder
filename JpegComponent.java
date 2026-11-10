final class JpegComponent {
    final int id;
    final int horizontalSampling;
    final int verticalSampling;
    final int quantizationTableId;
    final int blocksWide;
    final int blocksHigh;
    final int[][] coefficients;
    final int[][] samples;
    int dcPredictor;

    JpegComponent(int id, int horizontalSampling, int verticalSampling,
                  int quantizationTableId, int blocksWide, int blocksHigh) {
        this.id = id;
        this.horizontalSampling = horizontalSampling;
        this.verticalSampling = verticalSampling;
        this.quantizationTableId = quantizationTableId;
        this.blocksWide = blocksWide;
        this.blocksHigh = blocksHigh;
        this.coefficients = new int[blocksWide * blocksHigh][64];
        this.samples = new int[blocksWide * 8][blocksHigh * 8];
    }

    int blockIndex(int x, int y) {
        return y * blocksWide + x;
    }
}
