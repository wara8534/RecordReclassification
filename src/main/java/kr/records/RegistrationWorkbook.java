package kr.records;

import org.apache.poi.ss.usermodel.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static kr.records.Models.*;

final class RegistrationWorkbook {
    private static final int HEADER_INDEX = 3;
    private static final int FIRST_DATA_INDEX = 4;
    private static final List<String> RESULT_FIELDS = List.of("openDivNm", "openGrade", "openLimitPart", "partOpenRsn");

    private final Map<SourceSheet, SheetLayout> layouts = new HashMap<>();
    private final DataFormatter formatter = new DataFormatter(Locale.KOREA);

    static boolean matches(Workbook workbook) {
        return workbook.getSheet(SourceSheet.FOLDER.sheetName()) != null
            || workbook.getSheet(SourceSheet.ITEM.sheetName()) != null;
    }

    RegistrationWorkbook(Workbook workbook) {
        for (SourceSheet type : SourceSheet.values()) {
            int sheetIndex = workbook.getSheetIndex(type.sheetName());
            if (sheetIndex != type.ordinal()) {
                throw new IllegalArgumentException("기록물철·기록물건 시트의 이름과 순서를 등록 서식과 동일하게 유지해 주세요.");
            }
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            Map<String, Integer> columns = columns(sheet, type);
            for (var merged : sheet.getMergedRegions()) {
                if (merged.getLastRow() >= FIRST_DATA_INDEX && RESULT_FIELDS.stream().anyMatch(key ->
                    columns.get(key) >= merged.getFirstColumn() && columns.get(key) <= merged.getLastColumn())) {
                    throw new IllegalArgumentException(sheet.getSheetName() + ": 결과 열의 병합 셀을 해제해 주세요.");
                }
            }
            layouts.put(type, new SheetLayout(sheet, columns));
        }
    }

    private Map<String, Integer> columns(Sheet sheet, SourceSheet type) {
        List<String> fields = new ArrayList<>(RESULT_FIELDS);
        fields.addAll(List.of("sno", "title", "keywd"));
        fields.addAll(type == SourceSheet.FOLDER
            ? List.of("folderId", "creatOrgNm", "creatYyyyFrom", "creatYyyyTo", "typeDivNm", "detailTypeNm", "descInfo")
            : List.of("recordId", "folderSno", "batchFileRowSno", "creatYmd", "recordTypeNm", "recordDetailTypeNm", "mainCont"));
        Map<String, Integer> columns = new HashMap<>();
        Row header = sheet.getRow(HEADER_INDEX);
        if (header != null) {
            for (Cell cell : header) {
                String key = formatter.formatCellValue(cell).strip();
                if (fields.contains(key) && columns.putIfAbsent(key, cell.getColumnIndex()) != null) {
                    throw new IllegalArgumentException(sheet.getSheetName() + ": 4행에 중복된 필드가 있습니다 (" + key + ").");
                }
            }
        }
        List<String> missing = fields.stream().filter(field -> !columns.containsKey(field)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(sheet.getSheetName() + ": 등록 서식의 4행에 필요한 필드가 없습니다 ("
                + String.join(", ", missing) + ").");
        }
        return columns;
    }

    List<RecordItem> read() {
        List<RecordItem> records = new ArrayList<>();
        for (SourceSheet type : SourceSheet.values()) {
            SheetLayout layout = layouts.get(type);
            if (layout.sheet().getLastRowNum() >= FIRST_DATA_INDEX + MAX_RECORD_ROWS) {
                throw new IllegalArgumentException("등록 서식의 5행부터 시트별 최대 " + MAX_RECORD_ROWS_LABEL + "개 행을 사용할 수 있습니다.");
            }
            Set<String> ids = new HashSet<>();
            for (Row row : layout.sheet()) {
                if (row.getRowNum() < FIRST_DATA_INDEX || !hasValues(row)) continue;
                RecordItem record = readRecord(type, layout, row);
                if (!ids.add(record.id())) throw invalid(row, "기록물 ID가 중복됩니다 (" + record.id() + ").");
                records.add(record);
                if (records.size() > MAX_RECORD_ROWS) {
                    throw new IllegalArgumentException("기록물철과 기록물건을 합쳐 최대 " + MAX_RECORD_ROWS_LABEL + "건까지 업로드할 수 있습니다.");
                }
            }
        }
        if (records.isEmpty()) throw new IllegalArgumentException("불러올 기록물이 없습니다. 등록 서식의 5행부터 작성해 주세요.");
        return records;
    }

    private boolean hasValues(Row row) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) return true;
        }
        return false;
    }

    private RecordItem readRecord(SourceSheet type, SheetLayout layout, Row row) {
        boolean folder = type == SourceSheet.FOLDER;
        String title = value(layout, row, "title", 2000);
        if (title.isBlank()) throw invalid(row, (folder ? "제목" : "건제목") + "이 비어 있습니다.");
        String id = value(layout, row, folder ? "folderId" : "recordId", 200);
        if (id.isBlank()) id = "ROW-" + (row.getRowNum() + 1);
        String department = folder ? value(layout, row, "creatOrgNm", 500) : "";
        String year = folder ? joined(value(layout, row, "creatYyyyFrom", 40), value(layout, row, "creatYyyyTo", 40), "~")
            : value(layout, row, "creatYmd", 100).replaceFirst("^(\\d{4})[-./]?\\d{2}[-./]?\\d{2}$", "$1");
        String category = joined(value(layout, row, folder ? "typeDivNm" : "recordTypeNm", 240),
            value(layout, row, folder ? "detailTypeNm" : "recordDetailTypeNm", 240), " / ");
        return new RecordItem(id, title, department, year,
            value(layout, row, folder ? "descInfo" : "mainCont", 10000), value(layout, row, "openDivNm", 100),
            "검토필요", "검토필요", "", false, row.getRowNum() + 1, category,
            value(layout, row, "keywd", 2000), "", type, List.of(), "", value(layout, row, "sno", 200),
            folder ? "" : value(layout, row, "folderSno", 200), folder ? "" : value(layout, row, "batchFileRowSno", 200));
    }

    private String value(SheetLayout layout, Row row, String field, int limit) {
        Cell cell = row.getCell(layout.columns().get(field));
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) throw invalid(row, field + "에 입력한 수식은 값으로 붙여넣어 주세요.");
        String value = formatter.formatCellValue(cell).strip();
        if (value.length() > limit) throw invalid(row, field + "은 최대 " + limit + "자입니다.");
        return value;
    }

    private String joined(String first, String second, String separator) {
        if (first.isBlank()) return second;
        if (second.isBlank() || first.equals(second)) return first;
        return first + separator + second;
    }

    private IllegalArgumentException invalid(Row row, String message) {
        return new IllegalArgumentException(row.getSheet().getSheetName() + " " + (row.getRowNum() + 1) + "행: " + message);
    }

    void write(List<RecordItem> results) {
        for (RecordItem result : results) {
            if (result.decision().equals("검토필요")) continue;
            SheetLayout layout = layouts.get(result.sourceSheet());
            Row row = layout.sheet().getRow(result.sourceRow() - 1);
            boolean publicRecord = result.decision().equals("공개");
            String grades = publicRecord ? "" : result.nonDisclosureGrades().stream()
                .distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
            String restricted = result.decision().equals("부분공개") ? result.restrictedPart() : "";
            String[] values = {result.decision(), grades, restricted, publicRecord ? "" : result.nonDisclosureReason()};
            for (int i = 0; i < RESULT_FIELDS.size(); i++) {
                Cell cell = row.getCell(layout.columns().get(RESULT_FIELDS.get(i)), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                cell.setBlank();
                cell.setCellValue(values[i]);
            }
        }
    }

    private record SheetLayout(Sheet sheet, Map<String, Integer> columns) {}
}
