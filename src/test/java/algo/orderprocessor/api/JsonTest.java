package algo.orderprocessor.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesFlatObjectWithMixedValueTypes() {
        Map<String, Object> parsed = Json.parse("{\"orderId\":\"o1\",\"premium\":true,\"creationTime\":123}");
        assertEquals("o1", parsed.get("orderId"));
        assertEquals(Boolean.TRUE, parsed.get("premium"));
        assertEquals(123L, parsed.get("creationTime"));
    }

    @Test
    void parsesWhitespaceAndEscapesAndNull() {
        Map<String, Object> parsed = Json.parse("{ \"name\" : \"a\\\"b\" , \"missing\": null }");
        assertEquals("a\"b", parsed.get("name"));
        assertTrue(parsed.containsKey("missing"));
        assertNull(parsed.get("missing"));
    }

    @Test
    void emptyObjectYieldsEmptyMap() {
        assertTrue(Json.parse("{}").isEmpty());
    }

    @Test
    void rejectsNonObjectInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2,3]"));
    }

    @Test
    void writesValuesWithCorrectTypes() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", "o1");
        values.put("premium", true);
        values.put("count", 5L);
        assertEquals("{\"id\":\"o1\",\"premium\":true,\"count\":5}", Json.write(values));
    }

    @Test
    void writeRoundTripsThroughParse() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("orderId", "x-1");
        values.put("premium", false);
        values.put("creationTime", 99L);

        Map<String, Object> parsed = Json.parse(Json.write(values));
        assertEquals(values, parsed);
    }
}

