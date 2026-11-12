/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to this file be
 * licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.opensearch.plugin.otlp.servicemap;

import java.util.Locale;
import java.util.Map;

/** Resolves Data Prepper-compatible service environments from OTLP attributes. */
final class ServiceEnvironmentResolver {

    private static final String DEFAULT_ENVIRONMENT = "generic:default";

    String resolve(
            Map<String, Object> spanAttributes, Map<String, Object> resourceAttributes) {
        String cloudPlatform = firstString("cloud.platform", spanAttributes, resourceAttributes);
        if (cloudPlatform != null) {
            switch (cloudPlatform.toLowerCase(Locale.ROOT)) {
                case "aws_api_gateway":
                    return "api-gateway:" + valueOrDefault(
                        firstString("aws.api_gateway.stage", spanAttributes, resourceAttributes), "default");
                case "aws_ec2":
                    return "ec2:default";
                case "aws_ecs":
                    return ecsEnvironment(spanAttributes, resourceAttributes);
                case "aws_eks":
                    return "eks:default";
                case "aws_elastic_beanstalk":
                    return "elastic-beanstalk:default";
                case "aws_lambda":
                    return "lambda:default";
                default:
                    break;
            }
        }

        if (isAwsLambda(spanAttributes, resourceAttributes)) {
            return "lambda:default";
        }

        String environment = stringValue(resourceAttributes.get("deployment.environment.name"));
        if (environment == null) {
            environment = stringValue(resourceAttributes.get("deployment.environment"));
        }
        return valueOrDefault(environment, DEFAULT_ENVIRONMENT);
    }

    private static String ecsEnvironment(
            Map<String, Object> spanAttributes, Map<String, Object> resourceAttributes) {
        String launchType = firstString("aws.ecs.launchtype", spanAttributes, resourceAttributes);
        if ("fargate".equalsIgnoreCase(launchType)) {
            return "ecs-fargate:default";
        }
        if ("ec2".equalsIgnoreCase(launchType)) {
            return "ecs-ec2:default";
        }
        return "ecs:default";
    }

    private static boolean isAwsLambda(
            Map<String, Object> spanAttributes, Map<String, Object> resourceAttributes) {
        String resourceId = firstString("cloud.resource_id", spanAttributes, resourceAttributes);
        String invokedArn = firstString("aws.lambda.invoked_arn", spanAttributes, resourceAttributes);
        String cloudProvider = firstString("cloud.provider", spanAttributes, resourceAttributes);
        String functionName = firstString("faas.name", spanAttributes, resourceAttributes);
        return (resourceId != null && resourceId.startsWith("arn:aws:lambda:"))
            || invokedArn != null
            || ("aws".equalsIgnoreCase(cloudProvider) && functionName != null);
    }

    @SafeVarargs
    private static String firstString(String key, Map<String, Object>... maps) {
        for (Map<String, Object> map : maps) {
            String value = stringValue(map.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String stringValue(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
