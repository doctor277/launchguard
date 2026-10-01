locals {
  services = {
    backend              = { port = 8080, health = "wget -q -T 3 -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1" }
    dashboard            = { port = 8080, health = "wget -q -T 3 -O /dev/null http://127.0.0.1:8080/healthz || exit 1" }
    probe-worker         = { port = 8084, health = "wget -q -T 3 -O /dev/null http://127.0.0.1:8084/actuator/health/liveness || exit 1" }
    payment-service      = { port = 8081, health = "wget -q -T 3 -O /dev/null http://127.0.0.1:8081/health || exit 1" }
    order-service        = { port = 8082, health = "wget -q -T 3 -O /dev/null http://127.0.0.1:8082/health || exit 1" }
    notification-service = { port = 8083, health = "wget -q -T 3 -O /dev/null http://127.0.0.1:8083/health || exit 1" }
  }
  demo_services = toset(["payment-service", "order-service", "notification-service"])
  kafka_enabled = var.msk_cluster_arn != null
  kafka_environment = [
    { name = "KAFKA_BOOTSTRAP_SERVERS", value = var.kafka_bootstrap_servers },
    { name = "KAFKA_TOPIC_PARTITIONS", value = "6" },
    { name = "KAFKA_TOPIC_REPLICATION_FACTOR", value = local.kafka_enabled ? "3" : "1" },
    { name = "KAFKA_CONSUMER_CONCURRENCY", value = "6" }
  ]
  telemetry_environment = [
    { name = "OTEL_TRACING_EXPORT_ENABLED", value = var.otel_exporter_endpoint != "" ? "true" : "false" },
    { name = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", value = var.otel_exporter_endpoint },
    { name = "TRACING_SAMPLING_PROBABILITY", value = "0.1" },
    { name = "OTEL_RESOURCE_ATTRIBUTES", value = "deployment.environment.name=${var.environment_name},service.namespace=launchguard" },
    { name = "LAUNCHGUARD_ENVIRONMENT", value = var.environment_name }
  ]
  environment = {
    backend = concat(local.kafka_environment, local.telemetry_environment, [
      { name = "SERVER_PORT", value = "8080" },
      { name = "DB_URL", value = "jdbc:postgresql://${var.database_endpoint}:5432/${var.database_name}" },
      { name = "DB_USERNAME", value = var.database_username },
      { name = "MONITORING_INTERVAL", value = "30s" },
      { name = "MONITORING_INITIAL_DELAY", value = "30s" },
      { name = "MONITORING_CONNECT_TIMEOUT", value = "2s" },
      { name = "MONITORING_RESPONSE_TIMEOUT", value = "5s" },
      { name = "SPRING_PROFILES_ACTIVE", value = local.kafka_enabled ? "aws" : "default" }
    ])
    probe-worker = concat(local.kafka_environment, local.telemetry_environment, [
      { name = "SERVER_PORT", value = "8084" },
      { name = "PROBE_WORKER_THREADS", value = "4" },
      { name = "PROBE_WORKER_QUEUE_CAPACITY", value = "64" },
      { name = "SPRING_PROFILES_ACTIVE", value = local.kafka_enabled ? "aws" : "default" }
    ])
    dashboard            = []
    payment-service      = [{ name = "SERVER_PORT", value = "8081" }]
    order-service        = [{ name = "SERVER_PORT", value = "8082" }]
    notification-service = [{ name = "SERVER_PORT", value = "8083" }]
  }
}

resource "aws_ecs_cluster" "this" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
  tags = { Name = var.name }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name = "${var.name}.internal"
  vpc  = var.vpc_id
  tags = { Name = "${var.name}.internal" }
}

resource "aws_service_discovery_service" "demo" {
  for_each = local.demo_services
  name     = each.value
  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.this.id
    dns_records {
      ttl  = 10
      type = "A"
    }
    routing_policy = "MULTIVALUE"
  }
  health_check_custom_config {}
  tags = { Name = "${var.name}-${each.value}" }
}

resource "aws_cloudwatch_log_group" "service" {
  for_each          = local.services
  name              = "/ecs/${var.name}/${each.key}"
  retention_in_days = var.log_retention_days
  tags              = { Name = "${var.name}-${each.key}" }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*"]
    }
  }
}

resource "aws_iam_role" "execution" {
  for_each           = local.services
  name               = "${var.name}-${each.key}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = { Name = "${var.name}-${each.key}-execution" }
}

data "aws_iam_policy_document" "execution" {
  for_each = local.services
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
    resources = [var.repository_arns[each.key]]
  }
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.service[each.key].arn}:*"]
  }
}

resource "aws_iam_role_policy" "execution" {
  for_each = local.services
  name     = "${var.name}-${each.key}-execution"
  role     = aws_iam_role.execution[each.key].id
  policy   = data.aws_iam_policy_document.execution[each.key].json
}

data "aws_iam_policy_document" "database_secret" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.database_secret_arn]
  }
}

resource "aws_iam_role_policy" "database_secret" {
  name   = "${var.name}-database-secret"
  role   = aws_iam_role.execution["backend"].id
  policy = data.aws_iam_policy_document.database_secret.json
}

resource "aws_iam_role" "task" {
  for_each           = local.services
  name               = "${var.name}-${each.key}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = { Name = "${var.name}-${each.key}-task" }
}

data "aws_iam_policy_document" "msk" {
  count = local.kafka_enabled ? 1 : 0
  statement {
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
    resources = [var.msk_cluster_arn]
  }
  statement {
    actions   = ["kafka-cluster:CreateTopic", "kafka-cluster:DescribeTopic", "kafka-cluster:AlterTopic", "kafka-cluster:ReadData", "kafka-cluster:WriteData"]
    resources = ["${replace(var.msk_cluster_arn, ":cluster/", ":topic/")}/*"]
  }
  statement {
    actions   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
    resources = ["${replace(var.msk_cluster_arn, ":cluster/", ":group/")}/*"]
  }
}

resource "aws_iam_role_policy" "msk" {
  for_each = local.kafka_enabled ? toset(["backend", "probe-worker"]) : toset([])
  name     = "${var.name}-msk"
  role     = aws_iam_role.task[each.value].id
  policy   = data.aws_iam_policy_document.msk[0].json
}

resource "aws_ecs_task_definition" "service" {
  for_each                 = local.services
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_cpu[each.key])
  memory                   = tostring(var.task_memory[each.key])
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name                   = each.key
    image                  = "${var.repository_urls[each.key]}:${var.image_tag}"
    essential              = true
    portMappings           = [{ name = "http", containerPort = each.value.port, hostPort = each.value.port, protocol = "tcp" }]
    environment            = local.environment[each.key]
    secrets                = each.key == "backend" ? [{ name = "DB_PASSWORD", valueFrom = "${var.database_secret_arn}:password::" }] : []
    healthCheck            = { command = ["CMD-SHELL", each.value.health], interval = 30, timeout = 5, retries = 3, startPeriod = 60 }
    logConfiguration       = { logDriver = "awslogs", options = { "awslogs-group" = aws_cloudwatch_log_group.service[each.key].name, "awslogs-region" = var.aws_region, "awslogs-stream-prefix" = each.key } }
    readonlyRootFilesystem = false
  }])
  tags = { Name = "${var.name}-${each.key}" }
}

resource "aws_lb" "this" {
  name                       = substr(var.name, 0, 32)
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [var.alb_security_group_id]
  subnets                    = var.public_subnet_ids
  drop_invalid_header_fields = true
  tags                       = { Name = var.name }
}

resource "aws_lb_target_group" "dashboard" {
  name        = substr("${var.name}-dashboard", 0, 32)
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  health_check {
    enabled             = true
    path                = "/healthz"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group" "backend" {
  name        = substr("${var.name}-backend", 0, 32)
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"
  dynamic "default_action" {
    for_each = var.certificate_arn == null ? [1] : []
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.dashboard.arn
    }
  }
  dynamic "default_action" {
    for_each = var.certificate_arn != null ? [1] : []
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count             = var.certificate_arn != null ? 1 : 0
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.dashboard.arn
  }
}

resource "aws_lb_listener_rule" "backend_http" {
  count        = var.certificate_arn == null ? 1 : 0
  listener_arn = aws_lb_listener.http.arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
  condition {
    path_pattern {
      values = ["/api", "/api/*"]
    }
  }
}

resource "aws_lb_listener_rule" "backend_https" {
  count        = var.certificate_arn != null ? 1 : 0
  listener_arn = aws_lb_listener.https[0].arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
  condition {
    path_pattern {
      values = ["/api", "/api/*"]
    }
  }
}

resource "aws_ecs_service" "this" {
  for_each                          = local.services
  name                              = "${var.name}-${each.key}"
  cluster                           = aws_ecs_cluster.this.id
  task_definition                   = aws_ecs_task_definition.service[each.key].arn
  desired_count                     = var.desired_counts[each.key]
  launch_type                       = "FARGATE"
  platform_version                  = "LATEST"
  enable_execute_command            = false
  health_check_grace_period_seconds = contains(["backend", "dashboard"], each.key) ? 120 : null
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets = var.application_subnet_ids
    security_groups = concat(
      [var.application_security_group_id],
      each.key == "backend" ? [var.database_client_security_group_id] : [],
      contains(["backend", "probe-worker"], each.key) ? [var.kafka_client_security_group_id] : []
    )
    assign_public_ip = false
  }
  dynamic "load_balancer" {
    for_each = each.key == "dashboard" ? [aws_lb_target_group.dashboard.arn] : each.key == "backend" ? [aws_lb_target_group.backend.arn] : []
    content {
      target_group_arn = load_balancer.value
      container_name   = each.key
      container_port   = each.value.port
    }
  }
  dynamic "service_registries" {
    for_each = contains(local.demo_services, each.key) ? [aws_service_discovery_service.demo[each.key].arn] : []
    content { registry_arn = service_registries.value }
  }
  lifecycle { ignore_changes = [task_definition] }
  depends_on = [aws_lb_listener.http, aws_lb_listener.https]
  tags       = { Name = "${var.name}-${each.key}" }
}

