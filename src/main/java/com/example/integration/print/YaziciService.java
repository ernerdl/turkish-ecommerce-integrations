/*
 * Adapted from a production Spring Boot ERP system.
 *
 * NOTE: This service depends on the Yazici entity and YaziciRepository.
 * You need to provide your own implementations matching the method signatures used here.
 *
 * The PNG-to-PPLB and PNG-to-TSPL conversions shell out to ImageMagick's
 * `convert` binary at /usr/bin/convert — adjust the path for your environment.
 *
 * Original author: Mehmet Eren Erdal <m.erenerdal@sevinclidoga.com>
 * License: MIT
 */
package com.example.integration.print;

import com.example.integration.entity.Yazici;
import com.example.integration.repository.YaziciRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;

/**
 * Yazıcı iletişim servisi.
 * RAW Socket, CUPS ve IPP protokolleri desteklenir.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class YaziciService {

    private final YaziciRepository yaziciRepository;

    /**
     * Yazıcıya veri gönderir (yazıcı kodu ile).
     *
     * @param yaziciKod Yazıcı kodu (örn: TERMAL_80x100)
     * @param data      Gönderilecek veri (PDF bytes)
     * @return CUPS job ID (CUPS için) veya null (RAW_SOCKET için)
     */
    public String gonder(String yaziciKod, byte[] data) {
        Yazici yazici = yaziciRepository.findByKodAndAktifTrue(yaziciKod)
                .orElseThrow(() -> new RuntimeException("Aktif yazıcı bulunamadı: " + yaziciKod));

        return gonder(yazici, data);
    }

    /**
     * Yazıcıya veri gönderir (yazıcı entity ile).
     *
     * @param yazici Yazıcı entity
     * @param data   Gönderilecek veri (PDF bytes)
     * @return CUPS job ID (CUPS için) veya null (diğer bağlantı tipleri için)
     */
    public String gonder(Yazici yazici, byte[] data) {
        if (yazici.getAdres() == null || yazici.getAdres().isEmpty()) {
            log.warn("Yazıcı adresi tanımlı değil [yazıcı={}]", yazici.getKod());
            throw new RuntimeException("Yazıcı adresi tanımlı değil: " + yazici.getKod());
        }

        return switch (yazici.getBaglantiTipi()) {
            case PrintConstants.BAGLANTI_RAW_SOCKET -> {
                gonderRawSocket(yazici, data);
                yield null; // RAW Socket job ID döndürmez
            }
            case PrintConstants.BAGLANTI_CUPS -> gonderCups(yazici, data);
            case PrintConstants.BAGLANTI_IPP -> {
                gonderIpp(yazici, data);
                yield null; // IPP job ID döndürmez (şimdilik)
            }
            default -> {
                log.error("Desteklenmeyen bağlantı tipi: {}", yazici.getBaglantiTipi());
                throw new RuntimeException("Desteklenmeyen bağlantı tipi: " + yazici.getBaglantiTipi());
            }
        };
    }

    /**
     * RAW Socket ile yazıcıya gönderir (port 9100).
     * Termal yazıcılar için yaygın yöntem.
     */
    private boolean gonderRawSocket(Yazici yazici, byte[] data) {
        String adres = yazici.getAdres();
        int port = yazici.getPort() != null ? yazici.getPort() : PrintConstants.RAW_SOCKET_DEFAULT_PORT;

        log.info("RAW Socket ile yazıcıya gönderiliyor [yazıcı={}, adres={}:{}]",
                yazici.getKod(), adres, port);

        try {
            // Termal yazıcı ise PNG'yi uygun formata çevir (CUPS gibi)
            byte[] printData = data;
            if (PrintConstants.TIP_TERMAL_TRANSFER.equals(yazici.getTip()) ||
                PrintConstants.TIP_TERMAL.equals(yazici.getTip())) {

                // PNG header kontrolü (89 50 4E 47)
                if (data.length > 4 && data[0] == (byte)0x89 && data[1] == (byte)0x50 &&
                    data[2] == (byte)0x4E && data[3] == (byte)0x47) {

                    if ("TERMAL_100x150".equals(yazici.getKod())) {
                        log.info("PNG tespit edildi, TSPL'ye çevriliyor (RAW Socket, XPrinter 100x150)...");
                        printData = convertPngToTspl(data);
                    } else {
                        log.info("PNG tespit edildi, PPLB'ye çevriliyor (RAW Socket, Argox 80x100)...");
                        printData = convertPngToPplb(data);
                    }
                }
            }

            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(adres, port),
                        PrintConstants.RAW_SOCKET_CONNECT_TIMEOUT_MS
                );
                socket.setSoTimeout(PrintConstants.RAW_SOCKET_TIMEOUT_MS);

                try (OutputStream out = socket.getOutputStream()) {
                    out.write(printData);
                    out.flush();
                }
            }

            log.info("Yazıcıya başarıyla gönderildi [yazıcı={}, boyut={} bytes]",
                    yazici.getKod(), printData.length);
            return true;

        } catch (IOException | InterruptedException e) {
            log.error("RAW Socket hatası [yazıcı={}, adres={}:{}]: {}",
                    yazici.getKod(), adres, port, e.getMessage());
            throw new RuntimeException("Yazıcıya bağlanılamadı: " + e.getMessage(), e);
        }
    }

    /**
     * CUPS üzerinden yazıcıya gönderir.
     * Linux/macOS sistemlerde yerel yazıcılar için.
     * Termal yazıcılar için PNG otomatik olarak yazıcı formatına çevrilir:
     * - TERMAL_80x100 (Argox) -> PPLB format
     * - TERMAL_100x150 (XPrinter) -> TSPL format
     *
     * @return CUPS job ID (örn: "Argox-Etiket-27")
     */
    private String gonderCups(Yazici yazici, byte[] data) {
        String yaziciAdi = yazici.getAdres(); // CUPS yazıcı adı

        log.info("CUPS ile yazıcıya gönderiliyor [yazıcı={}, cups_adi={}, boyut={}]",
                yazici.getKod(), yaziciAdi, data.length);

        try {
            byte[] printData = data;
            boolean isRaw = false;

            // Termal yazıcı ise PNG'yi uygun formata çevir
            if (PrintConstants.TIP_TERMAL_TRANSFER.equals(yazici.getTip()) ||
                PrintConstants.TIP_TERMAL.equals(yazici.getTip())) {

                // PNG header kontrolü (89 50 4E 47)
                if (data.length > 4 && data[0] == (byte)0x89 && data[1] == (byte)0x50 &&
                    data[2] == (byte)0x4E && data[3] == (byte)0x47) {

                    // Yazıcı koduna göre format seç
                    if ("TERMAL_100x150".equals(yazici.getKod())) {
                        log.info("PNG tespit edildi, TSPL'ye çevriliyor (XPrinter 100x150)...");
                        printData = convertPngToTspl(data);
                    } else {
                        log.info("PNG tespit edildi, PPLB'ye çevriliyor (Argox 80x100)...");
                        printData = convertPngToPplb(data);
                    }
                    isRaw = true;
                }
            }

            // CUPS lp komutu ile yazdır
            ProcessBuilder pb;
            if (isRaw) {
                pb = new ProcessBuilder("lp", "-d", yaziciAdi, "-o", "raw", "-");
            } else {
                pb = new ProcessBuilder("lp", "-d", yaziciAdi, "-");
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (OutputStream out = process.getOutputStream()) {
                out.write(printData);
                out.flush();
            }

            // CUPS çıktısını oku (job-id için)
            String output = new String(process.getInputStream().readAllBytes()).trim();

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                // CUPS çıktısı: "request id is Argox-Etiket-27 (1 file(s))"
                String jobId = parseJobIdFromOutput(output);
                log.info("CUPS ile başarıyla gönderildi [yazıcı={}, job_id={}, output={}]",
                        yazici.getKod(), jobId, output);
                return jobId;
            } else {
                log.error("CUPS hatası [yazıcı={}, exitCode={}, output={}]",
                        yazici.getKod(), exitCode, output);
                throw new RuntimeException("CUPS yazdırma hatası, exit code: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            log.error("CUPS hatası [yazıcı={}]: {}", yazici.getKod(), e.getMessage());
            throw new RuntimeException("CUPS yazdırma hatası: " + e.getMessage(), e);
        }
    }

    /**
     * Çoklu kopya ile yazıcıya gönderir.
     * TEK PNG oluşturulur, yazıcı komutu içinde adet belirtilir.
     * Bu sayede 139 etiket için 139 ayrı job yerine 1 job gönderilir.
     *
     * @param yaziciKod Yazıcı kodu (TERMAL_80x100 veya TERMAL_100x150)
     * @param data PNG verisi
     * @param adet Basılacak kopya sayısı
     * @return CUPS job ID
     */
    public String gonderCokluKopya(String yaziciKod, byte[] data, int adet) {
        Yazici yazici = yaziciRepository.findByKodAndAktifTrue(yaziciKod)
                .orElseThrow(() -> new RuntimeException("Aktif yazıcı bulunamadı: " + yaziciKod));

        if (yazici.getAdres() == null || yazici.getAdres().isEmpty()) {
            throw new RuntimeException("Yazıcı adresi tanımlı değil: " + yaziciKod);
        }

        log.info("Çoklu kopya gönderiliyor [yazıcı={}, adet={}, boyut={} bytes]", yaziciKod, adet, data.length);

        // Sadece CUPS destekleniyor şimdilik
        if (!PrintConstants.BAGLANTI_CUPS.equals(yazici.getBaglantiTipi())) {
            // CUPS değilse eski yönteme fallback (tek tek gönder)
            log.warn("Çoklu kopya sadece CUPS için destekleniyor, tek tek gönderim yapılacak");
            for (int i = 0; i < adet; i++) {
                gonder(yazici, data);
            }
            return null;
        }

        return gonderCupsCokluKopya(yazici, data, adet);
    }

    /**
     * CUPS üzerinden çoklu kopya gönderir.
     * PPLB/TSPL formatında P<adet> komutu kullanılır.
     */
    private String gonderCupsCokluKopya(Yazici yazici, byte[] data, int adet) {
        String yaziciAdi = yazici.getAdres();

        log.info("CUPS çoklu kopya gönderiliyor [yazıcı={}, cups_adi={}, adet={}, boyut={}]",
                yazici.getKod(), yaziciAdi, adet, data.length);

        try {
            byte[] printData = data;

            // Termal yazıcı ise PNG'yi uygun formata çevir (adet bilgisiyle)
            if (PrintConstants.TIP_TERMAL_TRANSFER.equals(yazici.getTip()) ||
                PrintConstants.TIP_TERMAL.equals(yazici.getTip())) {

                // PNG header kontrolü (89 50 4E 47)
                if (data.length > 4 && data[0] == (byte)0x89 && data[1] == (byte)0x50 &&
                    data[2] == (byte)0x4E && data[3] == (byte)0x47) {

                    if ("TERMAL_100x150".equals(yazici.getKod())) {
                        log.info("PNG tespit edildi, TSPL'ye çevriliyor (XPrinter 100x150, adet={})...", adet);
                        printData = convertPngToTsplCokluKopya(data, adet);
                    } else {
                        log.info("PNG tespit edildi, PPLB'ye çevriliyor (Argox 80x100, adet={})...", adet);
                        printData = convertPngToPplbCokluKopya(data, adet);
                    }
                }
            }

            // CUPS lp komutu ile yazdır
            ProcessBuilder pb = new ProcessBuilder("lp", "-d", yaziciAdi, "-o", "raw", "-");
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (OutputStream out = process.getOutputStream()) {
                out.write(printData);
                out.flush();
            }

            String output = new String(process.getInputStream().readAllBytes()).trim();

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                String jobId = parseJobIdFromOutput(output);
                log.info("CUPS çoklu kopya gönderildi [yazıcı={}, adet={}, job_id={}, output={}]",
                        yazici.getKod(), adet, jobId, output);
                return jobId;
            } else {
                log.error("CUPS hatası [yazıcı={}, exitCode={}, output={}]",
                        yazici.getKod(), exitCode, output);
                throw new RuntimeException("CUPS yazdırma hatası, exit code: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            log.error("CUPS hatası [yazıcı={}]: {}", yazici.getKod(), e.getMessage());
            throw new RuntimeException("CUPS yazdırma hatası: " + e.getMessage(), e);
        }
    }

    private byte[] convertPngToPplbCokluKopya(byte[] pngData, int adet) throws IOException, InterruptedException {
        java.nio.file.Path tempPng = java.nio.file.Files.createTempFile("etiket_", ".png");
        java.nio.file.Path tempBmp = java.nio.file.Files.createTempFile("etiket_", ".bmp");

        try {
            java.nio.file.Files.write(tempPng, pngData);

            ProcessBuilder convertPb = new ProcessBuilder(
                "/usr/bin/convert", tempPng.toString(),
                "-resize", "640x800!",
                "-flip",
                "-monochrome",
                "-colors", "2",
                "-depth", "1",
                "-type", "bilevel",
                "BMP3:" + tempBmp.toString()
            );
            convertPb.redirectErrorStream(true);
            Process convertProcess = convertPb.start();
            int convertExit = convertProcess.waitFor();

            if (convertExit != 0) {
                throw new RuntimeException("ImageMagick dönüşüm hatası, exit code: " + convertExit);
            }

            byte[] bmpData = java.nio.file.Files.readAllBytes(tempBmp);
            int pixelOffset = (bmpData[10] & 0xFF) |
                              ((bmpData[11] & 0xFF) << 8) |
                              ((bmpData[12] & 0xFF) << 16) |
                              ((bmpData[13] & 0xFF) << 24);
            byte[] rawData = new byte[bmpData.length - pixelOffset];
            System.arraycopy(bmpData, pixelOffset, rawData, 0, rawData.length);

            int bytesPerRow = 80;
            int height = rawData.length / bytesPerRow;

            log.info("PPLB çoklu: pixelOffset={}, rawSize={}, height={}", pixelOffset, rawData.length, height);

            java.io.ByteArrayOutputStream pplbOut = new java.io.ByteArrayOutputStream();
            pplbOut.write("N\r\n".getBytes());
            pplbOut.write("^W640\r\n".getBytes());
            pplbOut.write(String.format("^Q%d,32\r\n", height).getBytes());
            pplbOut.write("^M1\r\n".getBytes());
            pplbOut.write(String.format("GW0,0,%d,%d,", bytesPerRow, height).getBytes());
            pplbOut.write(rawData);
            pplbOut.write("\r\n".getBytes());
            pplbOut.write(String.format("P%d\r\n", adet).getBytes());

            log.info("PNG -> PPLB çoklu kopya tamamlandı [adet={}, pplb_boyut={}]", adet, pplbOut.size());
            return pplbOut.toByteArray();

        } finally {
            java.nio.file.Files.deleteIfExists(tempPng);
            java.nio.file.Files.deleteIfExists(tempBmp);
        }
    }

    /**
     * PNG görselini TSPL formatına çevirir - ÇOKLU KOPYA.
     * PRINT 1,1 yerine PRINT <adet>,1 komutu kullanılır.
     */
    private byte[] convertPngToTsplCokluKopya(byte[] pngData, int adet) throws IOException, InterruptedException {
        java.nio.file.Path tempPng = java.nio.file.Files.createTempFile("etiket_tspl_", ".png");
        java.nio.file.Path tempBmp = java.nio.file.Files.createTempFile("etiket_tspl_", ".bmp");

        try {
            java.nio.file.Files.write(tempPng, pngData);

            ProcessBuilder convertPb = new ProcessBuilder(
                "/usr/bin/convert", tempPng.toString(),
                "-resize", "800x1200!",
                "-flip",
                "-monochrome",
                "-colors", "2",
                "-depth", "1",
                "-type", "bilevel",
                "BMP3:" + tempBmp.toString()
            );
            convertPb.redirectErrorStream(true);
            Process convertProcess = convertPb.start();
            int convertExit = convertProcess.waitFor();

            if (convertExit != 0) {
                throw new RuntimeException("ImageMagick dönüşüm hatası (TSPL), exit code: " + convertExit);
            }

            byte[] bmpData = java.nio.file.Files.readAllBytes(tempBmp);
            int headerSize = 62;
            byte[] rawData = new byte[bmpData.length - headerSize];
            System.arraycopy(bmpData, headerSize, rawData, 0, rawData.length);

            int bytesPerRow = 100;
            int height = 1200;

            java.io.ByteArrayOutputStream tsplOut = new java.io.ByteArrayOutputStream();
            tsplOut.write("SIZE 100 mm, 150 mm\r\n".getBytes());
            tsplOut.write("GAP 3 mm, 0 mm\r\n".getBytes());
            tsplOut.write("DIRECTION 1\r\n".getBytes());
            tsplOut.write("CLS\r\n".getBytes());
            tsplOut.write(String.format("BITMAP 0,0,%d,%d,0,", bytesPerRow, height).getBytes());
            tsplOut.write(rawData);
            tsplOut.write("\r\n".getBytes());
            // ÇOKLU KOPYA: PRINT 1,1 yerine PRINT <adet>,1
            tsplOut.write(String.format("PRINT %d,1\r\n", adet).getBytes());

            log.info("PNG -> TSPL çoklu kopya dönüşümü tamamlandı [adet={}, raw_boyut={}, tspl_boyut={}]",
                    adet, rawData.length, tsplOut.size());

            return tsplOut.toByteArray();

        } finally {
            java.nio.file.Files.deleteIfExists(tempPng);
            java.nio.file.Files.deleteIfExists(tempBmp);
        }
    }

    private byte[] convertPngToPplb(byte[] pngData) throws IOException, InterruptedException {
        java.nio.file.Path tempPng = java.nio.file.Files.createTempFile("etiket_", ".png");
        java.nio.file.Path tempBmp = java.nio.file.Files.createTempFile("etiket_", ".bmp");

        try {
            java.nio.file.Files.write(tempPng, pngData);

            ProcessBuilder convertPb = new ProcessBuilder(
                "/usr/bin/convert", tempPng.toString(),
                "-resize", "640x800!",
                "-flip",
                "-monochrome",
                "-colors", "2",
                "-depth", "1",
                "-type", "bilevel",
                "BMP3:" + tempBmp.toString()
            );
            convertPb.redirectErrorStream(true);
            Process convertProcess = convertPb.start();
            int convertExit = convertProcess.waitFor();

            if (convertExit != 0) {
                throw new RuntimeException("ImageMagick dönüşüm hatası, exit code: " + convertExit);
            }

            byte[] bmpData = java.nio.file.Files.readAllBytes(tempBmp);
            int pixelOffset = (bmpData[10] & 0xFF) |
                              ((bmpData[11] & 0xFF) << 8) |
                              ((bmpData[12] & 0xFF) << 16) |
                              ((bmpData[13] & 0xFF) << 24);
            byte[] rawData = new byte[bmpData.length - pixelOffset];
            System.arraycopy(bmpData, pixelOffset, rawData, 0, rawData.length);

            int bytesPerRow = 80;
            int height = rawData.length / bytesPerRow;

            log.info("PPLB tek: pixelOffset={}, rawSize={}, height={}", pixelOffset, rawData.length, height);

            java.io.ByteArrayOutputStream pplbOut = new java.io.ByteArrayOutputStream();
            pplbOut.write("N\r\n".getBytes());
            pplbOut.write("^W640\r\n".getBytes());
            pplbOut.write(String.format("^Q%d,32\r\n", height).getBytes());
            pplbOut.write("^M1\r\n".getBytes());
            pplbOut.write(String.format("GW0,0,%d,%d,", bytesPerRow, height).getBytes());
            pplbOut.write(rawData);
            pplbOut.write("\r\n".getBytes());
            pplbOut.write("P1\r\n".getBytes());

            log.info("PNG -> PPLB tamamlandı [pplb_boyut={}]", pplbOut.size());
            return pplbOut.toByteArray();

        } finally {
            java.nio.file.Files.deleteIfExists(tempPng);
            java.nio.file.Files.deleteIfExists(tempBmp);
        }
    }

    /**
     * PNG görselini TSPL (XPrinter/TSC) formatına çevirir.
     * ImageMagick kullanarak PNG -> 1-bit BMP -> TSPL BITMAP komutu
     * 100x150mm @ 203dpi = 800x1200 dots
     */
    private byte[] convertPngToTspl(byte[] pngData) throws IOException, InterruptedException {
        // PNG'yi geçici dosyaya yaz
        java.nio.file.Path tempPng = java.nio.file.Files.createTempFile("etiket_tspl_", ".png");
        java.nio.file.Path tempBmp = java.nio.file.Files.createTempFile("etiket_tspl_", ".bmp");

        try {
            java.nio.file.Files.write(tempPng, pngData);

            // ImageMagick ile PNG'yi 1-bit BMP'ye çevir (100x150mm = 800x1200 dot @ 203dpi)
            ProcessBuilder convertPb = new ProcessBuilder(
                "/usr/bin/convert", tempPng.toString(),
                "-resize", "800x1200!",
                "-flip",
                "-monochrome",
                "-colors", "2",
                "-depth", "1",
                "-type", "bilevel",
                "BMP3:" + tempBmp.toString()
            );
            convertPb.redirectErrorStream(true);
            Process convertProcess = convertPb.start();
            int convertExit = convertProcess.waitFor();

            if (convertExit != 0) {
                throw new RuntimeException("ImageMagick dönüşüm hatası (TSPL), exit code: " + convertExit);
            }

            // BMP'den raw data çıkar (62 byte header atla)
            byte[] bmpData = java.nio.file.Files.readAllBytes(tempBmp);
            int headerSize = 62;
            byte[] rawData = new byte[bmpData.length - headerSize];
            System.arraycopy(bmpData, headerSize, rawData, 0, rawData.length);

            // TSPL komutu oluştur
            int width = 800;
            int height = 1200;
            int bytesPerRow = 100; // 800 / 8 = 100

            java.io.ByteArrayOutputStream tsplOut = new java.io.ByteArrayOutputStream();

            // TSPL başlangıç komutları
            tsplOut.write("SIZE 100 mm, 150 mm\r\n".getBytes());
            tsplOut.write("GAP 3 mm, 0 mm\r\n".getBytes());
            tsplOut.write("DIRECTION 1\r\n".getBytes());
            tsplOut.write("CLS\r\n".getBytes());

            // BITMAP komutu: x, y, width(bytes), height, mode, data
            tsplOut.write(String.format("BITMAP 0,0,%d,%d,0,", bytesPerRow, height).getBytes());
            tsplOut.write(rawData);
            tsplOut.write("\r\n".getBytes());

            // Yazdır ve son
            tsplOut.write("PRINT 1,1\r\n".getBytes());

            log.info("PNG -> TSPL dönüşümü tamamlandı [raw_boyut={}, tspl_boyut={}]",
                    rawData.length, tsplOut.size());

            return tsplOut.toByteArray();

        } finally {
            // Geçici dosyaları temizle
            java.nio.file.Files.deleteIfExists(tempPng);
            java.nio.file.Files.deleteIfExists(tempBmp);
        }
    }

    /**
     * IPP protokolü ile yazıcıya gönderir.
     * Ağ yazıcıları ve AirPrint için.
     */
    private boolean gonderIpp(Yazici yazici, byte[] data) {
        String ippUrl = yazici.getAdres();
        int port = yazici.getPort() != null ? yazici.getPort() : 631;

        log.info("IPP ile yazıcıya gönderiliyor [yazıcı={}, url={}:{}]",
                yazici.getKod(), ippUrl, port);

        // IPP için CUPS lp komutu kullanılabilir
        // Alternatif: cups4j veya başka bir IPP kütüphanesi
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "lp",
                    "-h", ippUrl + ":" + port,
                    "-"
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (OutputStream out = process.getOutputStream()) {
                out.write(data);
                out.flush();
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("IPP ile başarıyla gönderildi [yazıcı={}]", yazici.getKod());
                return true;
            } else {
                log.error("IPP hatası [yazıcı={}, exitCode={}]", yazici.getKod(), exitCode);
                throw new RuntimeException("IPP yazdırma hatası, exit code: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            log.error("IPP hatası [yazıcı={}]: {}", yazici.getKod(), e.getMessage());
            throw new RuntimeException("IPP yazdırma hatası: " + e.getMessage(), e);
        }
    }

    /**
     * CUPS çıktısından job ID'yi parse eder.
     * Örnek çıktı: "request id is Argox-Etiket-27 (1 file(s))"
     */
    private String parseJobIdFromOutput(String output) {
        if (output == null || output.isEmpty()) {
            return "unknown";
        }
        // "request id is X (Y file(s))" formatından X'i çıkar
        if (output.contains("request id is ")) {
            String[] parts = output.split("request id is ");
            if (parts.length > 1) {
                String remaining = parts[1];
                int parenIndex = remaining.indexOf(" (");
                if (parenIndex > 0) {
                    return remaining.substring(0, parenIndex);
                }
                return remaining.split(" ")[0];
            }
        }
        return output;
    }

    /**
     * CUPS yazdırma kuyruğundaki işleri sorgular.
     * @param yaziciAdi CUPS yazıcı adı (null ise tüm yazıcılar)
     * @return Job listesi
     */
    public List<CupsJob> getCupsJobs(String yaziciAdi) {
        try {
            ProcessBuilder pb;
            if (yaziciAdi != null) {
                pb = new ProcessBuilder("lpstat", "-o", yaziciAdi);
            } else {
                pb = new ProcessBuilder("lpstat", "-o");
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0 || output.isEmpty()) {
                return List.of();
            }

            // Her satır: "Argox-Etiket-27  <kullanici>  64512  Wed 14 Jan 2026 12:33:06 PM UTC"
            return output.lines()
                    .filter(line -> !line.isEmpty())
                    .map(this::parseCupsJobLine)
                    .filter(job -> job != null)
                    .toList();

        } catch (IOException | InterruptedException e) {
            log.error("CUPS job sorgu hatası: {}", e.getMessage());
            return List.of();
        }
    }

    private CupsJob parseCupsJobLine(String line) {
        try {
            // Format: "JobId  User  Size  Date"
            String[] parts = line.trim().split("\\s+", 4);
            if (parts.length >= 3) {
                return new CupsJob(parts[0], parts[1], parts.length > 2 ? parts[2] : "0",
                        parts.length > 3 ? parts[3] : "");
            }
        } catch (Exception e) {
            log.debug("Job satırı parse hatası: {}", line);
        }
        return null;
    }

    /**
     * Belirli bir CUPS job'ın durumunu kontrol eder.
     * @return "completed", "pending", "processing", "stopped", "cancelled" veya null
     */
    public String getJobStatus(String jobId) {
        try {
            ProcessBuilder pb = new ProcessBuilder("lpstat", "-W", "all", "-o");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();

            // Job ID'yi bul
            for (String line : output.split("\n")) {
                if (line.startsWith(jobId + " ") || line.startsWith(jobId + "\t")) {
                    return "pending"; // Kuyrukta bekliyor
                }
            }

            // Tamamlanmış işlerde ara
            ProcessBuilder pbCompleted = new ProcessBuilder("lpstat", "-W", "completed", "-o");
            pbCompleted.redirectErrorStream(true);
            Process processCompleted = pbCompleted.start();

            String completedOutput = new String(processCompleted.getInputStream().readAllBytes()).trim();
            processCompleted.waitFor();

            for (String line : completedOutput.split("\n")) {
                if (line.startsWith(jobId + " ") || line.startsWith(jobId + "\t")) {
                    return "completed";
                }
            }

            return null; // Bulunamadı

        } catch (IOException | InterruptedException e) {
            log.error("Job durum sorgu hatası [jobId={}]: {}", jobId, e.getMessage());
            return null;
        }
    }

    /**
     * Tamamlanmış CUPS işlerini sorgular (son N iş).
     * @param limit Maksimum iş sayısı
     * @return Tamamlanan job listesi
     */
    public List<CupsJob> getCompletedJobs(int limit) {
        try {
            ProcessBuilder pb = new ProcessBuilder("lpstat", "-W", "completed", "-o");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0 || output.isEmpty()) {
                return List.of();
            }

            List<CupsJob> allJobs = output.lines()
                    .filter(line -> !line.isEmpty())
                    .map(this::parseCupsJobLine)
                    .filter(job -> job != null)
                    .toList();

            // Son N işi döndür (ters sırada - en yeni en üstte)
            int startIndex = Math.max(0, allJobs.size() - limit);
            List<CupsJob> recentJobs = new java.util.ArrayList<>(allJobs.subList(startIndex, allJobs.size()));
            java.util.Collections.reverse(recentJobs);
            return recentJobs;

        } catch (IOException | InterruptedException e) {
            log.error("Tamamlanan jobs sorgu hatası: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * CUPS Job bilgisi
     */
    public record CupsJob(String jobId, String user, String size, String date) {}

    /**
     * Yazıcı bağlantısını test eder.
     *
     * @param yaziciKod Yazıcı kodu
     * @return Bağlantı durumu
     */
    public YaziciBaglantiDurumu testBaglanti(String yaziciKod) {
        Yazici yazici = yaziciRepository.findByKod(yaziciKod)
                .orElseThrow(() -> new RuntimeException("Yazıcı bulunamadı: " + yaziciKod));

        return testBaglanti(yazici);
    }

    /**
     * Yazıcı bağlantısını test eder.
     */
    public YaziciBaglantiDurumu testBaglanti(Yazici yazici) {
        if (yazici.getAdres() == null || yazici.getAdres().isEmpty()) {
            return new YaziciBaglantiDurumu(false, "Adres tanımlı değil", 0);
        }

        long startTime = System.currentTimeMillis();

        try {
            if (PrintConstants.BAGLANTI_RAW_SOCKET.equals(yazici.getBaglantiTipi())) {
                return testRawSocketBaglanti(yazici, startTime);
            } else if (PrintConstants.BAGLANTI_CUPS.equals(yazici.getBaglantiTipi())) {
                return testCupsBaglanti(yazici, startTime);
            } else if (PrintConstants.BAGLANTI_IPP.equals(yazici.getBaglantiTipi())) {
                return testIppBaglanti(yazici, startTime);
            } else {
                return new YaziciBaglantiDurumu(false, "Test desteklenmiyor: " + yazici.getBaglantiTipi(), 0);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return new YaziciBaglantiDurumu(false, e.getMessage(), elapsed);
        }
    }

    private YaziciBaglantiDurumu testRawSocketBaglanti(Yazici yazici, long startTime) {
        String adres = yazici.getAdres();
        int port = yazici.getPort() != null ? yazici.getPort() : PrintConstants.RAW_SOCKET_DEFAULT_PORT;

        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(adres, port),
                    PrintConstants.RAW_SOCKET_CONNECT_TIMEOUT_MS
            );
            long elapsed = System.currentTimeMillis() - startTime;
            return new YaziciBaglantiDurumu(true, "Bağlantı başarılı", elapsed);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return new YaziciBaglantiDurumu(false, "Bağlantı hatası: " + e.getMessage(), elapsed);
        }
    }

    private YaziciBaglantiDurumu testCupsBaglanti(Yazici yazici, long startTime) {
        try {
            ProcessBuilder pb = new ProcessBuilder("lpstat", "-p", yazici.getAdres());
            Process process = pb.start();
            int exitCode = process.waitFor();
            long elapsed = System.currentTimeMillis() - startTime;

            if (exitCode == 0) {
                return new YaziciBaglantiDurumu(true, "CUPS yazıcı aktif", elapsed);
            } else {
                return new YaziciBaglantiDurumu(false, "CUPS yazıcı bulunamadı", elapsed);
            }
        } catch (IOException | InterruptedException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return new YaziciBaglantiDurumu(false, "CUPS hatası: " + e.getMessage(), elapsed);
        }
    }

    private YaziciBaglantiDurumu testIppBaglanti(Yazici yazici, long startTime) {
        String adres = yazici.getAdres();
        int port = yazici.getPort() != null ? yazici.getPort() : 631;

        // IPP için socket bağlantısı test et
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(adres, port),
                    PrintConstants.RAW_SOCKET_CONNECT_TIMEOUT_MS
            );
            long elapsed = System.currentTimeMillis() - startTime;
            return new YaziciBaglantiDurumu(true, "IPP bağlantısı başarılı (" + adres + ":" + port + ")", elapsed);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return new YaziciBaglantiDurumu(false, "IPP bağlantı hatası: " + e.getMessage(), elapsed);
        }
    }

    /**
     * Tüm aktif yazıcıları döner.
     */
    public List<Yazici> getAktifYazicilar() {
        return yaziciRepository.findByAktifTrueOrderByAdAsc();
    }

    /**
     * Varsayılan yazıcıları döner (tip bazında).
     */
    public List<Yazici> getVarsayilanYazicilar() {
        return yaziciRepository.findByVarsayilanTrueAndAktifTrue();
    }

    /**
     * Yazıcıyı kod ile bulur.
     */
    public Optional<Yazici> getByKod(String kod) {
        return yaziciRepository.findByKod(kod);
    }

    /**
     * Yazıcı bağlantı durumu DTO
     */
    public record YaziciBaglantiDurumu(
            boolean basarili,
            String mesaj,
            long responseTimeMs
    ) {}
}
