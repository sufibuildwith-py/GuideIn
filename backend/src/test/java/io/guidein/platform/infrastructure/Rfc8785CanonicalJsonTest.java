package io.guidein.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class Rfc8785CanonicalJsonTest {
    private final Rfc8785CanonicalJson canonicalizer =
            new Rfc8785CanonicalJson(JsonMapper.builder().build());

    @Test
    void followsOfficialRfc8785PrimitiveAndOrderingVector() {
        String input = """
                {"numbers":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001],
                 "literals":[null,true,false]}
                """;
        String expected = "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27]}";
        assertThat(new String(canonicalizer.canonicalizeJson(input), StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @Test
    void mapInsertionOrderCannotChangeCanonicalBytes() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", 1);
        first.put("a", 2);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 2);
        second.put("z", 1);
        assertThat(canonicalizer.canonicalize(first)).isEqualTo(canonicalizer.canonicalize(second));
    }
}
