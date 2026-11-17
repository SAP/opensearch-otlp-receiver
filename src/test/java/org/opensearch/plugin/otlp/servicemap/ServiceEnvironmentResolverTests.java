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

import org.opensearch.test.OpenSearchTestCase;

import java.util.Map;

public class ServiceEnvironmentResolverTests extends OpenSearchTestCase {

    private final ServiceEnvironmentResolver resolver = new ServiceEnvironmentResolver();

    public void testDeploymentEnvironmentFallbacks() {
        assertEquals("production", resolver.resolve(Map.of(), Map.of(
            "deployment.environment.name", "production",
            "deployment.environment", "legacy")));
        assertEquals("legacy", resolver.resolve(Map.of(), Map.of(
            "deployment.environment", "legacy")));
        assertEquals("generic:default", resolver.resolve(Map.of(), Map.of()));
    }

    public void testAwsPlatformEnvironments() {
        assertEquals("api-gateway:prod", resolver.resolve(Map.of(
            "cloud.platform", "AWS_API_GATEWAY",
            "aws.api_gateway.stage", "prod"), Map.of()));
        assertEquals("api-gateway:default", resolver.resolve(Map.of(
            "cloud.platform", "aws_api_gateway"), Map.of()));
        assertEquals("ec2:default", environmentForPlatform("aws_ec2"));
        assertEquals("eks:default", environmentForPlatform("aws_eks"));
        assertEquals("elastic-beanstalk:default",
            environmentForPlatform("aws_elastic_beanstalk"));
        assertEquals("lambda:default", environmentForPlatform("aws_lambda"));
    }

    public void testEcsLaunchTypes() {
        assertEquals("ecs-fargate:default", resolver.resolve(Map.of(
            "cloud.platform", "aws_ecs", "aws.ecs.launchtype", "FARGATE"), Map.of()));
        assertEquals("ecs-ec2:default", resolver.resolve(Map.of(
            "cloud.platform", "aws_ecs", "aws.ecs.launchtype", "EC2"), Map.of()));
        assertEquals("ecs:default", environmentForPlatform("aws_ecs"));
    }

    public void testDetectsLambdaWithoutCloudPlatform() {
        assertEquals("lambda:default", resolver.resolve(Map.of(), Map.of(
            "cloud.resource_id", "arn:aws:lambda:eu-central-1:123:function:checkout")));
        assertEquals("lambda:default", resolver.resolve(Map.of(
            "aws.lambda.invoked_arn", "arn:aws:lambda:eu-central-1:123:function:checkout"), Map.of()));
        assertEquals("lambda:default", resolver.resolve(Map.of(), Map.of(
            "cloud.provider", "AWS", "faas.name", "checkout")));
    }

    public void testSpanAttributesTakePrecedenceOverResourceAttributes() {
        assertEquals("ec2:default", resolver.resolve(
            Map.of("cloud.platform", "aws_ec2"),
            Map.of("cloud.platform", "aws_eks")));
    }

    private String environmentForPlatform(String platform) {
        return resolver.resolve(Map.of("cloud.platform", platform), Map.of());
    }
}
