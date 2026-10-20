package kr.records;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class Models {
    private Models() {}
    public static final String STATUS = "공개|부분공개|비공개|검토필요";

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
        @NotNull @Size(max=2000) String nonDisclosureReason
    ) {}
    public record Criterion(int number, String title, String requirement, List<String> keywords,
                            String exceptionNote, List<String> exceptionSignals) {}
    public record ClassificationPolicy(String source, String version, String sourceUrl,
                                       String method, List<Criterion> criteria, List<String> notes) {}
    public record ClassifyRequest(
        @NotNull @Size(min=1, max=5000) List<@NotNull @Valid RecordItem> records
    ) {}
    public record ExportRequest(
        @NotNull @Size(min=1, max=5000) List<@NotNull @Valid RecordItem> records
    ) {}
    public record ImportResult(String filename, List<RecordItem> records, List<String> warnings) {}
}
