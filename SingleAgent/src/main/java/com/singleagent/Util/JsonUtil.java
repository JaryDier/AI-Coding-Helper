package com.singleagent.Util;

import org.apache.commons.lang3.StringUtils;

public class JsonUtil {

    public static String normalizeJson(String content) {
        String json = StringUtils.trimToEmpty(content);
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*", "");
            json = json.replaceFirst("```$", "");
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return json.substring(start, end + 1).trim();
        }
        return json;
    }
}
