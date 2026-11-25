package kr.records;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

import static kr.records.Models.MAX_RECORD_ROWS_LABEL;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> validation(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "입력 형식 또는 길이가 올바르지 않습니다. 기록물은 "
            + MAX_RECORD_ROWS_LABEL + "건까지 가능하며 분류 기준은 서버에서 고정 적용됩니다."));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> size(Exception e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "파일 크기는 최대 10MB입니다."));
    }
}
