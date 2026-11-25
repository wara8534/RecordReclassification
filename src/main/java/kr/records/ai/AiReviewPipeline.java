package kr.records.ai;

import kr.records.Models.ClassificationPolicy;
import kr.records.Models.RecordItem;
import kr.records.Models.SourceSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static kr.records.ai.AiReviewModels.*;

public final class AiReviewPipeline {
    private static final int PART_CHARS = 8000;
    private static final int PART_SEGMENTS = 80;
    private static final int MAX_PARTS = 24;
    static final int PART_RATIONALE_LENGTH = 550;
    static final int PART_CITATIONS = 6;

    @FunctionalInterface
    public interface TextExtractor {
        Extraction extract(Attachment attachment) throws ProcessingUnavailableException;
    }

    @FunctionalInterface
    public interface SensitiveDataScreen {
        // Mask metadata and evidence text. Preserve evidence IDs and report types without matched values.
        MaskedInput prepare(Metadata metadata, List<Evidence> evidence);
    }

    @FunctionalInterface
    public interface ReviewModel {
        // Return null for an unparseable response. Translate transport failures to ProcessingUnavailableException.
        Proposal recommend(PolicyContext policy, ReviewInput input) throws ProcessingUnavailableException;
    }

    public static final class ProcessingUnavailableException extends Exception {
        public ProcessingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final TextExtractor extractor;
    private final SensitiveDataScreen screen;
    private final ReviewModel model;

    public AiReviewPipeline(TextExtractor extractor, SensitiveDataScreen screen, ReviewModel model) {
        this.extractor = extractor;
        this.screen = screen;
        this.model = model;
        // Load and verify the registered source when AI mode starts.
        this.articleText = RegisteredArticle.TEXT;
    }

    private final String articleText;

    public ReviewResult review(RecordItem record, List<Attachment> attachments, ClassificationPolicy policy) {
        if (record.reviewed()) return hold(Status.SKIPPED_REVIEWED, policy);

        List<Evidence> evidence = new ArrayList<>();
        if (!record.content().isBlank()) {
            var sheet = record.sourceSheet();
            var location = new ExcelCell(sheet, record.sourceRow(), sheet == null ? "내용" : sheet.contentHeader());
            evidence.add(new Evidence("excel:content", record.content(), location));
        }
        if (attachments.isEmpty()) {
            var sheet = record.sourceSheet();
            String titleColumn = sheet == null ? "기록물제목" : sheet == SourceSheet.FOLDER ? "제목" : "건제목";
            addMetadataEvidence(evidence, record, "title", titleColumn, record.title());
            addMetadataEvidence(evidence, record, "department", "생산부서", record.department());
            addMetadataEvidence(evidence, record, "year", "생산연도", record.year());
            addMetadataEvidence(evidence, record, "category", "기록물분류", record.category());
            addMetadataEvidence(evidence, record, "keywords", "키워드", record.keywords());
        }
        try {
            for (Attachment attachment : attachments) {
                Extraction extracted = extractor.extract(attachment);
                List<Evidence> readable = extracted.segments().stream()
                    .filter(segment -> !segment.text().isBlank()).toList();
                if (!extracted.complete() || readable.isEmpty()) {
                    return hold(Status.EXTRACTION_INCOMPLETE, policy);
                }
                evidence.addAll(readable);
            }
        } catch (ProcessingUnavailableException e) {
            return hold(Status.EXTRACTION_INCOMPLETE, policy);
        }
        if (evidence.isEmpty()) return hold(Status.EXTRACTION_INCOMPLETE, policy);

        Map<String, Location> locations = new HashMap<>();
        for (Evidence segment : evidence) {
            if (segment.id().isBlank() || segment.location() == null
                || locations.putIfAbsent(segment.id(), segment.location()) != null) {
                return hold(Status.EXTRACTION_INCOMPLETE, policy);
            }
        }
        Metadata metadata = new Metadata(record.title(), record.department(), record.year(), record.category(), record.keywords(),
            record.originalStatus(), record.sourceSheet());
        MaskedInput input = screen.prepare(metadata, List.copyOf(evidence));
        if (input.evidence().size() > 2400 || input.evidence().stream().mapToLong(segment -> segment.text().length()).sum() > 120000) {
            return hold(Status.INPUT_TOO_LARGE, policy);
        }
        Set<String> submittedIds = input.evidence().stream().filter(segment -> !segment.text().isBlank())
            .map(MaskedEvidence::id).collect(Collectors.toSet());
        if (!submittedIds.equals(locations.keySet()) || submittedIds.size() != input.evidence().size()) {
            return hold(Status.EXTRACTION_INCOMPLETE, policy);
        }

        List<List<MaskedEvidence>> parts = partition(input.evidence(), locations);
        if (parts.size() > MAX_PARTS) return hold(Status.INPUT_TOO_LARGE, policy);
        PolicyContext context = new PolicyContext(policy.source(), policy.version(), articleText,
            policy.criteria().stream().map(criterion -> criterion.number()).toList());
        Proposal proposal;
        try {
            proposal = reviewParts(context, input, parts);
        } catch (ProcessingUnavailableException e) {
            return hold(Status.MODEL_UNAVAILABLE, policy);
        } catch (ConflictingReviewException e) {
            return hold(Status.CONFLICTING_FINDINGS, policy);
        }
        if (proposal == null) return hold(Status.INVALID_MODEL_RESPONSE, policy);

        List<Citation> citations = proposal.evidenceIds().stream().map(id -> new Citation(id, locations.get(id))).toList();
        Proposal validated = new Proposal(proposal.recommendation(), List.copyOf(proposal.criterionNumbers()),
            List.copyOf(proposal.evidenceIds()), proposal.rationale());
        return new ReviewResult(Status.READY, policy.version(), validated, citations,
            new Coverage(attachments.size(), evidence.size(), parts.size()));
    }

    private void addMetadataEvidence(List<Evidence> evidence, RecordItem record, String field, String column, String value) {
        if (!value.isBlank()) {
            evidence.add(new Evidence("excel:meta:" + field, value, new ExcelCell(record.sourceSheet(), record.sourceRow(), column)));
        }
    }

    private List<List<MaskedEvidence>> partition(List<MaskedEvidence> evidence, Map<String, Location> locations) {
        List<List<MaskedEvidence>> parts = new ArrayList<>();
        List<MaskedEvidence> part = new ArrayList<>();
        int chars = 0;
        for (MaskedEvidence item : evidence) {
            for (MaskedEvidence piece : split(item, locations)) {
                if (chars + piece.text().length() > PART_CHARS || part.size() == PART_SEGMENTS) {
                    parts.add(part);
                    part = new ArrayList<>();
                    chars = 0;
                }
                part.add(piece);
                chars += piece.text().length();
            }
        }
        if (!part.isEmpty()) parts.add(part);
        return parts;
    }

    private List<MaskedEvidence> split(MaskedEvidence item, Map<String, Location> locations) {
        String text = item.text();
        if (text.length() <= PART_CHARS) return List.of(item);
        List<MaskedEvidence> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + PART_CHARS);
            if (end < text.length()) {
                int boundary = Math.max(text.lastIndexOf('\n', end - 1), text.lastIndexOf(' ', end - 1));
                if (boundary >= start + PART_CHARS * 3 / 4) end = boundary + 1;
                if (Character.isLowSurrogate(text.charAt(end))) end--;
            }
            String id = item.id() + "/" + (pieces.size() + 1);
            locations.put(id, locations.get(item.id()));
            pieces.add(new MaskedEvidence(id, text.substring(start, end)));
            start = end;
        }
        return pieces;
    }

    private Proposal reviewParts(PolicyContext policy, MaskedInput input, List<List<MaskedEvidence>> parts)
        throws ProcessingUnavailableException, ConflictingReviewException {
        if (parts.size() == 1) {
            List<MaskedEvidence> evidence = parts.get(0);
            var proposal = model.recommend(policy, new ReviewInput(input.metadata(), evidence, input.signals(),
                ReviewStage.WHOLE, 1, 1, List.of()));
            return isValid(proposal, policy, ids(evidence)) ? proposal : null;
        }

        Map<String, List<SensitiveSignal>> signalsById = input.signals().stream().collect(Collectors.groupingBy(SensitiveSignal::evidenceId));
        List<Proposal> findings = new ArrayList<>();
        for (List<MaskedEvidence> part : parts) {
            var signals = part.stream().flatMap(piece -> signalsById.getOrDefault(piece.id().split("/", 2)[0], List.of()).stream()
                .map(signal -> new SensitiveSignal(piece.id(), signal.type()))).toList();
            Proposal finding = model.recommend(policy, new ReviewInput(input.metadata(), part, signals,
                ReviewStage.PART, findings.size() + 1, parts.size(), List.of()));
            if (!isValid(finding, policy, ids(part)) || finding.rationale().length() > PART_RATIONALE_LENGTH
                || finding.evidenceIds().size() > PART_CITATIONS) return null;
            findings.add(finding);
        }

        Set<String> cited = findings.stream().flatMap(finding -> finding.evidenceIds().stream()).collect(Collectors.toSet());
        Proposal proposal = model.recommend(policy, new ReviewInput(input.metadata(), List.of(), List.of(),
            ReviewStage.SYNTHESIS, 0, parts.size(), List.copyOf(findings)));
        if (!isValid(proposal, policy, cited)) return null;
        if (proposal.recommendation() == Recommendation.PUBLIC
            && findings.stream().anyMatch(finding -> finding.recommendation() != Recommendation.PUBLIC)) {
            throw new ConflictingReviewException();
        }
        return proposal;
    }

    private Set<String> ids(List<MaskedEvidence> evidence) {
        return evidence.stream().map(MaskedEvidence::id).collect(Collectors.toSet());
    }

    private static final class ConflictingReviewException extends Exception {}

    private boolean isValid(Proposal proposal, PolicyContext policy, Set<String> submittedIds) {
        if (proposal == null || proposal.recommendation() == null || proposal.criterionNumbers() == null
            || proposal.evidenceIds() == null || proposal.rationale() == null || proposal.rationale().isBlank()
            || proposal.rationale().length() > 2000) return false;

        Set<Integer> validNumbers = new HashSet<>(policy.allowedCriterionNumbers());
        if (!validNumbers.containsAll(proposal.criterionNumbers()) || !submittedIds.containsAll(proposal.evidenceIds())
            || new HashSet<>(proposal.evidenceIds()).size() != proposal.evidenceIds().size()
            || new HashSet<>(proposal.criterionNumbers()).size() != proposal.criterionNumbers().size()) return false;

        return switch (proposal.recommendation()) {
            case PUBLIC -> !proposal.evidenceIds().isEmpty();
            case PARTIAL, PRIVATE -> !proposal.criterionNumbers().isEmpty() && !proposal.evidenceIds().isEmpty();
            case NEEDS_REVIEW -> true;
        };
    }

    private ReviewResult hold(Status status, ClassificationPolicy policy) {
        return new ReviewResult(status, policy.version(), null, List.of(), null);
    }
}
