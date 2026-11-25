package kr.records.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static kr.records.ai.AiReviewModels.*;

final class PatternSensitiveDataScreen implements AiReviewPipeline.SensitiveDataScreen {
    private record Rule(String type, Pattern pattern) {
        Rule(String type, String expression) { this(type, Pattern.compile(expression)); }
    }

    private static final List<Rule> RULES = List.of(
        new Rule("주민등록번호", "(?<![0-9])[0-9]{6}\\s*[-–]?\\s*[1-8][0-9]{6}(?![0-9])"),
        new Rule("이메일", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"),
        new Rule("전화번호", "(?<![0-9])(?:\\+82[ .-]?|0)(?:1[016789]|2|[3-6][1-5]|70)[ .-]?[0-9]{3,4}[ .-]?[0-9]{4}(?![0-9])"),
        new Rule("계좌번호", "(?<=계좌번호)\\s*[:：]?\\s*[0-9](?:[0-9 -]{6,24})[0-9](?![0-9])")
    );

    @Override
    public MaskedInput prepare(Metadata metadata, List<Evidence> evidence) {
        var maskedMetadata = new Metadata(mask(metadata.title(), null, null), mask(metadata.department(), null, null),
            mask(metadata.year(), null, null), mask(metadata.category(), null, null), mask(metadata.keywords(), null, null),
            mask(metadata.originalStatus(), null, null), metadata.sourceType());
        List<SensitiveSignal> signals = new ArrayList<>();
        var maskedEvidence = evidence.stream()
            .map(segment -> new MaskedEvidence(segment.id(), mask(segment.text(), segment.id(), signals))).toList();
        return new MaskedInput(maskedMetadata, maskedEvidence, List.copyOf(signals));
    }

    private String mask(String value, String evidenceId, List<SensitiveSignal> signals) {
        for (Rule rule : RULES) {
            var matches = rule.pattern().matcher(value);
            if (matches.find()) {
                if (signals != null) signals.add(new SensitiveSignal(evidenceId, rule.type()));
                value = matches.replaceAll(" [" + rule.type() + " 가림] ");
            }
        }
        return value;
    }
}
