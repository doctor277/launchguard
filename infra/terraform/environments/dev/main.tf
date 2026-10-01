locals {
  name                    = "${var.environment}-launchguard"
  services                = toset(["backend", "probe-worker", "payment-service", "order-service", "notification-service", "dashboard"])
  running_tasks           = sum(values(var.desired_counts))
  kafka_bootstrap_servers = var.enable_msk ? module.messaging.bootstrap_brokers : var.external_kafka_bootstrap_servers
}

data "aws_availability_zones" "available" { state = "available" }

module "network" {
  source                = "../../modules/network"
  name                  = local.name
  vpc_cidr              = var.vpc_cidr
  availability_zones    = data.aws_availability_zones.available.names
  enable_nat_gateway    = var.enable_nat_gateway
  enable_vpc_endpoints  = var.enable_vpc_endpoints
  allowed_ingress_cidrs = var.allowed_ingress_cidrs
}

module "ecr" {
  source               = "../../modules/ecr"
  name                 = local.name
  repository_names     = local.services
  retained_image_count = var.ecr_retained_image_count
}

module "data" {
  source                = "../../modules/data"
  name                  = local.name
  subnet_ids            = module.network.data_subnet_ids
  security_group_id     = module.network.database_security_group_id
  database_name         = var.database_name
  master_username       = var.database_username
  engine_version        = var.database_engine_version
  instance_class        = var.database_instance_class
  allocated_storage_gib = var.database_allocated_storage_gib
  backup_retention_days = var.database_backup_retention_days
  deletion_protection   = var.database_deletion_protection
  skip_final_snapshot   = var.database_skip_final_snapshot
}

module "messaging" {
  source            = "../../modules/messaging"
  name              = local.name
  enabled           = var.enable_msk
  subnet_ids        = module.network.data_subnet_ids
  security_group_id = module.network.kafka_security_group_id
}

module "platform" {
  source                            = "../../modules/platform"
  name                              = local.name
  environment_name                  = var.environment
  aws_region                        = var.aws_region
  vpc_id                            = module.network.vpc_id
  public_subnet_ids                 = module.network.public_subnet_ids
  application_subnet_ids            = module.network.application_subnet_ids
  alb_security_group_id             = module.network.alb_security_group_id
  application_security_group_id     = module.network.application_security_group_id
  database_client_security_group_id = module.network.database_client_security_group_id
  kafka_client_security_group_id    = module.network.kafka_client_security_group_id
  repository_urls                   = module.ecr.repository_urls
  repository_arns                   = module.ecr.repository_arns_by_name
  image_tag                         = var.image_tag
  desired_counts                    = var.desired_counts
  task_cpu                          = var.task_cpu
  task_memory                       = var.task_memory
  log_retention_days                = var.log_retention_days
  database_endpoint                 = module.data.endpoint
  database_name                     = module.data.database_name
  database_username                 = module.data.master_username
  database_secret_arn               = module.data.master_secret_arn
  kafka_bootstrap_servers           = local.kafka_bootstrap_servers
  msk_cluster_arn                   = module.messaging.cluster_arn
  msk_cluster_uuid                  = module.messaging.cluster_uuid
  certificate_arn                   = var.certificate_arn
  otel_exporter_endpoint            = var.otel_exporter_endpoint
}

module "github_oidc" {
  count                 = var.enable_github_oidc ? 1 : 0
  source                = "../../modules/github_oidc"
  name                  = local.name
  github_repository     = var.github_repository
  github_environment    = var.github_environment
  create_provider       = var.create_github_oidc_provider
  existing_provider_arn = var.existing_github_oidc_provider_arn
  ecr_repository_arns   = module.ecr.repository_arns
  ecs_cluster_arn       = module.platform.cluster_arn
  ecs_service_arns      = module.platform.service_arns
  ecs_task_role_arns    = module.platform.task_role_arns
}

resource "terraform_data" "deployment_guardrails" {
  input = {
    running_tasks = local.running_tasks
  }

  lifecycle {
    precondition {
      condition     = var.acknowledge_billable_resources
      error_message = "A dev apply creates billable baseline resources, including an ALB and RDS. Review a saved plan and current pricing, then set acknowledge_billable_resources=true explicitly."
    }
    precondition {
      condition     = local.running_tasks == 0 || var.enable_nat_gateway || var.enable_vpc_endpoints
      error_message = "Running private ECS tasks require either a NAT gateway or the required ECR API, ECR DKR, S3, CloudWatch Logs, and Secrets Manager VPC endpoints."
    }
    precondition {
      condition     = !var.require_internet_egress || var.enable_nat_gateway
      error_message = "require_internet_egress=true requires enable_nat_gateway=true; VPC endpoints do not provide arbitrary Internet access."
    }
    precondition {
      condition     = var.desired_counts["backend"] + var.desired_counts["probe-worker"] == 0 || var.enable_msk || trimspace(var.external_kafka_bootstrap_servers) != ""
      error_message = "Running backend or probe-worker tasks requires MSK or an explicit reachable external Kafka bootstrap address."
    }
    precondition {
      condition     = !var.enable_github_oidc || var.create_github_oidc_provider || trimspace(var.existing_github_oidc_provider_arn) != ""
      error_message = "Enable GitHub OIDC by creating the provider or supplying an existing provider ARN."
    }
  }
}

