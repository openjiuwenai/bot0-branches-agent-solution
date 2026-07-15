/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.expensereview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * expense-review entry point. The WorkflowAgent bean is assembled in
 * {@link ExpenseReviewConfiguration}; this class only boots Spring.
 *
 * @since 2026-07-08
 */
@SpringBootApplication
public class ExpenseReviewApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExpenseReviewApplication.class, args);
    }
}
