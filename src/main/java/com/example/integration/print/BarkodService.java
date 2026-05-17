package com.example.integration.print;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.oned.EAN13Writer;
import com.google.zxing.oned.EAN8Writer;
import com.google.zxing.oned.Code39Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Merkezi barkod ve QR kod oluşturma servisi.
 * Tüm barkod işlemleri bu servis üzerinden yapılmalıdır.
 */
@Service
@Slf4j
public class BarkodService {

    /**
     * Code128 barkod oluşturur (varsayılan boyut).
     *
     * @param content Barkod içeriği
     * @return BufferedImage olarak barkod görseli
     */
    public BufferedImage createCode128(String content) {
        return createCode128(content, PrintConstants.BARCODE_WIDTH_DEFAULT, PrintConstants.BARCODE_HEIGHT_DEFAULT);
    }

    /**
     * Code128 barkod oluşturur (özel boyut).
     *
     * @param content Barkod içeriği
     * @param width   Genişlik (piksel)
     * @param height  Yükseklik (piksel)
     * @return BufferedImage olarak barkod görseli
     */
    public BufferedImage createCode128(String content, int width, int height) {
        try {
            Code128Writer writer = new Code128Writer();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.CODE_128, width, height, hints);
            return MatrixToImageWriter.toBufferedImage(bitMatrix);
        } catch (Exception e) {
            log.error("Code128 barkod oluşturma hatası [content={}]: {}", content, e.getMessage());
            return createEmptyImage(width, height);
        }
    }

    /**
     * QR kod oluşturur (varsayılan boyut).
     *
     * @param content QR kod içeriği
     * @return BufferedImage olarak QR kod görseli
     */
    public BufferedImage createQrCode(String content) {
        return createQrCode(content, PrintConstants.QR_SIZE_DEFAULT);
    }

    /**
     * QR kod oluşturur (özel boyut).
     *
     * @param content QR kod içeriği
     * @param size    Boyut (piksel, kare)
     * @return BufferedImage olarak QR kod görseli
     */
    public BufferedImage createQrCode(String content, int size) {
        return createQrCode(content, size, ErrorCorrectionLevel.M);
    }

    /**
     * QR kod oluşturur (özel boyut ve hata düzeltme seviyesi).
     *
     * @param content         QR kod içeriği
     * @param size            Boyut (piksel, kare)
     * @param errorCorrection Hata düzeltme seviyesi (L, M, Q, H)
     * @return BufferedImage olarak QR kod görseli
     */
    public BufferedImage createQrCode(String content, int size, ErrorCorrectionLevel errorCorrection) {
        try {
            QRCodeWriter writer = new QRCodeWriter();

            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, errorCorrection);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            // Manuel piksel çevirimi (beyaz arka plan, siyah modül)
            BufferedImage qrImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    qrImage.setRGB(x, y, bitMatrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }

            return qrImage;
        } catch (Exception e) {
            log.error("QR kod oluşturma hatası [content={}]: {}", content, e.getMessage());
            return createEmptyImage(size, size);
        }
    }

    /**
     * EAN-13 barkod oluşturur.
     *
     * @param content 13 haneli EAN kodu
     * @param width   Genişlik (piksel)
     * @param height  Yükseklik (piksel)
     * @return BufferedImage olarak barkod görseli
     */
    public BufferedImage createEan13(String content, int width, int height) {
        try {
            EAN13Writer writer = new EAN13Writer();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.EAN_13, width, height, hints);
            return MatrixToImageWriter.toBufferedImage(bitMatrix);
        } catch (Exception e) {
            log.error("EAN-13 barkod oluşturma hatası [content={}]: {}", content, e.getMessage());
            return createEmptyImage(width, height);
        }
    }

    /**
     * EAN-8 barkod oluşturur.
     *
     * @param content 8 haneli EAN kodu
     * @param width   Genişlik (piksel)
     * @param height  Yükseklik (piksel)
     * @return BufferedImage olarak barkod görseli
     */
    public BufferedImage createEan8(String content, int width, int height) {
        try {
            EAN8Writer writer = new EAN8Writer();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.EAN_8, width, height, hints);
            return MatrixToImageWriter.toBufferedImage(bitMatrix);
        } catch (Exception e) {
            log.error("EAN-8 barkod oluşturma hatası [content={}]: {}", content, e.getMessage());
            return createEmptyImage(width, height);
        }
    }

    /**
     * Code39 barkod oluşturur.
     *
     * @param content Barkod içeriği
     * @param width   Genişlik (piksel)
     * @param height  Yükseklik (piksel)
     * @return BufferedImage olarak barkod görseli
     */
    public BufferedImage createCode39(String content, int width, int height) {
        try {
            Code39Writer writer = new Code39Writer();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.CODE_39, width, height, hints);
            return MatrixToImageWriter.toBufferedImage(bitMatrix);
        } catch (Exception e) {
            log.error("Code39 barkod oluşturma hatası [content={}]: {}", content, e.getMessage());
            return createEmptyImage(width, height);
        }
    }

    /**
     * Genel barkod oluşturma metodu.
     *
     * @param content Barkod içeriği
     * @param type    Barkod tipi
     * @param width   Genişlik (piksel)
     * @param height  Yükseklik (piksel)
     * @return BufferedImage olarak barkod görseli
     */
    public BufferedImage createBarcode(String content, PrintConstants.BarcodeType type, int width, int height) {
        return switch (type) {
            case CODE128 -> createCode128(content, width, height);
            case QR_CODE -> createQrCode(content, Math.max(width, height));
            case EAN13 -> createEan13(content, width, height);
            case EAN8 -> createEan8(content, width, height);
            case CODE39 -> createCode39(content, width, height);
        };
    }

    /**
     * Boş görsel oluşturur (hata durumunda fallback).
     */
    private BufferedImage createEmptyImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        // Beyaz arka plan
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, 0xFFFFFF);
            }
        }
        return image;
    }
}
