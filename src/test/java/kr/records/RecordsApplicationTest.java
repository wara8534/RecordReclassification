package kr.records;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static kr.records.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RecordsApplicationTest {
    @Autowired ClassificationService classifier;
    @Autowired ExcelService excel;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private RecordItem item(String title, String content, boolean reviewed) {
        return new RecordItem("001", title, "기록관리팀", "2025", content, "",
            "검토필요", reviewed ? "공개" : "검토필요", "검토 의견", reviewed, 2, "", "", "");
    }
    private MockMultipartFile fixture(boolean legacy, int count) throws IOException {
        try (Workbook book = legacy ? new HSSFWorkbook() : WorkbookFactory.create(new ByteArrayInputStream(excel.template()));
             var output = new ByteArrayOutputStream()) {
            Sheet sheet = legacy ? book.createSheet("Sheet1") : book.getSheetAt(0);
            Row header = sheet.getRow(0) == null ? sheet.createRow(0) : sheet.getRow(0);
            for (int c = 0; c < 15; c++) {
                header.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(ExcelService.HEADERS.get(c));
            }
            header.createCell(15).setCellValue("보존할 추가 열");
            for (int i = 1; i <= count; i++) {
                Row row = sheet.createRow(i);
                String[] values = {String.valueOf(i), "ID-" + i, "서고-001", "기록 " + i, "행정",
                    i % 2 == 0 ? "주민등록번호가 포함된 명단" : "월별 시설 이용 집계",
                    "", "기록관리팀", "처리부서", "2025", "영구", "제1서고", "기존값", "이전사유", "이전근거"};
                for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
                row.createCell(15).setCellFormula("1+1");
            }
            sheet.createFreezePane(3, 1);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 16, 17));
            Sheet other = book.createSheet("보존할 시트");
            other.createRow(0).createCell(0).setCellValue("원본 보존");
            book.write(output);
            return new MockMultipartFile("file", legacy ? "input.xls" : "input.xlsx", "application/octet-stream", output.toByteArray());
        }
    }
    private List<RecordItem> classifyFile(MockMultipartFile file) {
        return classifier.classify(new ClassifyRequest(excel.read(file).records()));
    }
    @Test void templateIsExactlyTheSuppliedWorkbook() throws Exception {
        assertThat(excel.template()).isEqualTo(Files.readAllBytes(Path.of("src/main/resources/templates/records-template.xlsx")));
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(excel.template()))) {
            assertThat(book.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short) 15);
            for (int i = 0; i < 15; i++)
                assertThat(book.getSheetAt(0).getRow(0).getCell(i).getStringCellValue()).isEqualTo(ExcelService.HEADERS.get(i));
        }
    }
    @Test void automaticPublicPrivateAndInsufficientDecisions() {
        var rows = List.of(item("통계자료", "월별 시설 이용 집계", false),
            item("개인정보", "영업비밀이 포함된 자료", false), item("제목만 있음", "", false));
        var result = classifier.classify(new ClassifyRequest(rows));
        assertThat(result.get(0).decision()).isEqualTo("공개");
        assertThat(result.get(0).nonDisclosureReason()).isEmpty();
        assertThat(result.get(1).decision()).isEqualTo("비공개");
        assertThat(result.get(1).nonDisclosureReason()).contains("제6호", "제7호");
        assertThat(result.get(1).reason()).contains("개인정보", "영업비밀");
        assertThat(result.get(2).decision()).isEqualTo("검토필요");
    }
    @Test void allEightClausesAndExceptionsAreApplied() {
        List<String> examples = List.of("법정비밀", "국방 작전계획", "신변보호", "진행 중인 재판",
            "입찰계약", "주민등록번호", "영업상 비밀", "부동산 투기");
        for (int i = 0; i < examples.size(); i++) {
            var result = classifier.classify(new ClassifyRequest(List.of(item(examples.get(i), "상세 내용", false)))).get(0);
            assertThat(result.reason()).contains("제9조제1항제" + (i + 1) + "호");
            assertThat(result.decision()).isEqualTo("비공개");
        }
        for (String content : List.of("직무 수행 공무원의 성명·직위", "영업비밀과 위법 사업활동",
            "내부검토 종료", "국방 자료 비공개 기간 만료"))
            assertThat(classifier.classify(new ClassifyRequest(List.of(item("검토 대상", content, false)))).get(0).decision())
                .as(content).isEqualTo("검토필요");
    }
    @Test void classificationIncludesCategoryAndKeywordsAndPreservesReview() {
        var row = new RecordItem("001", "자료", "", "2025", "상세 내용", "", "검토필요", "검토필요", "", false,
            2, "국방", "주민등록번호", "");
        assertThat(classifier.classify(new ClassifyRequest(List.of(row))).get(0).nonDisclosureReason())
            .contains("제2호", "제6호");
        var reviewed = item("개인정보", "영업비밀", true);
        assertThat(classifier.classify(new ClassifyRequest(List.of(reviewed))).get(0)).isEqualTo(reviewed);
    }
    @Test void rewritesOnlyResultValuesAndPreservesWorkbookInBothFormats() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            var file = fixture(legacy, 2);
            byte[] result = excel.write(file, classifyFile(file));
            try (Workbook source = WorkbookFactory.create(file.getInputStream());
                 Workbook output = WorkbookFactory.create(new ByteArrayInputStream(result))) {
                assertThat(output.getNumberOfSheets()).isEqualTo(2);
                assertThat(output.getSheetAt(1).getRow(0).getCell(0).getStringCellValue()).isEqualTo("원본 보존");
                Sheet sheet = output.getSheetAt(0);
                assertThat(sheet.getNumMergedRegions()).isEqualTo(1);
                assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
                for (int r = 1; r <= 2; r++) {
                    for (int c = 0; c < 12; c++) {
                        assertThat(sheet.getRow(r).getCell(c).toString()).isEqualTo(source.getSheetAt(0).getRow(r).getCell(c).toString());
                        assertThat(sheet.getRow(r).getCell(c).getCellStyle().getIndex())
                            .isEqualTo(source.getSheetAt(0).getRow(r).getCell(c).getCellStyle().getIndex());
                    }
                    assertThat(sheet.getRow(r).getCell(15).getCellFormula()).isEqualTo("1+1");
                }
                assertThat(sheet.getRow(1).getCell(12).getStringCellValue()).isEqualTo("공개");
                assertThat(sheet.getRow(1).getCell(13).getStringCellValue()).isEmpty();
                assertThat(sheet.getRow(2).getCell(12).getStringCellValue()).isEqualTo("비공개");
                assertThat(sheet.getRow(2).getCell(13).getStringCellValue()).contains("제6호");
                assertThat(sheet.getRow(2).getCell(14).getStringCellValue()).contains("주민등록번호");
                assertThat(sheet.getRow(2).getCell(14).getCellStyle().getWrapText()).isTrue();
            }
        }
    }
    @Test void outputTextCannotBecomeFormulaAndReviewIsExported() throws Exception {
        var file = fixture(false, 1);
        var row = classifyFile(file).get(0);
        var edited = new RecordItem(row.id(), row.title(), row.department(), row.year(), row.content(), row.originalStatus(),
            row.recommendation(), "부분공개", "=1+1", true, row.sourceRow(), row.category(), row.keywords(), "개인 식별정보 분리");
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(excel.write(file, List.of(edited))))) {
            Row output = book.getSheetAt(0).getRow(1);
            assertThat(output.getCell(12).getStringCellValue()).isEqualTo("부분공개");
            assertThat(output.getCell(14).getCellType()).isEqualTo(CellType.STRING);
            assertThat(output.getCell(14).getStringCellValue()).isEqualTo("=1+1");
        }
    }
    @Test void mismatchedOrMissingResultsCannotOverwriteWrongRows() throws Exception {
        var file = fixture(false, 2);
        var rows = classifyFile(file);
        assertThatThrownBy(() -> excel.write(file, List.of(rows.get(0)))).hasMessageContaining("기록물 수");
        assertThatThrownBy(() -> excel.write(file, List.of(rows.get(0), rows.get(0)))).hasMessageContaining("중복");
    }
    @Test void inputFormulasMissingHeadersAndDuplicateIdsAreRejected() throws Exception {
        var file = fixture(false, 2);
        try (Workbook book = WorkbookFactory.create(file.getInputStream()); var bytes = new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(1).getCell(5).setCellFormula("1+1");
            book.write(bytes);
            assertThatThrownBy(() -> excel.read(new MockMultipartFile("file", "bad.xlsx", null, bytes.toByteArray())))
                .hasMessageContaining("수식");
        }
        try (Workbook book = WorkbookFactory.create(file.getInputStream()); var bytes = new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(0).getCell(14).setCellValue("잘못된열");
            book.write(bytes);
            assertThatThrownBy(() -> excel.read(new MockMultipartFile("file", "bad.xlsx", null, bytes.toByteArray())))
                .hasMessageContaining("판단근거");
        }
        try (Workbook book = WorkbookFactory.create(file.getInputStream()); var bytes = new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(2).getCell(1).setCellValue("ID-1");
            book.write(bytes);
            assertThatThrownBy(() -> excel.read(new MockMultipartFile("file", "bad.xlsx", null, bytes.toByteArray())))
                .hasMessageContaining("중복");
        }
    }
    @Test void blankRowsKeepTheirOriginalPositions() throws Exception {
        var file = fixture(false, 3);
        try (Workbook book = WorkbookFactory.create(file.getInputStream()); var bytes = new ByteArrayOutputStream()) {
            book.getSheetAt(0).removeRow(book.getSheetAt(0).getRow(2));
            book.write(bytes);
            var sparse = new MockMultipartFile("file", "blank.xlsx", null, bytes.toByteArray());
            var records = classifyFile(sparse);
            assertThat(records).extracting(RecordItem::sourceRow).containsExactly(2, 4);
            try (Workbook output = WorkbookFactory.create(new ByteArrayInputStream(excel.write(sparse, records)))) {
                assertThat(output.getSheetAt(0).getRow(2)).isNull();
                assertThat(output.getSheetAt(0).getRow(3).getCell(12).getStringCellValue()).isEqualTo("공개");
            }
        }
    }
    @Test void fullApiFlowWrites500RowsIntoTheUploadedTemplate() throws Exception {
        var file = fixture(false, 500);
        var imported = mvc.perform(multipart("/api/records/import").file(file))
            .andExpect(status().isOk()).andExpect(jsonPath("$.records.length()").value(500))
            .andExpect(jsonPath("$.records[0].decision").value("공개")).andReturn();
        var result = mapper.readValue(imported.getResponse().getContentAsByteArray(), ImportResult.class);
        var json = new MockMultipartFile("records", "records.json", "application/json",
            mapper.writeValueAsBytes(new ExportRequest(result.records())));
        byte[] bytes = mvc.perform(multipart("/api/records/export").file(file).file(json))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsByteArray();
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(500);
            assertThat(book.getSheetAt(0).getRow(500).getCell(13).getStringCellValue()).contains("제6호");
            assertThat(book.getSheetAt(0).getRow(499).getCell(12).getStringCellValue()).isEqualTo("공개");
        }
        Path output = Path.of("target/test-output");
        Files.createDirectories(output);
        Files.write(output.resolve("500-records-input.xlsx"), file.getBytes());
        Files.write(output.resolve("500-records-result.xlsx"), bytes);
    }
    @Test void oneStepProcessAndExactTemplateDownload() throws Exception {
        mvc.perform(get("/api/records/template")).andExpect(status().isOk()).andExpect(content().bytes(excel.template()));
        for (boolean legacy : List.of(false, true)) {
            mvc.perform(multipart("/api/records/process").file(fixture(legacy, 2)))
                .andExpect(status().isOk()).andExpect(content().contentType(legacy ? "application/vnd.ms-excel"
                    : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        }
    }
    @Test void invalidAndCustomRulesAreRejected() throws Exception {
        mvc.perform(get("/api/classification-policy")).andExpect(status().isOk()).andExpect(jsonPath("$.criteria.length()").value(8));
        mvc.perform(post("/api/records/classify").contentType("application/json").content("{\"records\":[]}"))
            .andExpect(status().isBadRequest());
        var payload = mapper.createObjectNode();
        payload.set("records", mapper.valueToTree(List.of(item("자료", "내용", false))));
        payload.putArray("rules");
        mvc.perform(post("/api/records/classify").contentType("application/json").content(payload.toString()))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/records/import").file(new MockMultipartFile("file", "bad.txt", null, new byte[]{1})))
            .andExpect(status().isBadRequest());
    }
}
