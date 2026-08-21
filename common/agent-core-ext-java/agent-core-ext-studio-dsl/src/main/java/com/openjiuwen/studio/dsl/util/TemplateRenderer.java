package com.openjiuwen.studio.dsl.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Simple ${key} / ${a.b} substitution from a flat or nested map (Studio message templates). */
public final class TemplateRenderer {
    private static final Pattern P = Pattern.compile("\\$\\{([^}]+)}");

    private TemplateRenderer() {}

    public static String render(String template, Map<String, Object> fields) {
        if (template == null) {
            return "";
        }
        Matcher m = P.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = PathResolver.get(fields, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
