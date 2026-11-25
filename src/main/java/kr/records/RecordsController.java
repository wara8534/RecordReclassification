package kr.records;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static kr.records.Models.*;

@RestController
@RequestMapping("/api")
public class RecordsController {
    private final ExcelService excel;
    private final ClassificationService classifier;
    private final boolean aiReviewEnabled;

    public RecordsController(ExcelService excel, ClassificationService classifier,
                             @Value("${recordon.ai.enabled}") boolean aiReviewEnabled) {
        this.excel = excel;
        this.classifier = classifier;
        this.aiReviewEnabled = aiReviewEnabled;
    }
    @GetMapping("/classification-policy")
    public ClassificationPolicy policy() { return classifier.policy(); }

    @PostMapping(value="/records/import", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult upload(@RequestParam("file") MultipartFile file) {
        ImportResult imported = excel.read(file);
        var warnings = aiReviewEnabled ? List.of("기록물 전체 검토를 완료하면 공개 관련 값이 결과 엑셀에 반영됩니다.") : imported.warnings();
        return new ImportResult(imported.filename(), classify(new ClassifyRequest(imported.records())), warnings);
    }

    @PostMapping("/records/classify")
    public List<RecordItem> classify(@Valid @RequestBody ClassifyRequest request) {
        return aiReviewEnabled ? classifier.awaitFullReview(request) : classifier.classify(request);
    }

    @PostMapping(value="/records/export", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> export(@RequestPart("file") MultipartFile file,
                                         @Valid @RequestPart(value="records", required=false) ExportRequest request,
                                         @Valid @RequestPart(value="edits", required=false) ExportEditsRequest edits) throws IOException {
        if ((request == null) == (edits == null)) throw new IllegalArgumentException("전체 결과 또는 수정 내역 중 하나를 보내 주세요.");
        byte[] result = request != null ? excel.write(file, request.records(), aiReviewEnabled)
            : excel.writeEdits(file, edits.edits(), aiReviewEnabled, this::classify);
        return resultDownload(file, result);
    }
    @PostMapping(value="/records/process", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> process(@RequestParam("file") MultipartFile file) throws IOException {
        return resultDownload(file, excel.writeEdits(file, List.of(), aiReviewEnabled, this::classify));
    }
    @GetMapping("/records/template")
    public ResponseEntity<byte[]> template() throws IOException {
        return download(excel.template(), ExcelService.TEMPLATE_FILENAME);
    }
    private ResponseEntity<byte[]> resultDownload(MultipartFile original, byte[] result) {
        boolean legacy = original.getOriginalFilename() != null && original.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".xls");
        return download(result, legacy ? "기록물_분류결과.xls" : "기록물_분류결과.xlsx");
    }
    private ResponseEntity<byte[]> download(byte[] bytes, String name) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(name.endsWith(".xlsx")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.ms-excel"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes);
    }
}
