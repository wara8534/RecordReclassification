package kr.records.ai;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class RegisteredArticle {
    private static final String PDF_SHA256 = "e4064294d7e56841e0f6f20f56106c42ec91abaf0b1050f5af036ad11426a1c9";
    static final String TEXT = loadText();

    private RegisteredArticle() {}

    private static String loadText() {
        var pdf = new ClassPathResource("static/reference/information-disclosure-article9.pdf");
        var text = new ClassPathResource("information-disclosure-article9.txt");
        try (var source = pdf.getInputStream()) {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.readAllBytes()));
            if (!PDF_SHA256.equals(hash)) throw new IllegalStateException("등록 법령 PDF가 변경되었습니다. AI용 원문을 다시 준비해 주세요.");
            return text.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("등록 법령 원문을 읽을 수 없습니다.", e);
        }
    }
}
