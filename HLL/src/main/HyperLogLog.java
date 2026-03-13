package main;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

/**
 * HyperLogLog - Cardinality Estimation (Küme Büyüklügü Tahmini) Algoritmasi
 *
 * Teorik Hata Analizi:
 * -------------------
 * Standart hata: sigma ≈ 1.04 / sqrt(m)
 * burada m = kova (register) sayisi = 2^b
 *
 * b=4  → m=16,    sigma ≈ 26.0%
 * b=8  → m=256,   sigma ≈  6.5%
 * b=10 → m=1024,  sigma ≈  3.25%
 * b=12 → m=4096,  sigma ≈  1.625%
 * b=16 → m=65536, sigma ≈  0.4%
 *
 * Bellek Kullanimi:
 * ----------------
 * Her register 1 byte (6 bit yeterli).
 * b=12 icin: 4096 byte = 4 KB (milyarlarca eleman sayabilir!)
 */
public class HyperLogLog {

    // -------------------------------------------------------------------------
    // Alanlar
    // -------------------------------------------------------------------------
    private final int b;            // Register indeksi icin kullanilacak bit sayisi
    private final int m;            // Kova (bucket/register) sayisi = 2^b
    private final byte[] registers; // Her kovadaki maksimum ardisik sifir sayisi
    private final double alphaMM;   // Düzeltme sabiti * m^2

    /**
     * @param b Register bit genisligi (4 <= b <= 16)
     *          Küçük b → az bellek, yüksek hata
     *          Büyük b → fazla bellek, düsük hata
     */
    public HyperLogLog(int b) {
        if (b < 4 || b > 16) {
            throw new IllegalArgumentException(
                    "b parametresi 4 ile 16 arasinda olmalidir. Verilen: " + b);
        }
        this.b = b;
        this.m = 1 << b;              // m = 2^b
        this.registers = new byte[m]; // Baslangicta tüm registerlar 0
        this.alphaMM = computeAlpha(m) * m * m;
    }

    // -------------------------------------------------------------------------
    // 1) HASH FONKSIYONU (SHA-256 tabanli, yüksek kaliteli dagilim)
    // -------------------------------------------------------------------------

    /**
     * Verilen string degeri icin 64-bit hash üretir.
     * SHA-256 ile yüksek kaliteli, düzgün dagitimli hash saglar.
     * Ilk 8 byte'i 64-bit long'a dönüstürür (big-endian).
     */
    private long hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (hashBytes[i] & 0xFFL);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 bulunamadi", e);
        }
    }

    // -------------------------------------------------------------------------
    // 2) BUCKETING MEKANIZMASI
    // -------------------------------------------------------------------------

    /**
     * 64-bit hash degerinden kova (bucket) indeksini cikarir.
     * En yüksek b bit, kova indeksini belirler.
     *
     * Örnek (b=4):
     *   hash = 1010 0011 1100 ...
     *          ^^^^
     *          kova indeksi = 10
     */
    private int getBucketIndex(long hash) {
        return (int) (hash >>> (64 - b)) & (m - 1);
    }

    /**
     * Hash degerinin geri kalan (64-b) bitinde ardisik sifir sayisini bulur.
     * HLL'nin temel gözlemi: en uzun ardisik sifir serisi kardinaliteyi tahmin eder.
     *
     * Örnek (b=4, kalan 60 bit):
     *   0001 1010 ... → 3 ardisik sifir, ardindan 1 → rho = 4
     *
     * @return rho: ilk '1' bitinin pozisyonu (1'den baslar)
     */
    private int countLeadingZeros(long hash) {
        long remaining = hash << b;
        if (remaining == 0) {
            return 64 - b + 1;
        }
        return Long.numberOfLeadingZeros(remaining) + 1;
    }

    // -------------------------------------------------------------------------
    // 3) ELEMAN EKLEME
    // -------------------------------------------------------------------------

    /**
     * Yeni bir elemani HLL yapisina ekler.
     * Zaman: O(1)  |  Ek bellek: O(1)
     */
    public void add(String element) {
        long h = hash(element);
        int bucketIndex = getBucketIndex(h);
        int rho = countLeadingZeros(h);
        if (rho > registers[bucketIndex]) {
            registers[bucketIndex] = (byte) rho;
        }
    }

    // -------------------------------------------------------------------------
    // 4) KARDINALITE TAHMINI (Harmonik Ortalama + Düzeltme Faktorleri)
    // -------------------------------------------------------------------------

    /**
     * Mevcut elemanlarin sayisini tahmin eder.
     *
     * Adimlar:
     *   1. Ham tahmin (raw estimate):
     *      E = alphaM * m^2 / toplam(2^(-M[j]))
     *      Harmonik ortalama kullanilir; asiri büyük degerlerin etkisini azaltir.
     *
     *   2. Küçük aralik düzeltmesi (Small Range Correction - Linear Counting):
     *      Sifir register varsa: E* = m * ln(m / V)
     *
     *   3. Büyük aralik düzeltmesi (Large Range Correction):
     *      2^32'ye yaklasinca: E* = -2^32 * ln(1 - E/2^32)
     */
    public long estimate() {
        double harmonicSum = 0.0;
        int zeroRegisters = 0;

        for (int j = 0; j < m; j++) {
            harmonicSum += Math.pow(2, -registers[j]);
            if (registers[j] == 0) {
                zeroRegisters++;
            }
        }

        // Ham tahmin
        double rawEstimate = alphaMM / harmonicSum;

        // Küçük aralik düzeltmesi
        if (rawEstimate <= 2.5 * m && zeroRegisters > 0) {
            double linearCount = m * Math.log((double) m / zeroRegisters);
            return Math.round(linearCount);
        }

        // Büyük aralik düzeltmesi
        double twoTo32 = Math.pow(2, 32);
        if (rawEstimate > twoTo32 / 30.0) {
            double corrected = -twoTo32 * Math.log(1.0 - rawEstimate / twoTo32);
            return Math.round(corrected);
        }

        return Math.round(rawEstimate);
    }

    // -------------------------------------------------------------------------
    // 5) BIRLESTIRME (MERGE)
    // -------------------------------------------------------------------------

    /**
     * Iki HLL yapisini birlestirir (UNION operasyonu).
     *
     * merge(HLL_A, HLL_B) → |A ∪ B| tahmini, veri kaybi olmaz.
     * Her register icin maksimum deger alinir.
     *
     * Kullanim senaryosu:
     *   - Dagitik sistemde her node kendi HLL'ini tutar.
     *   - Merkezi toplayici HLL'leri merge eder.
     *   - Sonuç: tüm veri tek yerde islenmiş gibi dogru tahmin.
     *
     * Zaman: O(m)  |  Bellek: O(m)
     *
     * @param other Birlestirilecek diger HLL (ayni b parametresiyle olusturulmali)
     * @return Yeni birlestirilmis HLL nesnesi
     */
    public HyperLogLog merge(HyperLogLog other) {
        if (this.b != other.b) {
            throw new IllegalArgumentException(
                    String.format("Birlestirme icin b parametreleri esit olmali. Bu: %d, Diger: %d",
                            this.b, other.b));
        }
        HyperLogLog merged = new HyperLogLog(this.b);
        for (int j = 0; j < m; j++) {
            merged.registers[j] = (byte) Math.max(this.registers[j], other.registers[j]);
        }
        return merged;
    }

    // -------------------------------------------------------------------------
    // 6) YARDIMCI METODLAR
    // -------------------------------------------------------------------------

    /**
     * Alpha sabitini hesaplar (Flajolet et al. 2007 - Tablo 1).
     * Harmonik ortalama tabanli tahmindeki sistematik hatay düzeltir.
     */
    private double computeAlpha(int m) {
        switch (m) {
            case 16:  return 0.673;
            case 32:  return 0.697;
            case 64:  return 0.709;
            default:  return 0.7213 / (1.0 + 1.079 / m);
        }
    }

    /** Teorik standart hata: sigma = 1.04 / sqrt(m) */
    public double getTheoreticalStandardError() {
        return 1.04 / Math.sqrt(m);
    }

    public int getB() { return b; }
    public int getM() { return m; }

    @Override
    public String toString() {
        return String.format("HyperLogLog{b=%d, m=%d, tahmin=%d, teorikHata=+/-%.2f%%}",
                b, m, estimate(), getTheoreticalStandardError() * 100);
    }

    // =========================================================================
    // MAIN - Demo ve Teorik Analiz
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=".repeat(68));
        System.out.println("  HyperLogLog - Olasiliksal Kardinalite Tahmini");
        System.out.println("  Algoritma Analizi ve Tasarimi Dersi Odevi");
        System.out.println("=".repeat(68));

        demo1_TemelKullanimVeDogruluk();
        demo2_KovaSayisiHataEtkisi();
        demo3_BirlesmeOzelligi();
        demo4_BellekVerimliligi();
        demo5_TeoriAnaliziOzet();

        System.out.println("\n" + "=".repeat(68));
        System.out.println("  Tüm demolar basariyla tamamlandi!");
        System.out.println("=".repeat(68));
    }

    // -------------------------------------------------------------------------
    // DEMO 1: Temel Kullanim ve Dogruluk Testi
    // -------------------------------------------------------------------------
    static void demo1_TemelKullanimVeDogruluk() {
        printSection("DEMO 1: Temel Kullanim ve Dogruluk Testi");

        int[] testSizes = {100, 1_000, 10_000, 100_000, 1_000_000};
        int b = 12; // m = 4096 register, sigma ≈ 1.625%

        System.out.printf("  %-15s %-15s %-15s %-12s %-12s%n",
                "Gercek Sayi", "HLL Tahmini", "Mutlak Hata", "% Hata", "Teorik sigma");
        System.out.println("  " + "-".repeat(66));

        for (int realCount : testSizes) {
            HyperLogLog hll = new HyperLogLog(b);
            for (int i = 0; i < realCount; i++) {
                hll.add("eleman_" + i + "_salt_" + (i * 2654435761L));
            }
            long estimated = hll.estimate();
            double error = Math.abs(estimated - realCount) / (double) realCount * 100;
            double theoreticalError = hll.getTheoreticalStandardError() * 100;

            System.out.printf("  %-15s %-15s %-15s %-11.2f%% %-12.3f%%%n",
                    formatNumber(realCount),
                    formatNumber((int) estimated),
                    formatNumber((int) Math.abs(estimated - realCount)),
                    error,
                    theoreticalError
            );
        }
        System.out.println("\n  Gozlem: HLL yüz milyonlarca eleman icin bile sabit bellek kullanir!");
    }

    // -------------------------------------------------------------------------
    // DEMO 2: Kova Sayisinin (m) Hata Üzerine Etkisi
    // -------------------------------------------------------------------------
    static void demo2_KovaSayisiHataEtkisi() {
        printSection("DEMO 2: Kova Sayisinin (m) Hata Üzerine Etkisi");

        System.out.println("  Teorik Formül: sigma = 1.04 / sqrt(m)");
        System.out.println("  m arttikca hata azalir, bellek kullanimi artar\n");

        System.out.printf("  %-6s %-10s %-14s %-15s %-12s%n",
                "b", "m (kova)", "Teorik Hata", "Gozlenen Hata", "Bellek");
        System.out.println("  " + "-".repeat(60));

        int realCount = 50_000;
        int[] bValues = {4, 6, 8, 10, 12, 14, 16};

        for (int b : bValues) {
            HyperLogLog hll = new HyperLogLog(b);
            for (int i = 0; i < realCount; i++) {
                hll.add("test_" + i);
            }
            long estimated = hll.estimate();
            int m = hll.getM();
            double theoreticalError = hll.getTheoreticalStandardError() * 100;
            double observedError = Math.abs(estimated - realCount) / (double) realCount * 100;

            System.out.printf("  %-6d %-10s %-14s %-15s %-12s%n",
                    b,
                    formatNumber(m),
                    String.format("+/-%.2f%%", theoreticalError),
                    String.format("+/-%.2f%%", observedError),
                    formatNumber(m) + " B"
            );
        }

        System.out.println("\n  Matematiksel Kural:");
        System.out.println("  m'yi 4 katina cikarmak (b'yi 2 artirmak) hatay yariya indirir.");
        System.out.println("  Örnek: b=8 → sigma=6.5%  |  b=10 → sigma=3.25%  (tam yarisi)");
    }

    // -------------------------------------------------------------------------
    // DEMO 3: Birlestirme (Merge) Ozelligi
    // -------------------------------------------------------------------------
    static void demo3_BirlesmeOzelligi() {
        printSection("DEMO 3: Birlestirme (Merge) Ozelligi");

        System.out.println("  Senaryo: 3 farkli sunucuda log analizi");
        System.out.println("  Her sunucu kendi HLL'ini bagimsiz olusturuyor...\n");

        int b = 12;
        // Sunucu A: kullanici_0 .. kullanici_39999         (40.000 tekil)
        // Sunucu B: kullanici_30000 .. kullanici_64999     (35.000 tekil, 10.000 A ile ortak)
        // Sunucu C: kullanici_100000 .. kullanici_124999   (25.000 tekil, hicbiriyle ortak degil)
        // Gercek birlesim: 40.000 + 25.000 + 25.000 = 90.000
        int realUnion = 90_000;

        HyperLogLog hllA = new HyperLogLog(b);
        HyperLogLog hllB = new HyperLogLog(b);
        HyperLogLog hllC = new HyperLogLog(b);

        for (int i = 0;       i < 40_000;  i++) hllA.add("kullanici_" + i);
        for (int i = 30_000;  i < 65_000;  i++) hllB.add("kullanici_" + i);
        for (int i = 100_000; i < 125_000; i++) hllC.add("kullanici_" + i);

        HyperLogLog merged = hllA.merge(hllB).merge(hllC);

        System.out.printf("  %-35s %s%n", "Sunucu A (tekil kullanici):", formatNumber((int) hllA.estimate()));
        System.out.printf("  %-35s %s%n", "Sunucu B (tekil kullanici):", formatNumber((int) hllB.estimate()));
        System.out.printf("  %-35s %s%n", "Sunucu C (tekil kullanici):", formatNumber((int) hllC.estimate()));
        System.out.println("  " + "-".repeat(50));
        System.out.printf("  %-35s %s%n", "Naif toplam (YANLIS!):",
                formatNumber((int)(hllA.estimate() + hllB.estimate() + hllC.estimate())));
        System.out.printf("  %-35s %s%n", "HLL Merge sonucu:", formatNumber((int) merged.estimate()));
        System.out.printf("  %-35s %s%n", "Gercek tekil kullanici:", formatNumber(realUnion));
        System.out.printf("  %-35s %.2f%%%n", "Hata:",
                Math.abs(merged.estimate() - realUnion) / (double) realUnion * 100);

        System.out.println("\n  Merge islemi: O(m) zaman, O(m) bellek");
        System.out.println("  Veri paylasimi gerekmez, sadece HLL yapilari birlestirilir!");
    }

    // -------------------------------------------------------------------------
    // DEMO 4: Bellek Verimliligi
    // -------------------------------------------------------------------------
    static void demo4_BellekVerimliligi() {
        printSection("DEMO 4: Bellek Verimliligi Karsilastirmasi");

        System.out.println("  1 Milyon tekil kullaniciyi saymak icin ne kadar bellek gerekir?\n");

        long hashSetMemory = 1_000_000L * 82; // ~82 byte/eleman (String + HashMap entry)

        System.out.printf("  %-35s %s%n", "HashSet (kesin sayim):", formatBytes(hashSetMemory));
        System.out.println();

        for (int b : new int[]{10, 12, 14}) {
            int m = 1 << b;
            double error = 1.04 / Math.sqrt(m) * 100;
            System.out.printf("  %-35s %s  (hata: +/-%.2f%%)%n",
                    String.format("HLL (b=%d, m=%d):", b, m),
                    formatBytes(m),
                    error);
        }

        long hll12Memory = 1 << 12;
        System.out.printf("%n  Sikistirma orani (b=12): %.0f:1%n", (double) hashSetMemory / hll12Memory);
        System.out.println("  HLL, HashSet'e kiyasla ~20.000 kat daha az bellek kullanir!");
    }

    // -------------------------------------------------------------------------
    // DEMO 5: Teorik Analiz Ozeti
    // -------------------------------------------------------------------------
    static void demo5_TeoriAnaliziOzet() {
        printSection("DEMO 5: Teorik Analiz - Kova Sayisi ve Hata Iliskisi");

        System.out.println("  KANIT: m'nin 4 katina cikarilmasi hatay yariya indirir");
        System.out.println("  " + "-".repeat(56));
        System.out.println("  sigma(m)  = 1.04 / sqrt(m)");
        System.out.println("  sigma(4m) = 1.04 / sqrt(4m) = 1.04 / (2 * sqrt(m)) = sigma(m) / 2");
        System.out.println("  Sonuc: m → 4m  =>  sigma → sigma/2");
        System.out.println();
        System.out.println("  UZAY-DOGRULUK DENKLEMI: m = (1.04 / sigma)^2");
        System.out.println("  sigma = 5%  → m =   433  → b =  9  (512 kova)");
        System.out.println("  sigma = 2%  → m =  2704  → b = 12  (4096 kova)");
        System.out.println("  sigma = 1%  → m = 10816  → b = 14  (16384 kova)");
        System.out.println();
        System.out.println("  DUZELTME FAKTORLERI:");
        System.out.println("  Kucuk aralik (E <= 2.5m)  : E* = m * ln(m/V)");
        System.out.println("  Normal aralik              : E* = alphaM * m^2 / toplam(2^-M[j])");
        System.out.println("  Büyük aralik (E > 2^32/30) : E* = -2^32 * ln(1 - E/2^32)");
        System.out.println();

        System.out.println("  Pratik Dogrulama: 10 deney ortalamasiyla gozlenen sigma");
        System.out.println("  " + "-".repeat(60));
        System.out.printf("  %-6s %-8s %-14s %-14s %-10s%n",
                "b", "m", "Teorik sigma", "Gozlenen sigma", "Uyum?");
        System.out.println("  " + "-".repeat(60));

        int[] bTests = {6, 8, 10, 12};
        int realCount = 100_000;
        int trials = 10;
        Random rand = new Random(42);

        for (int b : bTests) {
            double[] errors = new double[trials];
            for (int t = 0; t < trials; t++) {
                HyperLogLog hll = new HyperLogLog(b);
                long salt = rand.nextLong();
                for (int i = 0; i < realCount; i++) {
                    hll.add("item_" + i + "_" + salt);
                }
                errors[t] = (hll.estimate() - realCount) / (double) realCount;
            }
            double mean = Arrays.stream(errors).average().orElse(0);
            double variance = Arrays.stream(errors)
                    .map(e -> (e - mean) * (e - mean))
                    .average().orElse(0);
            double stdDev = Math.sqrt(variance) * 100;
            double theoretical = 1.04 / Math.sqrt(1 << b) * 100;
            boolean good = Math.abs(stdDev - theoretical) < theoretical * 0.5;

            System.out.printf("  %-6d %-8s %-14s %-14s %s%n",
                    b,
                    formatNumber(1 << b),
                    String.format("+/-%.2f%%", theoretical),
                    String.format("+/-%.2f%%", stdDev),
                    good ? "Uyumlu" : "Sapma var"
            );
        }
    }

    // -------------------------------------------------------------------------
    // Yardimci metodlar
    // -------------------------------------------------------------------------

    static void printSection(String title) {
        System.out.println("\n>> " + title);
        System.out.println("   " + "-".repeat(64));
    }

    static String formatNumber(int n) {
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    static String formatBytes(long bytes) {
        if (bytes >= 1_048_576) return String.format("%.2f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024)     return String.format("%.2f KB", bytes / 1_024.0);
        return bytes + " B";
    }
}