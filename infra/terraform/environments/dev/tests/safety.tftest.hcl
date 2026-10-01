mock_provider "aws" {
  override_during = plan

  mock_data "aws_availability_zones" {
    defaults = {
      names = ["us-east-1a", "us-east-1b"]
    }
  }
}

override_module {
  target = module.network
  outputs = {
    vpc_id                            = "vpc-test"
    public_subnet_ids                 = ["subnet-public-a", "subnet-public-b"]
    application_subnet_ids            = ["subnet-app-a", "subnet-app-b"]
    data_subnet_ids                   = ["subnet-data-a", "subnet-data-b"]
    alb_security_group_id             = "sg-alb"
    application_security_group_id     = "sg-application"
    database_client_security_group_id = "sg-database-client"
    kafka_client_security_group_id    = "sg-kafka-client"
    database_security_group_id        = "sg-database"
    kafka_security_group_id           = "sg-kafka"
  }
}

override_module {
  target = module.ecr
  outputs = {
    repository_urls = {
      backend              = "example.invalid/backend"
      probe-worker         = "example.invalid/probe-worker"
      payment-service      = "example.invalid/payment-service"
      order-service        = "example.invalid/order-service"
      notification-service = "example.invalid/notification-service"
      dashboard            = "example.invalid/dashboard"
    }
    repository_arns = []
    repository_arns_by_name = {
      backend              = "arn:aws:ecr:us-east-1:111122223333:repository/backend"
      probe-worker         = "arn:aws:ecr:us-east-1:111122223333:repository/probe-worker"
      payment-service      = "arn:aws:ecr:us-east-1:111122223333:repository/payment-service"
      order-service        = "arn:aws:ecr:us-east-1:111122223333:repository/order-service"
      notification-service = "arn:aws:ecr:us-east-1:111122223333:repository/notification-service"
      dashboard            = "arn:aws:ecr:us-east-1:111122223333:repository/dashboard"
    }
  }
}

override_module {
  target = module.data
  outputs = {
    endpoint          = "postgres.internal"
    port              = 5432
    database_name     = "launchguard"
    master_username   = "launchguard"
    master_secret_arn = "arn:aws:secretsmanager:us-east-1:111122223333:secret:launchguard"
    identifier        = "dev-launchguard"
  }
}

override_module {
  target = module.messaging
  outputs = {
    cluster_arn       = "arn:aws:kafka:us-east-1:111122223333:cluster/dev-launchguard/test"
    cluster_uuid      = "test"
    bootstrap_brokers = "boot-test.kafka-serverless.us-east-1.amazonaws.com:9098"
  }
}

override_module {
  target = module.platform
  outputs = {
    cluster_arn                 = "arn:aws:ecs:us-east-1:111122223333:cluster/dev-launchguard"
    cluster_name                = "dev-launchguard"
    service_arns                = []
    service_names               = {}
    task_role_arns              = []
    alb_dns_name                = "launchguard.example.invalid"
    application_url             = "http://launchguard.example.invalid"
    service_discovery_namespace = "dev-launchguard.internal"
  }
}

run "zero_tasks_msk_off_is_safe_after_cost_acknowledgement" {
  command = plan

  variables {
    acknowledge_billable_resources = true
  }
}

run "private_tasks_with_required_endpoints_and_msk_are_valid" {
  command = plan

  variables {
    acknowledge_billable_resources = true
    enable_vpc_endpoints           = true
    enable_msk                     = true
    desired_counts = {
      backend              = 1
      dashboard            = 1
      probe-worker         = 1
      payment-service      = 1
      order-service        = 1
      notification-service = 1
    }
  }
}

run "tasks_without_nat_or_endpoints_fail_early" {
  command = plan

  variables {
    acknowledge_billable_resources = true
    enable_msk                     = true
    desired_counts = {
      backend              = 1
      dashboard            = 0
      probe-worker         = 0
      payment-service      = 0
      order-service        = 0
      notification-service = 0
    }
  }

  expect_failures = [terraform_data.deployment_guardrails]
}

run "endpoint_only_cannot_claim_internet_egress" {
  command = plan

  variables {
    acknowledge_billable_resources = true
    enable_vpc_endpoints           = true
    require_internet_egress        = true
  }

  expect_failures = [terraform_data.deployment_guardrails]
}

run "billable_baseline_requires_explicit_acknowledgement" {
  command = plan

  expect_failures = [terraform_data.deployment_guardrails]
}
