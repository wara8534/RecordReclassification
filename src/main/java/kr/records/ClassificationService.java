package kr.records;

import org.springframework.stereotype.Service;
import java.util.*;
import static kr.records.Models.*;

@Service
public class ClassificationService {
    // Search signals are implementation heuristics, not additional legal grounds.
    private static final List<Criterion> CRITERIA = List.of(
        new Criterion(1, "다른 법령에 따른 비밀·비공개",
            "다른 법률 또는 법률에서 위임한 명령이 비밀·비공개로 정한 정보인지 구체적인 근거를 확인합니다.",
            List.of("법정비밀", "법정 비밀", "비밀로 규정", "비공개로 규정", "비밀유지", "비밀 유지", "과세정보", "납세정보"),
            "단순한 비밀 표시만으로 판단하지 않고 해당 법률·위임명령을 확인해야 합니다.", List.of()),
        new Criterion(2, "국가안전보장·국방·통일·외교",
            "공개로 국가의 중대한 이익을 현저히 해칠 우려가 있는지 확인합니다.",
            List.of("국가안전보장", "국가 안보", "국방", "군사", "통일", "외교", "작전계획"),
            "분야에 해당한다는 사정만으로 비공개를 확정하지 않습니다.", List.of()),
        new Criterion(3, "생명·신체·재산 보호",
            "공개로 국민의 생명·신체 및 재산 보호에 현저한 지장이 생기는지 확인합니다.",
            List.of("신변보호", "신변 보호", "보호대상자", "보안취약점", "보안 취약점", "비밀번호", "출입암호", "생명 위협", "신체 위해"),
            "보호 필요성과 공개 시 발생할 구체적인 지장을 확인해야 합니다.", List.of()),
        new Criterion(4, "재판·수사·형 집행",
            "진행 중인 재판 관련 정보인지, 범죄 예방·수사·공소·형 집행·교정·보안처분의 직무 수행 또는 공정한 재판을 침해할 상당한 이유가 있는지 확인합니다.",
            List.of("재판", "수사", "범죄 예방", "범죄예방", "공소", "형의 집행", "형 집행", "교정", "보안처분"),
            "진행 상태와 구체적 침해 우려를 확인합니다.", List.of()),
        new Criterion(5, "공정한 업무 수행·의사결정",
            "감사·감독·검사·시험·규제·입찰계약·기술개발·인사관리 또는 의사결정·내부검토 공개가 공정한 업무 수행이나 연구·개발에 현저한 지장을 주는지 확인합니다.",
            List.of("감사", "감독", "검사", "시험", "규제", "입찰", "계약", "기술개발", "인사", "채용", "의사결정", "내부검토", "내부 검토"),
            "의사결정·내부검토 사유는 단계와 종료 예정일 안내 및 종료 사실 통지 여부를 확인합니다. 종료만으로 다른 비공개 사유까지 해소되는 것은 아닙니다.",
            List.of("의사결정 완료", "의사결정 종료", "내부검토 완료", "내부검토 종료", "내부 검토 완료", "내부 검토 종료")),
        new Criterion(6, "사생활 침해 우려가 있는 개인정보",
            "성명·주민등록번호 등 개인정보 공개가 사생활의 비밀 또는 자유를 침해할 우려가 있는지 확인합니다.",
            List.of("개인정보", "주민등록번호", "성명", "계좌번호", "전화번호", "연락처", "자택주소", "진료기록", "건강정보"),
            "가: 법령상 열람 가능 정보, 나: 공표 목적이며 사생활을 부당하게 침해하지 않는 정보, 다: 공익·개인 권리구제에 필요한 정보, 라: 직무 수행 공무원의 성명·직위, 마: 공익상 필요한 법령상 위탁·위촉 개인의 성명·직업은 제외 요건을 검토합니다.",
            List.of("법령에 따라 열람", "법령상 열람", "공표 목적", "공표를 목적", "권리구제", "권리 구제", "공익", "공무원", "위탁", "위촉")),
        new Criterion(7, "경영상·영업상 비밀",
            "경영상·영업상 비밀 공개가 법인·단체 또는 개인의 정당한 이익을 현저히 해칠 우려가 있는지 확인합니다.",
            List.of("영업비밀", "영업 비밀", "영업상 비밀", "경영상 비밀", "경영 비밀", "제조공법", "제조 공법", "원가명세"),
            "가: 사업활동 위해로부터 생명·신체·건강 보호에 공개가 필요한 정보, 나: 위법·부당한 사업활동으로부터 재산·생활 보호에 공개가 필요한 정보는 제외 요건을 검토합니다.",
            List.of("생명", "신체", "건강", "위해", "위법", "부당한 사업", "재산 보호", "생활 보호")),
        new Criterion(8, "투기·매점매석 등 특정인 이익",
            "공개로 부동산 투기·매점매석 등 특정인에게 이익 또는 불이익이 생길 우려가 있는지 확인합니다.",
            List.of("부동산 투기", "매점매석", "개발예정지", "개발 예정지", "토지매입", "토지 매입"),
            "구체적인 이익·불이익 발생 우려를 확인해야 합니다.", List.of())
    );
    private static final List<String> EXPIRY_SIGNALS = List.of(
        "비공개 필요성 소멸", "비공개 필요성이 없어", "비공개 사유 소멸", "비공개 기간 만료", "보호기간 만료", "기간의 경과");

    public ClassificationPolicy policy() {
        return new ClassificationPolicy(
            "공공기관의정보공개에관한법률_제9조.pdf",
            "첨부본: 2023. 11. 17. 시행 / 법률 제19408호",
            "/reference/information-disclosure-article9.pdf",
            "첨부 PDF를 사람이 구조화한 고정 기준입니다. 제목·내용의 탐지어로 관련 조항 후보를 찾으며 PDF를 실시간 해석하거나 최신 법령으로 자동 갱신하지 않습니다.",
            CRITERIA,
            List.of(
                "제9조제1항: 공개가 원칙입니다. 탐지어 일치만으로 비공개 요건이 성립하지 않으며, 미탐지 역시 공개 가능성을 입증하지 않습니다.",
                "제9조제2항: 기간 경과 등으로 비공개 필요성이 사라졌는지 확인합니다.",
                "제9조제3항·제4항: 기관별 비공개 세부 기준의 수립·공개 및 정기 점검·개선 사항이 있습니다. 첨부본에 기관별 기준은 포함되어 있지 않습니다.",
                "제6호·제7호 예외, 진행 상태, 침해 우려는 담당자가 원문으로 검토합니다. 탐지어는 구현상 보조 수단이며 법령 문구 자체가 아닙니다.",
                "자동분류 결과를 공개여부·비공개사유·판단근거에 작성합니다. 관련 표현 미탐지 및 내용이 있는 행은 공개, 비공개 관련 표현이 탐지된 행은 비공개, 내용 부족·예외·기간 경과는 검토필요로 작성합니다. 법적 확정 판정은 아니며 부분공개는 별도 근거와 분리 가능성을 검토해 선택합니다."
            ));
    }

    public List<RecordItem> classify(ClassifyRequest request) {
        return request.records().stream().map(this::classifyRow).toList();
    }

    private RecordItem classifyRow(RecordItem row) {
        if (row.reviewed()) return row;
        String text = normalize(row.title() + " " + row.category() + " " + row.content() + " " + row.keywords());
        List<Criterion> matches = CRITERIA.stream()
            .filter(c -> c.keywords().stream().anyMatch(k -> text.contains(normalize(k)))).toList();
        boolean exception = matches.stream().anyMatch(c -> c.exceptionSignals().stream()
            .anyMatch(k -> text.contains(normalize(k))));
        boolean expired = EXPIRY_SIGNALS.stream().anyMatch(k -> text.contains(normalize(k)));
        List<String> reasons = new ArrayList<>();
        reasons.add("첨부 제9조(2023.11.17 시행) 기준 / 규칙 기반 자동분류");
        for (Criterion criterion : matches) {
            String hits = String.join(", ", criterion.keywords().stream()
                .filter(k -> text.contains(normalize(k))).toList());
            reasons.add("제9조제1항제" + criterion.number() + "호 [" + hits + "]: " + criterion.requirement()
                + " " + criterion.exceptionNote());
        }
        if (matches.isEmpty()) reasons.add("제9조제1항 본문의 공개 원칙 적용: 제목·기록물분류·내용·키워드에서 비공개 관련 표현을 탐지하지 못했습니다. 미탐지가 비공개 정보 부재를 보장하지는 않습니다.");
        if (exception) reasons.add("예외 또는 절차 종료 관련 표현이 탐지되어 자동 비공개 추천을 보류합니다. 다른 조항의 해당 여부도 함께 확인해 주세요.");
        if (expired) reasons.add("제9조제2항: 비공개 필요성 소멸 관련 표현이 있습니다. 현재의 보호 필요성을 다시 확인해 주세요.");
        reasons.add("키워드만으로 법적 요건을 확정하지 않습니다. 제9조제2항의 기간 경과와 기관별 세부 기준을 확인한 후 최종 구분을 결정해 주세요.");
        String reason = String.join("\n", reasons);
        if (reason.length() > 2000) reason = reason.substring(0, 1950) + "\n일부 설명 생략: 분류 기준과 PDF 원문에서 전체 요건을 확인해 주세요.";
        boolean insufficient = row.content().isBlank();
        String recommendation = exception || expired || insufficient ? "검토필요"
            : matches.isEmpty() ? "공개" : "비공개";
        if (insufficient) reason = "내용이 비어 있어 공개 여부를 결정할 자료가 부족합니다.\n" + reason;
        if (reason.length() > 2000) reason = reason.substring(0, 1997) + "...";
        String nonDisclosure = recommendation.equals("공개") ? ""
            : String.join("; ", matches.stream().map(c -> "제9조제1항제" + c.number() + "호 (" + c.title() + ")").toList());
        if (recommendation.equals("검토필요")) nonDisclosure = "확정 보류: 내용 부족 또는 예외·기간 경과 확인 필요"
            + (nonDisclosure.isEmpty() ? "" : " / 관련 조항: " + nonDisclosure);
        return new RecordItem(row.id(), row.title(), row.department(), row.year(), row.content(),
            row.originalStatus(), recommendation, recommendation, reason, false,
            row.sourceRow(), row.category(), row.keywords(), nonDisclosure);
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
