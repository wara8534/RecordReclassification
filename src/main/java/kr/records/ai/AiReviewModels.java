package kr.records.ai;

import kr.records.Models.SourceSheet;

import java.nio.file.Path;
import java.util.List;

public final class AiReviewModels {
    private AiReviewModels() {}

    public enum AttachmentType { PDF, IMAGE, AUDIO, VIDEO }

    // The upload handler supplies a validated temporary path owned by this job.
    public record Attachment(String id, AttachmentType type, Path temporaryFile) {}

    public sealed interface Location permits ExcelCell, PdfPage, ImageRegion, MediaRange {}
    public record ExcelCell(SourceSheet sheet, int row, String column) implements Location {}
    public record PdfPage(String attachmentId, int page) implements Location {}
    public record ImageRegion(String attachmentId, int page,
                              int left, int top, int right, int bottom) implements Location {}
    public record MediaRange(String attachmentId, long startMillis, long endMillis) implements Location {}

    public record Evidence(String id, String text, Location location) {}
    public record Extraction(List<Evidence> segments, boolean complete) {}

    public record Metadata(String title, String department, String year, String category, String keywords,
                           String originalStatus, SourceSheet sourceType) {}
    public record MaskedEvidence(String id, String text) {}
    public record SensitiveSignal(String evidenceId, String type) {}
    public record MaskedInput(Metadata metadata, List<MaskedEvidence> evidence, List<SensitiveSignal> signals) {}

    public record PolicyContext(String source, String version, String article9Text, List<Integer> allowedCriterionNumbers) {}
    public enum ReviewStage { WHOLE, PART, SYNTHESIS }
    public record ReviewInput(Metadata metadata, List<MaskedEvidence> evidence, List<SensitiveSignal> signals,
                              ReviewStage stage, int partNumber, int totalParts, List<Proposal> partReviews) {}
    public enum Recommendation { PUBLIC, PARTIAL, PRIVATE, NEEDS_REVIEW }
    public record Proposal(Recommendation recommendation, List<Integer> criterionNumbers,
                           List<String> evidenceIds, String rationale) {}

    public enum Status { READY, SKIPPED_REVIEWED, SOURCE_INCOMPLETE, EXTRACTION_INCOMPLETE, INPUT_TOO_LARGE,
                         MODEL_UNAVAILABLE, INVALID_MODEL_RESPONSE, CONFLICTING_FINDINGS }
    public record Citation(String evidenceId, Location location) {}
    public record Coverage(int attachmentCount, int evidenceSegments, int reviewedParts) {}
    public record ReviewResult(Status status, String policyVersion, Proposal proposal, List<Citation> citations, Coverage coverage) {}
}
