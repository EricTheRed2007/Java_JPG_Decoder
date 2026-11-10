final class JpegIdct {
    private static final double[][] BASIS = new double[8][8];

    static {
        for (int x = 0; x < 8; x++) {
            for (int frequency = 0; frequency < 8; frequency++) {
                double scale = frequency == 0 ? 1.0 / Math.sqrt(2.0) : 1.0;
                BASIS[x][frequency] = scale
                        * Math.cos(((2 * x + 1) * frequency * Math.PI) / 16.0);
            }
        }
    }

    private JpegIdct() {
    }

    static void transform(int[] coefficients, int[] quantization, int[][] output,
                          int outputX, int outputY) {
        double[][] intermediate = new double[8][8];
        for (int v = 0; v < 8; v++) {
            for (int x = 0; x < 8; x++) {
                double sum = 0;
                for (int u = 0; u < 8; u++) {
                    sum += coefficients[v * 8 + u] * quantization[v * 8 + u] * BASIS[x][u];
                }
                intermediate[v][x] = sum * 0.5;
            }
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                double sum = 0;
                for (int v = 0; v < 8; v++) {
                    sum += intermediate[v][x] * BASIS[y][v];
                }
                int sample = (int) Math.round(sum * 0.5 + 128.0);
                output[outputX + x][outputY + y] = Math.max(0, Math.min(255, sample));
            }
        }
    }
}
