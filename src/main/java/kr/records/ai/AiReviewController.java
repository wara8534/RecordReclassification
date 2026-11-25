package kr.records.ai;

import jakarta.validation.Valid;
import kr.records.ClassificationService;
import kr.records.Models.RecordItem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static kr.records.ai.AiReviewModels.*;

@RestController
@RequestMapping("/api/records/ai")
class AiReviewController {
    private static final int MAX_ATTACHMENTS = 20;
    private static final long MAX_ATTACHMENT_SIZE = 25L * 1024 * 1024;
    private static final long MAX_TOTAL_ATTACHMENT_SIZE = 100L * 1024 * 1024;
    private static final Map<String, AttachmentType> TYPES = Map.ofEntries(
        Map.entry("pdf", AttachmentType.PDF),
        Map.entry("png", AttachmentType.IMAGE), Map.entry("jpg", AttachmentType.IMAGE), Map.entry("jpeg", AttachmentType.IMAGE),
        Map.entry("wav", AttachmentType.AUDIO), Map.entry("mp3", AttachmentType.AUDIO), Map.entry("m4a", AttachmentType.AUDIO),
        Map.entry("flac", AttachmentType.AUDIO), Map.entry("mp4", AttachmentType.VIDEO), Map.entry("mov", AttachmentType.VIDEO),
        Map.entry("mkv", AttachmentType.VIDEO), Map.entry("webm", AttachmentType.VIDEO));

    private final ClassificationService classification;
    private final AiReviewPipeline pipeline;
    private final Semaphore slot = new Semaphore(1);

    AiReviewController(ClassificationService classification, ObjectProvider<AiReviewPipeline> configuredPipeline) {
        this.classification = classification;
        this.pipeline = configuredPipeline.getIfAvailable();
    }

    @GetMapping("/status")
    Map<String, Boolean> status() { return Map.of("enabled", pipeline != null); }

    @PostMapping(value = "/review", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ReviewResult review(@Valid @RequestPart("record") RecordItem record,
                        @RequestParam(name = "completeRecord", defaultValue = "false") boolean completeRecord,
                        @RequestPart(name = "attachments", required = false) List<MultipartFile> uploads) throws IOException {
        if (pipeline == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var policy = classification.policy();
        if (record.reviewed()) return pipeline.review(record, List.of(), policy);
        if (!completeRecord) return new ReviewResult(Status.SOURCE_INCOMPLETE, policy.version(), null, List.of(), null);
        List<MultipartFile> files = uploads == null ? List.of() : uploads;
        if (files.size() > MAX_ATTACHMENTS || files.stream().mapToLong(MultipartFile::getSize).sum() > MAX_TOTAL_ATTACHMENT_SIZE
            || files.stream().anyMatch(file -> file.isEmpty() || file.getSize() > MAX_ATTACHMENT_SIZE)) {
            throw new IllegalArgumentException("기록물당 첨부 20개, 파일당 25MB, 전체 100MB까지 올릴 수 있습니다. 빈 파일도 확인해 주세요.");
        }
        List<AttachmentType> types = files.stream().map(this::type).toList();
        if (!slot.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        List<Path> temporaryFiles = new ArrayList<>();
        try {
            List<Attachment> attachments = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                Path temporary = Files.createTempFile("record-on-review-", ".upload");
                temporaryFiles.add(temporary);
                files.get(i).transferTo(temporary);
                attachments.add(new Attachment("attachment-" + (i + 1), types.get(i), temporary));
            }
            return pipeline.review(record, attachments, policy);
        } finally {
            try {
                for (Path temporary : temporaryFiles) Files.deleteIfExists(temporary);
            } finally {
                slot.release();
            }
        }
    }

    private AttachmentType type(MultipartFile file) {
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        AttachmentType type = dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (type == null) throw new IllegalArgumentException("지원하는 PDF, 이미지, 음성 또는 영상 파일을 첨부해 주세요.");
        return type;
    }
}
