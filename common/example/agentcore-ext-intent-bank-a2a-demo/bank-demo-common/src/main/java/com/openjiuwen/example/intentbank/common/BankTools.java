/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic in-memory tools used by the bank demo. */
public final class BankTools {
    public static final String BALANCE = "query_balance";
    public static final String TRANSFER = "execute_transfer";
    public static final String WEALTH_RECOMMEND = "recommend_wealth";
    public static final String WEALTH_PURCHASE = "purchase_wealth";
    public static final String CALCULATOR = "bank_calculator";
    public static final String CURRENT_DATE = "current_date";
    public static final String WEATHER = "weather_query";

    private static final Pattern BINARY_EXPRESSION = Pattern
            .compile("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*([+\\-*/])\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))");

    private BankTools() {
    }

    public static Tool balance() {
        return tool(BALANCE, "查询银行账户余额", schema(Map.of("account", stringProperty("账户名称或账号；为空时使用当前账户")), List.of()),
                inputs -> Map.of("handledBy", "balance-agent", "status", "COMPLETED", "account",
                        text(inputs.get("account"), "当前账户"), "balance", 12800.50D, "currency", "CNY"));
    }

    public static Tool transfer() {
        return tool(TRANSFER, "执行一笔已收集收款人、金额且即将确认的银行转账",
                schema(Map.of("recipient", stringProperty("收款人"), "amount", numberProperty("转账金额")),
                        List.of("recipient", "amount")),
                inputs -> {
                    String recipient = text(inputs.get("recipient"), "unknown");
                    BigDecimal amount = decimal(inputs.get("amount"));
                    return Map.of("handledBy", "transfer-agent", "status", "COMPLETED", "recipient", recipient,
                            "amount", amount, "currency", "CNY", "transferId", "TR-DEMO-0001");
                });
    }

    public static Tool wealthRecommendation() {
        return tool(WEALTH_RECOMMEND, "根据金额、期限和风险偏好推荐理财产品",
                schema(Map.of("query", stringProperty("完整理财推荐需求")), List.of("query")),
                inputs -> Map.of("handledBy", "wealth-advisor-agent", "status", "COMPLETED", "recommendations",
                        List.of(Map.of("product", "稳盈90天", "risk", "R2", "annualRate", "2.8%"),
                                Map.of("product", "进取180天", "risk", "R3", "annualRate", "3.6%"))));
    }

    public static Tool wealthPurchase() {
        return tool(WEALTH_PURCHASE, "执行一笔已收集产品、金额且即将确认的理财购买",
                schema(Map.of("product", stringProperty("理财产品名称"), "amount", numberProperty("购买金额")),
                        List.of("product", "amount")),
                inputs -> Map.of("handledBy", "wealth-purchase-agent", "status", "COMPLETED", "product",
                        text(inputs.get("product"), "稳盈90天"), "amount", decimal(inputs.get("amount")), "currency",
                        "CNY", "purchaseId", "WP-DEMO-0001"));
    }

    public static Tool calculator() {
        return tool(CALCULATOR, "计算一个包含加减乘除的二元算式",
                schema(Map.of("query", stringProperty("包含算式的完整请求")), List.of("query")),
                inputs -> calculate(text(inputs.get("query"), "")));
    }

    public static Tool currentDate() {
        return tool(CURRENT_DATE, "查询当前日期和星期", schema(Map.of(), List.of()),
                inputs -> Map.of("handledBy", "intent-agent", "status", "COMPLETED", "date", LocalDate.now().toString(),
                        "dayOfWeek", LocalDate.now().getDayOfWeek().name()));
    }

    public static Tool weather() {
        return tool(WEATHER, "查询指定城市的演示天气", schema(Map.of("query", stringProperty("包含城市的完整天气请求")), List.of("query")),
                inputs -> {
                    String query = text(inputs.get("query"), "");
                    String city = query.contains("上海") ? "上海" : query.contains("北京") ? "北京" : "深圳";
                    String weather = "北京".equals(city) ? "晴，26C" : "上海".equals(city) ? "多云，28C" : "阵雨，30C";
                    return Map.of("handledBy", "intent-agent", "status", "COMPLETED", "city", city, "weather", weather);
                });
    }

    private static Tool tool(String name, String description, Map<String, Object> inputParams,
            java.util.function.Function<Map<String, Object>, Object> function) {
        ToolCard card = ToolCard.builder().id(name).name(name).description(description).inputParams(inputParams)
                .build();
        return new LocalFunction(card, function::apply);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> numberProperty(String description) {
        return Map.of("type", "number", "exclusiveMinimum", 0, "description", description);
    }

    private static Object calculate(String query) {
        Matcher matcher = BINARY_EXPRESSION.matcher(query);
        if (!matcher.find()) {
            return Map.of("handledBy", "intent-agent", "status", "INVALID_INPUT", "message", "未找到可计算的二元算式");
        }
        BigDecimal left = new BigDecimal(matcher.group(1));
        BigDecimal right = new BigDecimal(matcher.group(3));
        BigDecimal result = switch (matcher.group(2)) {
            case "+" -> left.add(right);
            case "-" -> left.subtract(right);
            case "*" -> left.multiply(right);
            case "/" -> right.signum() == 0 ? null : left.divide(right, MathContext.DECIMAL128);
            default -> null;
        };
        if (result == null) {
            return Map.of("handledBy", "intent-agent", "status", "INVALID_INPUT", "message", "除数不能为零");
        }
        return Map.of("handledBy", "intent-agent", "status", "COMPLETED", "expression", matcher.group(), "result",
                result.stripTrailingZeros().toPlainString());
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(text(value, "0"));
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }
}
