package kr.records;

import org.apache.poi.ss.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.util.*;
import java.util.function.Function;
import static kr.records.Models.*;

@Service
public class ExcelService {
    static final String TEMPLATE_FILENAME = "기록물등록_문서류·시청각류일괄등록서식.xlsx";
    static final List<String> HEADERS = List.of("순번", "기록물ID", "서고관리번호", "기록물제목", "기록물분류",
        "내용", "키워드", "생산부서", "처리부서", "생산연도", "보존기간", "보존장소", "공개여부", "비공개사유", "판단근거");
    private static final int[] LIMITS = {200, 200, 500, 2000, 500, 10000, 2000, 500, 500, 100, 500, 500};

    public byte[] template() throws IOException {
        // Serve the exact user-provided template, including its original styles.
        try (InputStream input = new ClassPathResource("templates/records-template.xlsx").getInputStream()) {
            return input.readAllBytes();
        }
    }

    private Workbook open(MultipartFile file) throws IOException {
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        if (file.isEmpty()) throw new IllegalArgumentException("빈 파일입니다.");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("파일 크기는 최대 10MB입니다.");
        if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|xls)$"))
            throw new IllegalArgumentException(".xlsx 또는 .xls 파일을 선택해 주세요.");
        try (InputStream input = file.getInputStream()) {
            return WorkbookFactory.create(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다. 암호화 여부와 파일 형식을 확인해 주세요.");
        }
    }

    private Map<String, Integer> columns(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) throw new IllegalArgumentException("첫 번째 행에 서식의 15개 열 이름이 필요합니다.");
        DataFormatter formatter = new DataFormatter(Locale.KOREA);
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : header) {
            String key = formatter.formatCellValue(cell).replaceAll("\\s", "");
            if (HEADERS.contains(key) && columns.put(key, cell.getColumnIndex()) != null)
                throw new IllegalArgumentException("중복된 열 이름입니다: " + key);
        }
        List<String> missing = HEADERS.stream().filter(h -> !columns.containsKey(h)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("서식에 필요한 열이 없습니다: " + String.join(", ", missing)
            + ". 새 입력 양식(서식.xlsx)을 다운로드해 사용해 주세요.");
        return columns;
    }

    private List<RecordItem> readRows(Workbook workbook) {
        if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("워크시트가 없습니다.");
        Sheet sheet = workbook.getSheetAt(0);
        Map<String, Integer> columns = columns(sheet);
        if (sheet.getLastRowNum() > MAX_RECORD_ROWS)
            throw new IllegalArgumentException("최대 " + MAX_RECORD_ROWS_LABEL + "행까지 업로드할 수 있습니다.");
        for (var merged : sheet.getMergedRegions()) {
            if (merged.getLastRow() > 0 && HEADERS.subList(12, 15).stream().anyMatch(h ->
                columns.get(h) >= merged.getFirstColumn() && columns.get(h) <= merged.getLastColumn()))
                throw new IllegalArgumentException("결과 열의 병합 셀을 해제한 후 업로드해 주세요.");
        }
        List<RecordItem> records = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.KOREA);
        Set<String> ids = new HashSet<>();
        for (Row row : sheet) {
            int i = row.getRowNum();
            if (i == 0) continue;
            String[] values = new String[12];
            for (int c = 0; c < 12; c++) {
                Cell cell = row.getCell(columns.get(HEADERS.get(c)));
                if (cell != null && cell.getCellType() == CellType.FORMULA)
                    throw new IllegalArgumentException((i + 1) + "행: 입력 열의 수식은 값으로 붙여넣기 후 업로드해 주세요.");
                values[c] = cell == null ? "" : formatter.formatCellValue(cell).strip();
                if (values[c].length() > LIMITS[c])
                    throw new IllegalArgumentException((i + 1) + "행: " + HEADERS.get(c) + "은 최대 " + LIMITS[c] + "자입니다.");
            }
            if (Arrays.stream(values).allMatch(String::isBlank)) continue;
            if (values[3].isBlank()) throw new IllegalArgumentException((i + 1) + "행: 기록물제목이 비어 있습니다.");
            String id = values[1].isBlank() ? "ROW-" + (i + 1) : values[1];
            if (!ids.add(id)) throw new IllegalArgumentException((i + 1) + "행: 기록물ID가 중복됩니다 (" + id + ").");
            Cell old = row.getCell(columns.get("공개여부"));
            records.add(new RecordItem(id, values[3], values[7], values[9], values[5],
                old == null ? "" : formatter.formatCellValue(old), "검토필요", "검토필요", "", false,
                i + 1, values[4], values[6], "", null, List.of(), "", values[0], "", ""));
        }
        if (records.isEmpty()) throw new IllegalArgumentException("불러올 기록물이 없습니다. 서식의 2행부터 내용을 작성해 주세요.");
        return records;
    }

    public ImportResult read(MultipartFile file) {
        try (Workbook workbook = open(file)) {
            boolean registration = RegistrationWorkbook.matches(workbook);
            List<RecordItem> rows = registration ? new RegistrationWorkbook(workbook).read() : readRows(workbook);
            List<String> warnings = new ArrayList<>();
            if (registration) {
                warnings.add("기록물철·기록물건 시트를 분류했습니다. 검토필요 건은 엑셀의 기존 공개 관련 값을 유지합니다.");
            } else {
                if (workbook.getNumberOfSheets() > 1) warnings.add("첫 번째 시트를 분류하며, 다른 시트는 결과 파일에 보존합니다.");
                if (rows.stream().anyMatch(r -> !r.originalStatus().isBlank()))
                    warnings.add("기존 공개여부·비공개사유·판단근거는 이번 분류 결과로 대체됩니다.");
            }
            return new ImportResult(Objects.requireNonNullElse(file.getOriginalFilename(), "records.xlsx"), rows, warnings);
        } catch (IOException e) {
            throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다.");
        }
    }

    public byte[] write(MultipartFile original, List<RecordItem> records) throws IOException {
        return write(original, records, false);
    }

    public byte[] write(MultipartFile original, List<RecordItem> records, boolean reviewedOnly) throws IOException {
        return writeResults(original, reviewedOnly, source -> verifiedResults(source, records));
    }

    byte[] writeEdits(MultipartFile original, List<RecordItem> edits, boolean reviewedOnly,
                      Function<ClassifyRequest, List<RecordItem>> classify) throws IOException {
        return writeResults(original, reviewedOnly, source -> {
            var defaults = reviewedOnly ? source : classify.apply(new ClassifyRequest(source));
            return applyEdits(defaults, edits);
        });
    }

    private byte[] writeResults(MultipartFile original, boolean reviewedOnly,
                                Function<List<RecordItem>, List<RecordItem>> prepareResults) throws IOException {
        try (Workbook workbook = open(original); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (RegistrationWorkbook.matches(workbook)) {
                var registration = new RegistrationWorkbook(workbook);
                registration.write(resultsToWrite(prepareResults.apply(registration.read()), reviewedOnly));
            } else {
                var results = resultsToWrite(prepareResults.apply(readRows(workbook)), reviewedOnly);
                if (!results.isEmpty()) writeLegacy(workbook, results);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private List<RecordItem> resultsToWrite(List<RecordItem> results, boolean reviewedOnly) {
        return results.stream().filter(row -> {
            if (reviewedOnly) return row.reviewed() && !"검토필요".equals(row.decision());
            return row.reviewed() || !(disclosureChanged(row.originalStatus(), row.recommendation())
                || disclosureChanged(row.originalStatus(), row.decision()));
        }).toList();
    }

    private record SourceLocation(SourceSheet sheet, int row) {
        SourceLocation(RecordItem record) { this(record.sourceSheet(), record.sourceRow()); }
    }

    private List<RecordItem> verifiedResults(List<RecordItem> source, List<RecordItem> records) {
        if (source.size() != records.size())
            throw new IllegalArgumentException("원본과 결과의 기록물 수가 다릅니다. 원본 파일을 다시 업로드해 주세요.");
        return applyEdits(source, records);
    }

    private List<RecordItem> applyEdits(List<RecordItem> source, List<RecordItem> edits) {
        if (edits.isEmpty()) return source;
        Map<SourceLocation, RecordItem> results = new HashMap<>();
        for (RecordItem row : edits) {
            if (results.put(new SourceLocation(row), row) != null)
                throw new IllegalArgumentException("결과에 중복된 시트와 행 번호가 있습니다.");
            if ((row.decision().equals("비공개") || row.decision().equals("부분공개")) && row.nonDisclosureReason().isBlank())
                throw new IllegalArgumentException("비공개·부분공개 기록물은 비공개사유가 필요합니다.");
            if (row.reason().isBlank()) throw new IllegalArgumentException("판단근거를 입력해 주세요.");
        }
        List<RecordItem> ordered = new ArrayList<>(source.size());
        for (RecordItem input : source) {
            RecordItem result = results.remove(new SourceLocation(input));
            if (result == null) {
                ordered.add(input);
                continue;
            }
            if (!result.id().equals(input.id()) || !result.title().equals(input.title())
                || !result.content().equals(input.content()) || !result.category().equals(input.category())
                || !result.keywords().equals(input.keywords()) || !result.originalStatus().equals(input.originalStatus()))
                throw new IllegalArgumentException("분류 결과가 원본 시트와 행에 일치하지 않습니다. 원본 파일을 다시 업로드해 주세요.");
            ordered.add(result);
        }
        if (!results.isEmpty())
            throw new IllegalArgumentException("분류 결과가 원본 시트와 행에 일치하지 않습니다. 원본 파일을 다시 업로드해 주세요.");
        return ordered;
    }

    private void writeLegacy(Workbook workbook, List<RecordItem> records) {
        Sheet sheet = workbook.getSheetAt(0);
        Map<String, Integer> columns = columns(sheet);
        Map<Short, CellStyle> styles = new HashMap<>();
        for (RecordItem result : records) {
            Row row = sheet.getRow(result.sourceRow() - 1);
            String[] values = {result.decision(), result.decision().equals("공개") ? "" : result.nonDisclosureReason(), result.reason()};
            for (int c = 0; c < 3; c++) {
                Cell cell = row.getCell(columns.get(HEADERS.get(12 + c)), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                short originalStyle = cell.getCellStyle().getIndex();
                CellStyle style = styles.computeIfAbsent(originalStyle, key -> {
                    CellStyle wrapped = workbook.createCellStyle();
                    wrapped.cloneStyleFrom(workbook.getCellStyleAt(key));
                    wrapped.setWrapText(true);
                    wrapped.setVerticalAlignment(VerticalAlignment.TOP);
                    return wrapped;
                });
                cell.setBlank(); // Replace any old formula rather than updating its cached value.
                cell.setCellValue(values[c]);
                cell.setCellStyle(style);
            }
            row.setHeightInPoints(Math.max(row.getHeightInPoints(), 90));
        }
        sheet.setColumnWidth(columns.get("공개여부"), 16 * 256);
        sheet.setColumnWidth(columns.get("비공개사유"), 48 * 256);
        sheet.setColumnWidth(columns.get("판단근거"), 100 * 256);
    }
}
