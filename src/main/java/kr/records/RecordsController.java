package kr.records;

import jakarta.validation.Valid;
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
    public RecordsController(ExcelService excel, ClassificationService classifier) {
        this.excel = excel;
        this.classifier = classifier;
    }
    @GetMapping("/classification-policy")
    public ClassificationPolicy policy() { return classifier.policy(); }

    @PostMapping(value="/records/import", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult upload(@RequestParam("file") MultipartFile file) {
        ImportResult imported = excel.read(file);
        return new ImportResult(imported.filename(), classifier.classify(new ClassifyRequest(imported.records())), imported.warnings());
    }

    @PostMapping("/records/classify")
    public List<RecordItem> classify(@Valid @RequestBody ClassifyRequest request) { return classifier.classify(request); }

    @PostMapping(value="/records/export", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> export(@RequestPart("file") MultipartFile file,
                                         @Valid @RequestPart("records") ExportRequest request) throws IOException {
        boolean legacy = file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".xls");
        return download(excel.write(file, request.records()), legacy ? "기록물_분류결과.xls" : "기록물_분류결과.xlsx");
    }
    @PostMapping(value="/records/process", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> process(@RequestParam("file") MultipartFile file) throws IOException {
        ImportResult result = upload(file);
        return export(file, new ExportRequest(result.records()));
    }
    @GetMapping("/records/template")
    public ResponseEntity<byte[]> template() throws IOException {
        return download(excel.template(), "서식.xlsx");
    }
    private ResponseEntity<byte[]> download(byte[] bytes, String name) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(name.endsWith(".xlsx")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.ms-excel"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes);
    }
}
