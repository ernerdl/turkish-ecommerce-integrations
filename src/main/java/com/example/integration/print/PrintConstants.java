package com.example.integration.print;

/**
 * Yazdırma servisleri için sabit değerler.
 * Tüm etiket boyutları, yazıcı kodları ve varsayılan değerler burada tanımlı.
 */
public final class PrintConstants {

    private PrintConstants() {
        // Utility class
    }

    // ========== DÖNÜŞÜM SABİTLERİ ==========
    /**
     * Milimetre -> PDF Point dönüşüm katsayısı
     * 1 inch = 72 points, 1 inch = 25.4 mm
     * 1 mm = 72/25.4 = 2.83465 points
     */
    public static final float MM_TO_POINTS = 2.83465f;

    // ========== ETİKET BOYUTLARI (mm cinsinden) ==========

    // Argox IX-240 Pro - 80x100mm Ürün Etiketi
    public static final float ETIKET_80x100_WIDTH_MM = 80f;
    public static final float ETIKET_80x100_HEIGHT_MM = 100f;
    public static final float ETIKET_80x100_WIDTH_PT = ETIKET_80x100_WIDTH_MM * MM_TO_POINTS;   // ~227pt
    public static final float ETIKET_80x100_HEIGHT_PT = ETIKET_80x100_HEIGHT_MM * MM_TO_POINTS; // ~283pt

    // XPrinter 470B - 100x150mm Kargo Etiketi
    public static final float ETIKET_100x150_WIDTH_MM = 100f;
    public static final float ETIKET_100x150_HEIGHT_MM = 150f;
    public static final float ETIKET_100x150_WIDTH_PT = ETIKET_100x150_WIDTH_MM * MM_TO_POINTS;  // ~283pt
    public static final float ETIKET_100x150_HEIGHT_PT = ETIKET_100x150_HEIGHT_MM * MM_TO_POINTS; // ~425pt

    // A4 Fatura
    public static final float A4_WIDTH_MM = 210f;
    public static final float A4_HEIGHT_MM = 297f;
    public static final float A4_WIDTH_PT = A4_WIDTH_MM * MM_TO_POINTS;   // ~595pt
    public static final float A4_HEIGHT_PT = A4_HEIGHT_MM * MM_TO_POINTS;  // ~842pt

    // ========== BARKOD BOYUTLARI (piksel) ==========
    public static final int BARCODE_WIDTH_DEFAULT = 500;
    public static final int BARCODE_HEIGHT_DEFAULT = 80;
    public static final int BARCODE_HEIGHT_SMALL = 50;
    public static final int QR_SIZE_DEFAULT = 200;
    public static final int QR_SIZE_SMALL = 150;
    public static final int QR_SIZE_LARGE = 300;

    // ========== YAZICI KODLARI (DB'deki kod alanı) ==========
    public static final String YAZICI_KOD_TERMAL_80x100 = "TERMAL_80x100";
    public static final String YAZICI_KOD_TERMAL_100x150 = "TERMAL_100x150";
    public static final String YAZICI_KOD_A4_WIFI = "A4_WIFI";

    // ========== YAZICI TİPLERİ ==========
    public static final String TIP_TERMAL = "TERMAL";
    public static final String TIP_TERMAL_TRANSFER = "TERMAL_TRANSFER";
    public static final String TIP_LAZER = "LAZER";
    public static final String TIP_INKJET = "INKJET";

    // ========== BAĞLANTI TİPLERİ ==========
    public static final String BAGLANTI_RAW_SOCKET = "RAW_SOCKET";
    public static final String BAGLANTI_CUPS = "CUPS";
    public static final String BAGLANTI_IPP = "IPP";
    public static final String BAGLANTI_SMB = "SMB";

    // ========== RAW SOCKET VARSAYILANLARI ==========
    public static final int RAW_SOCKET_DEFAULT_PORT = 9100;
    public static final int RAW_SOCKET_TIMEOUT_MS = 5000;
    public static final int RAW_SOCKET_CONNECT_TIMEOUT_MS = 3000;

    // ========== FONT YOLLARI ==========
    public static final String FONT_OPENSANS = "fonts/OpenSans-Regular.ttf";
    public static final String FONT_OPENSANS_BOLD = "fonts/OpenSans-Bold.ttf";
    public static final String FONT_DEJAVU = "fonts/DejaVuSans.ttf";

    // ========== VARSAYILAN FONT BOYUTLARI ==========
    public static final float FONT_SIZE_TITLE = 16f;
    public static final float FONT_SIZE_HEADER = 14f;
    public static final float FONT_SIZE_BODY = 12f;
    public static final float FONT_SIZE_SMALL = 10f;
    public static final float FONT_SIZE_TINY = 8f;

    // ========== PDF MARGIN (pt) ==========
    public static final float MARGIN_DEFAULT = 10f;
    public static final float MARGIN_SMALL = 5f;
    public static final float MARGIN_LARGE = 20f;

    // ========== BARKOD TİPLERİ ==========
    public enum BarcodeType {
        CODE128,
        QR_CODE,
        EAN13,
        EAN8,
        CODE39
    }

    // ========== ETİKET TİPLERİ ==========
    public enum EtiketTipi {
        URUN_80x100,      // Argox - Ürün etiketi
        KARGO_100x150,    // XPrinter - Kargo etiketi
        FATURA_A4         // WiFi yazıcı - Fatura
    }

    /**
     * Etiket tipine göre genişlik (pt)
     */
    public static float getWidth(EtiketTipi tip) {
        return switch (tip) {
            case URUN_80x100 -> ETIKET_80x100_WIDTH_PT;
            case KARGO_100x150 -> ETIKET_100x150_WIDTH_PT;
            case FATURA_A4 -> A4_WIDTH_PT;
        };
    }

    /**
     * Etiket tipine göre yükseklik (pt)
     */
    public static float getHeight(EtiketTipi tip) {
        return switch (tip) {
            case URUN_80x100 -> ETIKET_80x100_HEIGHT_PT;
            case KARGO_100x150 -> ETIKET_100x150_HEIGHT_PT;
            case FATURA_A4 -> A4_HEIGHT_PT;
        };
    }

    /**
     * Etiket tipine göre varsayılan yazıcı kodu
     */
    public static String getDefaultYaziciKod(EtiketTipi tip) {
        return switch (tip) {
            case URUN_80x100 -> YAZICI_KOD_TERMAL_80x100;
            case KARGO_100x150 -> YAZICI_KOD_TERMAL_100x150;
            case FATURA_A4 -> YAZICI_KOD_A4_WIFI;
        };
    }

    /**
     * mm'yi pt'ye çevir
     */
    public static float mmToPoints(float mm) {
        return mm * MM_TO_POINTS;
    }

    /**
     * pt'yi mm'ye çevir
     */
    public static float pointsToMm(float pt) {
        return pt / MM_TO_POINTS;
    }
}
