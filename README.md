# HyperLogLog — Olasılıksal Kardinalite Tahmini

Java ile sıfırdan geliştirilmiş HyperLogLog implementasyonu.

---

## Proje Hakkında

HyperLogLog (HLL), bir veri kümesindeki **tekil eleman sayısını** çok az bellek kullanarak tahmin eden olasılıksal bir veri yapısıdır.

| Yöntem | 1 Milyon Eleman | Hata |
|--------|----------------|------|
| HashSet (kesin) | ~78 MB | %0 |
| HyperLogLog (b=12) | **4 KB** | ~%1.6 |

> 20.000 kat daha az bellek, kabul edilebilir hata payı.

---

## Özellikler

- **Hash fonksiyonu** — SHA-256 tabanlı, yüksek kaliteli ve uniform dağılımlı
- **Bucketing** — Hash'in üst b biti ile kova (bucket) belirleme
- **Register yapısı** — Her kovada maksimum ardışık sıfır sayısı (rho) tutulur
- **Harmonik ortalama** — `E = αm × m² / Σ(2^-M[j])` formülü ile tahmin
- **Düzeltme faktörleri** — Küçük ve büyük aralıklar için ayrı düzeltme
- **Merge** — İki HLL yapısı veri kaybı olmadan birleştirilebilir

---

## Teorik Analiz

### Standart Hata Formülü
```
σ = 1.04 / √m
```

### Kova Sayısı ve Hata İlişkisi

| b | m (kova) | Teorik Hata | Bellek |
|---|----------|-------------|--------|
| 4 | 16 | ±26.00% | 16 B |
| 8 | 256 | ±6.50% | 256 B |
| 10 | 1.024 | ±3.25% | 1 KB |
| 12 | 4.096 | ±1.62% | 4 KB |
| 14 | 16.384 | ±0.81% | 16 KB |
| 16 | 65.536 | ±0.41% | 64 KB |

### Temel Kural
> m'yi 4 katına çıkarmak hatayı yarıya indirir.
> `σ(4m) = σ(m) / 2`

### Big-O Karmaşıklığı

| Operasyon | Zaman | Alan |
|-----------|-------|------|
| `add()` | O(1) | O(1) |
| `estimate()` | O(m) | O(1) |
| `merge()` | O(m) | O(m) |

---

## Kurulum ve Çalıştırma

### Gereksinimler
- Java 8+
- IntelliJ IDEA (veya herhangi bir Java IDE)

### Adımlar

1. Repoyu klonla
```bash
git clone https://github.com/kullanici-adin/hyperloglog.git
```

2. IntelliJ IDEA'da aç
   - `File → Open` ile proje klasörünü seç

3. `src/main/java/HyperLogLog.java` dosyasını aç

4. `main` metodunu çalıştır

---

## Demo Çıktısı

### Demo 1 — Doğruluk Testi
```
Gercek Sayi    HLL Tahmini    % Hata    Teorik σ
100            98             2.00%     ±1.625%
1.000          1.006          0.60%     ±1.625%
10.000         9.881          1.19%     ±1.625%
100.000        100.701        0.70%     ±1.625%
1.000.000      996.791        0.32%     ±1.625%
```

### Demo 3 — Merge Özelliği
```
Sunucu A (tekil kullanici):    40.5K
Sunucu B (tekil kullanici):    34.9K
Sunucu C (tekil kullanici):    24.1K
Naif toplam (YANLIS!):         99.5K
HLL Merge sonucu:              90.0K
Gercek tekil kullanici:        90.0K
Hata:                          %0.03
```

### Demo 4 — Bellek Karşılaştırması
```
HashSet (kesin sayim):         78.20 MB
HLL (b=10):                    1.00 KB   (hata: ±3.25%)
HLL (b=12):                    4.00 KB   (hata: ±1.62%)
HLL (b=14):                    16.00 KB  (hata: ±0.81%)
Sikistirma orani (b=12):       20.000:1
```

---

## Proje Yapısı
```
HLL/
└── src/
    └── main/
        └── java/
            └── HyperLogLog.java   ← Tek dosya, tüm kod burada
```

---

## Kullanım
```java
// HLL oluştur (b=12 → 4096 kova, ±%1.6 hata)
HyperLogLog hll = new HyperLogLog(12);

// Eleman ekle
hll.add("kullanici_1");
hll.add("kullanici_2");
hll.add("kullanici_3");

// Tahmin al
long tahmin = hll.estimate();
System.out.println("Tahmini tekil eleman: " + tahmin);

// İki HLL'yi birleştir
HyperLogLog hll2 = new HyperLogLog(12);
hll2.add("kullanici_4");

HyperLogLog merged = hll.merge(hll2);
System.out.println("Birlestirilmis tahmin: " + merged.estimate());
```

---

## Kaynaklar

- Flajolet, P. et al. (2007). *HyperLogLog: the analysis of a near-optimal cardinality estimation algorithm*
- [Redis HyperLogLog Dokümantasyonu](https://redis.io/docs/data-types/probabilistic/hyperloglogs/)

---

## Geliştirme Süreci

Bu proje **Claude Sonnet** (Anthropic) yapay zeka modeli yardımıyla geliştirilmiştir.
- Kod üretimi ve teorik analiz için [claude.ai](https://claude.ai) kullanıldı
- IDE olarak **IntelliJ IDEA** tercih edildi
- Geliştirme süreci boyunca iteratif düzeltmeler yapıldı
