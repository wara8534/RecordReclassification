package kr.records;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Models {
    private Models() {}
    public static final String STATUS = "공개|부분공개|비공개|검토필요";
    public static final int MAX_RECORD_ROWS = 200_000;
    static final String MAX_RECORD_ROWS_LABEL = String.format(Locale.ROOT, "%,d", MAX_RECORD_ROWS);
    private static final Set<String> DISCLOSURE_DECISIONS = Set.of("공개", "부분공개", "비공개");
    private static final java.util.regex.Pattern STATUS_WHITESPACE =
        java.util.regex.Pattern.compile("\\s+", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    static boolean disclosureChanged(String originalStatus, String proposedStatus) {
        if (!DISCLOSURE_DECISIONS.contains(proposedStatus)) return false;
        String original = STATUS_WHITESPACE.matcher(originalStatus).replaceAll("");
        return DISCLOSURE_DECISIONS.contains(original) && !original.equals(proposedStatus);
    }

    public enum SourceSheet {
        FOLDER("기록물등록 기록물철 일괄등록서식", "비고"),
        ITEM("기록물건 일괄등록서식 - 시트순서변경금지", "비고 / 주요내용");

        private final String sheetName;
        private final String contentHeader;

        SourceSheet(String sheetName, String contentHeader) {
            this.sheetName = sheetName;
            this.contentHeader = contentHeader;
        }

        public String sheetName() { return sheetName; }
        public String contentHeader() { return contentHeader; }
    }

    public record RecordItem(
        @NotBlank @Size(max=200) String id,
        @NotBlank @Size(max=2000) String title,
        @NotNull @Size(max=500) String department,
        @NotNull @Size(max=100) String year,
        @NotNull @Size(max=10000) String content,
        @NotNull @Size(max=100) String originalStatus,
        @NotNull @Pattern(regexp=STATUS) String recommendation,
        @NotNull @Pattern(regexp=STATUS) String decision,
        @NotNull @Size(max=2000) String reason,
        boolean reviewed,
        @Min(2) int sourceRow,
        @NotNull @Size(max=500) String category,
        @NotNull @Size(max=2000) String keywords,
        @NotNull @Size(max=2000) String nonDisclosureReason,
        SourceSheet sourceSheet,
        @Size(max=8) List<@NotNull @Min(1) @Max(8) Integer> nonDisclosureGrades,
        @Size(max=2000) String restrictedPart,
        @Size(max=200) String sourceSequence,
        @Size(max=200) String parentFolderSequence,
        @Size(max=200) String itemSequence
    ) {
        public RecordItem {
            nonDisclosureGrades = nonDisclosureGrades == null ? List.of() : nonDisclosureGrades;
            restrictedPart = restrictedPart == null ? "" : restrictedPart;
            sourceSequence = sourceSequence == null ? "" : sourceSequence;
            parentFolderSequence = parentFolderSequence == null ? "" : parentFolderSequence;
            itemSequence = itemSequence == null ? "" : itemSequence;
        }

        public RecordItem(String id, String title, String department, String year, String content, String originalStatus,
                          String recommendation, String decision, String reason, boolean reviewed, int sourceRow,
                          String category, String keywords, String nonDisclosureReason, SourceSheet sourceSheet,
                          List<Integer> nonDisclosureGrades, String restrictedPart) {
            this(id, title, department, year, content, originalStatus, recommendation, decision, reason, reviewed,
                sourceRow, category, keywords, nonDisclosureReason, sourceSheet, nonDisclosureGrades, restrictedPart, "", "", "");
        }

        public RecordItem(String id, String title, String department, String year, String content, String originalStatus,
                          String recommendation, String decision, String reason, boolean reviewed, int sourceRow,
                          String category, String keywords, String nonDisclosureReason) {
            this(id, title, department, year, content, originalStatus, recommendation, decision, reason, reviewed,
                sourceRow, category, keywords, nonDisclosureReason, null, List.of(), "");
        }
    }
    public record Criterion(int number, String title, String requirement, List<String> keywords,
                            String exceptionNote, List<String> exceptionSignals) {}
    public record ClassificationPolicy(String source, String version, String sourceUrl,
                                       String method, List<Criterion> criteria, List<String> notes) {}
    public record ClassifyRequest(
        @NotNull @Size(min=1, max=MAX_RECORD_ROWS) List<@NotNull @Valid RecordItem> records
    ) {}
    public record ExportRequest(
        @NotNull @Size(min=1, max=MAX_RECORD_ROWS) List<@NotNull @Valid RecordItem> records
    ) {}
    public record ExportEditsRequest(
        @NotNull @Size(max=MAX_RECORD_ROWS) List<@NotNull @Valid RecordItem> edits
    ) {}
    public record ImportResult(String filename, List<RecordItem> records, List<String> warnings) {}
}
