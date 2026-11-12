package cz.s74.vouchers.email;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QrCodeService {

    /** PNG s QR kódem; korekce chyb M, okraj 2 moduly. */
    public byte[] png(String content, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter()
                    .encode(
                            content,
                            BarcodeFormat.QR_CODE,
                            size,
                            size,
                            Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("QR code generation failed", e);
        }
    }
}
