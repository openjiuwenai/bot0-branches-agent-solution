/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails.formatters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fuzzy datetime parsing aligned with Python {@code dateutil.parser.parse}.
 *
 * <p>When {@code python3} and {@code python-dateutil} are available, delegates to dateutil for
 * true 1:1. Otherwise uses an expanded Java fuzzy parser (fixed formats, CN/EN relative, month
 * names, embedded dates in free text).
 *
 * @since 2026-08-26
 */

public final class DateUtilCompatibleParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> FIXED_FORMATS =
            List.of(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd HH:mm",
                    "yyyy-MM-dd",
                    "yyyy/MM/dd HH:mm:ss",
                    "yyyy/MM/dd HH:mm",
                    "yyyy/MM/dd",
                    "dd/MM/yyyy HH:mm:ss",
                    "dd/MM/yyyy",
                    "MM/dd/yyyy HH:mm:ss",
                    "MM/dd/yyyy",
                    "yyyyMMddHHmmss",
                    "yyyyMMdd",
                    "HH:mm:ss",
                    "HH:mm");

    private static final Pattern CN_YMD =
            Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern CN_MD =
            Pattern.compile("(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号](?!\\d)");
    private static final Pattern CN_HMS =
            Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})(?:\\s*[:：]\\s*(\\d{1,2}))?");
    private static final Pattern ISO_DATE_IN_TEXT =
            Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
    private static final Pattern DIGITS_8 = Pattern.compile("(?<!\\d)(\\d{8})(?!\\d)");

    private static volatile Boolean pythonDateutilAvailable;

    private DateUtilCompatibleParser() {}

    /**
     * Parse user input fuzzily (dateutil-compatible).
     *
     * @param value raw user text
     * @return parsed local datetime or null
     */

    public static LocalDateTime tryParse(String value) {
        if (value == null || value.isBlank()) {
        return null;
    }
        String trimmed = value.trim();
        LocalDateTime fromPython = tryParseViaPythonDateutil(trimmed);
        if (fromPython != null) {
            return fromPython;
        }
        return tryParseJava(trimmed);
    }

    /**
     * Format with Python strftime pattern ({@code %Y-%m-%d} etc.).
     *
     * @param dt datetime
     * @param pyFormat python strftime
     * @return formatted string
     */

    public static String formatWithPyPattern(LocalDateTime dt, String pyFormat) {
        String javaFmt = DateTimeFormatValidateAction.toJavaPattern(pyFormat);
        return dt.format(DateTimeFormatter.ofPattern(javaFmt));
    }

    static LocalDateTime tryParseChineseRelative(String value) {
        if (value == null || value.isBlank()) {
        return null;
    }
        LocalDate today = LocalDate.now();
        if (value.contains("今天") || value.contains("今日")) {
            return atTimeFromText(value, today);
        }
        if (value.contains("明天") || value.contains("明日")) {
            return atTimeFromText(value, today.plusDays(1));
        }
        if (value.contains("后天")) {
            return atTimeFromText(value, today.plusDays(2));
        }
        if (value.contains("昨天") || value.contains("昨日")) {
            return atTimeFromText(value, today.minusDays(1));
        }
        if (value.contains("前天")) {
            return atTimeFromText(value, today.minusDays(2));
        }
        return null;
    }

    private static LocalDateTime atTimeFromText(String value, LocalDate date) {
        Matcher hm = CN_HMS.matcher(value);
        if (hm.find()) {
            int hour = Integer.parseInt(hm.group(1));
            int minute = Integer.parseInt(hm.group(2));
            int second = hm.group(3) == null ? 0 : Integer.parseInt(hm.group(3));
            return LocalDateTime.of(date, LocalTime.of(hour, minute, second));
        }
        return date.atStartOfDay();
    }

    private static LocalDateTime tryParseJava(String value) {
        LocalDateTime relative = tryRelativeEnglish(value);
        if (relative != null) {
            return relative;
        }
        relative = tryParseChineseRelative(value);
        if (relative != null) {
            return relative;
        }
        LocalDateTime fixed = tryFixedFormats(value);
        if (fixed != null) {
            return fixed;
        }
        LocalDateTime cn = tryChinesePatterns(value);
        if (cn != null) {
            return cn;
        }
        LocalDateTime en = tryEnglishMonthNames(value);
        if (en != null) {
            return en;
        }
        return tryFuzzyEmbedded(value);
    }

    private static LocalDateTime tryFixedFormats(String value) {
        for (String fmt : FIXED_FORMATS) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(fmt);
                if (fmt.contains("HH") && fmt.contains("yyyy")) {
                    return LocalDateTime.parse(value, f);
                }
                if (fmt.contains("yyyy") && !fmt.contains("HH")) {
                    return LocalDate.parse(value, f).atStartOfDay();
                }
                if (fmt.contains("HH") && !fmt.contains("yyyy")) {
                    return LocalDate.now().atTime(LocalTime.parse(value, f));
                }
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        return null;
    }

    private static LocalDateTime tryChinesePatterns(String value) {
        Matcher ymd = CN_YMD.matcher(value);
        if (ymd.find()) {
            int y = Integer.parseInt(ymd.group(1));
            int m = Integer.parseInt(ymd.group(2));
            int d = Integer.parseInt(ymd.group(3));
            return withOptionalTime(value, LocalDate.of(y, m, d));
        }
        Matcher md = CN_MD.matcher(value);
        if (md.find()) {
            int m = Integer.parseInt(md.group(1));
            int d = Integer.parseInt(md.group(2));
            return withOptionalTime(value, LocalDate.of(Year.now().getValue(), m, d));
        }
        return null;
    }

    private static LocalDateTime withOptionalTime(String value, LocalDate date) {
        Matcher hm = CN_HMS.matcher(value);
        if (hm.find()) {
            int hour = Integer.parseInt(hm.group(1));
            int minute = Integer.parseInt(hm.group(2));
            int second = hm.group(3) == null ? 0 : Integer.parseInt(hm.group(3));
            return LocalDateTime.of(date, LocalTime.of(hour, minute, second));
        }
        Matcher isoTime = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(value);
        if (isoTime.find()) {
            int hour = Integer.parseInt(isoTime.group(1));
            int minute = Integer.parseInt(isoTime.group(2));
            int second = isoTime.group(3) == null ? 0 : Integer.parseInt(isoTime.group(3));
            return LocalDateTime.of(date, LocalTime.of(hour, minute, second));
        }
        return date.atStartOfDay();
    }

    private static LocalDateTime tryEnglishMonthNames(String value) {
        List<DateTimeFormatter> formatters =
                List.of(
                        new DateTimeFormatterBuilder()
                                .parseCaseInsensitive()
                                .appendPattern("MMM d, yyyy")
                                .optionalStart()
                                .appendPattern(" HH:mm:ss")
                                .optionalEnd()
                                .toFormatter(Locale.ENGLISH),
                        new DateTimeFormatterBuilder()
                                .parseCaseInsensitive()
                                .appendPattern("MMMM d, yyyy")
                                .optionalStart()
                                .appendPattern(" HH:mm:ss")
                                .optionalEnd()
                                .toFormatter(Locale.ENGLISH),
                        new DateTimeFormatterBuilder()
                                .parseCaseInsensitive()
                                .appendPattern("d MMM yyyy")
                                .optionalStart()
                                .appendPattern(" HH:mm")
                                .optionalEnd()
                                .toFormatter(Locale.ENGLISH));
        for (DateTimeFormatter f : formatters) {
            try {
                return LocalDateTime.parse(value, f);
            } catch (DateTimeParseException e) {
                try {
                    return LocalDate.parse(value, f).atStartOfDay();
                } catch (DateTimeParseException ignored) {
                    // next
                }
            }
        }
        return null;
    }

    private static LocalDateTime tryRelativeEnglish(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now();
        if (lower.contains("today")) {
            return atTimeFromText(value, today);
        }
        if (lower.contains("tomorrow")) {
            return atTimeFromText(value, today.plusDays(1));
        }
        if (lower.contains("yesterday")) {
            return atTimeFromText(value, today.minusDays(1));
        }
        return null;
    }

    private static LocalDateTime tryFuzzyEmbedded(String value) {
        Matcher iso = ISO_DATE_IN_TEXT.matcher(value);
        if (iso.find()) {
            int y = Integer.parseInt(iso.group(1));
            int m = Integer.parseInt(iso.group(2));
            int d = Integer.parseInt(iso.group(3));
            return withOptionalTime(value, LocalDate.of(y, m, d));
        }
        Matcher digits = DIGITS_8.matcher(value);
        if (digits.find()) {
            String s = digits.group(1);
            try {
                return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static LocalDateTime tryParseViaPythonDateutil(String value) {
        if (!isPythonDateutilAvailable()) {
        return null;
    }
        String script =
                """
                import json, sys
                try:
                    from dateutil import parser
                    dt = parser.parse(sys.stdin.read().strip())
                    print(json.dumps({
                        "year": dt.year, "month": dt.month, "day": dt.day,
                        "hour": dt.hour, "minute": dt.minute, "second": dt.second
                    }))
                except Exception:
                    sys.exit(1)
                """;
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-c", script);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (OutputStreamWriter w =
                    new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                        w.write(value);
                    }
            String out;
            try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        out = r.readLine();
                    }
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 || out == null || out.isBlank()) {
                return null;
            }
            JsonNode node = MAPPER.readTree(out);
            return LocalDateTime.of(
                    node.get("year").asInt(),
                    node.get("month").asInt(),
                    node.get("day").asInt(),
                    node.path("hour").asInt(0),
                    node.path("minute").asInt(0),
                    node.path("second").asInt(0));
        } catch (IOException | InterruptedException ignored) {
            return null;
        }
    }

    private static boolean isPythonDateutilAvailable() {
        Boolean cached = pythonDateutilAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (DateUtilCompatibleParser.class) {
            if (pythonDateutilAvailable != null) {
                return pythonDateutilAvailable;
            }
            boolean ok = false;
            try {
                Process p =
                        new ProcessBuilder("python3", "-c", "from dateutil import parser")
                                .redirectErrorStream(true)
                                .start();
                try (InputStream stdout = p.getInputStream()) {
                    stdout.readAllBytes();
                }
                ok = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (IOException | InterruptedException ignored) {
                ok = false;
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
            }
            pythonDateutilAvailable = ok;
            return ok;
        }
    }

    /**
     * Test hook: reset python availability cache.
     *
     * @since 0.1.0
     *
     */

    public static void resetPythonAvailabilityCacheForTest() {
        pythonDateutilAvailable = null;
    }

    /**
     * Test hook: force Java-only parsing path.
     *
     * @since 0.1.0
     *
     */

    public static void disablePythonDateutilForTest() {
        pythonDateutilAvailable = false;
    }
}
