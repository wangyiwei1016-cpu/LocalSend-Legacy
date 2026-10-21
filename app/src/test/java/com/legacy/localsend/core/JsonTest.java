package com.legacy.localsend.core;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Json 解析/序列化单元测试（v2 协议的核心数据结构都靠它）。 */
public class JsonTest {

    @Test
    public void parsesObjectWithAllPrimitiveTypes() throws Exception {
        String text = "{\"a\":1,\"b\":-2,\"c\":1.5,\"d\":\"x\",\"e\":true,\"f\":null,\"g\":[],\"h\":{}}";
        Map<String, Object> map = Json.parseObject(text);
        assertEquals(Long.valueOf(1), map.get("a"));
        assertEquals(Long.valueOf(-2), map.get("b"));
        assertEquals(Double.valueOf(1.5), map.get("c"));
        assertEquals("x", map.get("d"));
        assertEquals(Boolean.TRUE, map.get("e"));
        assertTrue(map.containsKey("f"));
        assertNull(map.get("f"));
        assertTrue(map.get("g") instanceof List);
        assertTrue(map.get("h") instanceof Map);
    }

    @Test
    public void parsesArrayAndNestedValues() throws Exception {
        Map<String, Object> map = Json.parseObject(
                "{\"files\":{\"id1\":{\"fileName\":\"a b.png\",\"size\":123}}}");
        Map<String, Object> files = Json.optObject(map, "files");
        Map<String, Object> file = Json.optObject(files, "id1");
        assertEquals("a b.png", Json.optString(file, "fileName", null));
        assertEquals(123L, Json.optLong(file, "size", 0L));
    }

    @Test
    public void parsesEscapesAndUnicode() throws Exception {
        String text = "{\"s\":\"line\\nquote\\\"backslash\\\\tab\\tunicode\\u4e2d\"}";
        Map<String, Object> map = Json.parseObject(text);
        assertEquals("line\nquote\"backslash\\tab\tunicode中", map.get("s"));
    }

    @Test
    public void parsesUtf8Chinese() throws Exception {
        String text = "{\"alias\":\"老手机\",\"deviceModel\":\"诺基亚\"}";
        Map<String, Object> map = Json.parseObject(text);
        assertEquals("老手机", map.get("alias"));
        assertEquals("诺基亚", map.get("deviceModel"));
    }

    @Test
    public void rejectsInvalidJson() {
        String[] invalid = new String[]{"", "{", "{\"a\":}", "[1,]", "{\"a\":1} garbage", "tru", "{}"};
        for (int i = 0; i < invalid.length; i++) {
            try {
                Json.parse(invalid[i]);
                if (i != invalid.length - 1) {
                    fail("应当解析失败: " + invalid[i]);
                }
            } catch (Json.JsonException expected) {
                // ok
            }
        }
    }

    @Test
    public void stringifyRoundTrips() throws Exception {
        Map<String, Object> map = Json.newObject();
        map.put("alias", "设备 A");
        map.put("port", Long.valueOf(53317));
        map.put("download", Boolean.FALSE);
        map.put("ratio", Double.valueOf(0.75));
        List<Object> list = Json.newArray();
        list.add("x");
        list.add(Long.valueOf(2));
        map.put("list", list);

        String json = Json.stringify(map);
        Map<String, Object> parsed = Json.parseObject(json);
        assertEquals("设备 A", parsed.get("alias"));
        assertEquals(Long.valueOf(53317), parsed.get("port"));
        assertEquals(Boolean.FALSE, parsed.get("download"));
        assertEquals(Double.valueOf(0.75), parsed.get("ratio"));
        List<?> parsedList = (List<?>) parsed.get("list");
        assertEquals("x", parsedList.get(0));
        assertEquals(Long.valueOf(2), parsedList.get(1));
    }

    @Test
    public void optionalsUseDefaults() throws Exception {
        Map<String, Object> map = Json.parseObject("{\"port\":\"1234\",\"flag\":\"true\"}");
        assertEquals(1234, Json.optInt(map, "port", 53317));
        assertEquals(99, Json.optInt(map, "missing", 99));
        assertTrue(Json.optBoolean(map, "flag", false));
        assertEquals("def", Json.optString(map, "missing", "def"));
        assertNull(Json.optObject(map, "missing"));
        assertNull(Json.optArray(map, "missing"));
    }

    @Test
    public void deviceInfoRoundTripsThroughJson() throws Exception {
        DeviceInfo info = new DeviceInfo("老手机", "Nokia N8", "abcdef0123456789", 53317);
        info.protocol = "http";
        info.download = false;
        Map<String, Object> parsed = Json.parseObject(Json.stringify(info.toMap()));
        DeviceInfo back = DeviceInfo.fromMap(parsed);
        assertEquals("老手机", back.alias);
        assertEquals("Nokia N8", back.deviceModel);
        assertEquals("abcdef0123456789", back.fingerprint);
        assertEquals(53317, back.port);
        assertEquals("http", back.protocol);
        assertEquals("2.0", back.version);
    }

    @Test
    public void sanitizeFileNameStripsPathTraversal() {
        String[] inputs = new String[]{
                "../../evil.txt",
                "..\\..\\windows\\system32\\evil.dll",
                "/etc/passwd",
                "C:\\temp\\x.txt",
                "   ",
                null,
                "正常 文件名.txt"
        };
        for (int i = 0; i < inputs.length; i++) {
            String sanitized = FileMeta.sanitizeFileName(inputs[i]);
            assertTrue("清洗结果不应包含路径分隔符: " + sanitized,
                    sanitized.indexOf('/') < 0 && sanitized.indexOf('\\') < 0);
            assertTrue("清洗结果不应以点开头: " + sanitized, !sanitized.startsWith("."));
            assertTrue("清洗结果不应为空: " + inputs[i], sanitized.length() > 0);
        }
        assertEquals("file", FileMeta.sanitizeFileName(null));
        assertEquals("file", FileMeta.sanitizeFileName("   "));
        assertEquals("正常 文件名.txt", FileMeta.sanitizeFileName("正常 文件名.txt"));
    }
}
