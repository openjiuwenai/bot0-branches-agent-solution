/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.expensereviewmain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * expense-review-main entry point. The ReActAgent bean is assembled in
 * {@link ExpenseReviewMainConfiguration}; this class only boots Spring.
 *
 * @since 2026-07-08
 */
@SpringBootApplication
public class ExpenseReviewMainApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExpenseReviewMainApplication.class, args);
    }
}
