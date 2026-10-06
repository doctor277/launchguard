variable "aws_region" {
  type    = string
  default = "us-east-1"
}
variable "environment" {
  type    = string
  default = "dev"
}
variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}
variable "allowed_ingress_cidrs" {
  description = "Explicit client CIDRs allowed to reach the public ALB; authentication is still required for application APIs."
  type        = list(string)
  default     = []
}
variable "acknowledge_billable_resources" {
  description = "Must be set explicitly after reviewing a saved plan and current pricing; even a zero-task apply creates billable ALB and RDS resources."
  type        = bool
  default     = false
}
variable "enable_nat_gateway" {
  description = "Creates a billable NAT gateway for private-task Internet and AWS API egress. Required for public probe targets or other Internet-only destinations."
  type        = bool
  default     = false
}
variable "enable_vpc_endpoints" {
  description = "Creates billable interface endpoints for ECR API/DKR, Logs, and Secrets Manager plus an S3 gateway endpoint; supports AWS-private task startup but not Internet access."
  type        = bool
  default     = false
}
variable "require_internet_egress" {
  description = "Declare that tasks must reach public probe targets, an Internet OTLP endpoint, or another Internet-only dependency; forces NAT to be enabled."
  type        = bool
  default     = false
}
variable "enable_msk" {
  description = "Creates a continuously billable MSK Serverless cluster; keep disabled except for an explicitly reviewed demonstration."
  type        = bool
  default     = false
}
variable "external_kafka_bootstrap_servers" {
  type    = string
  default = ""
}
variable "image_tag" {
  type    = string
  default = "bootstrap"
  validation {
    condition     = can(regex("^(bootstrap|sha-[0-9a-f]{40})$", var.image_tag))
    error_message = "image_tag must be bootstrap or an immutable sha-<40 hex> tag."
  }
}
variable "certificate_arn" {
  type    = string
  default = null
}
variable "otel_exporter_endpoint" {
  type    = string
  default = ""
}
variable "oidc_issuer_uri" {
  description = "Public standards-compliant OIDC issuer used by browsers and for JWT issuer validation."
  type        = string
  default     = ""
}
variable "oidc_jwk_set_uri" {
  description = "Optional backend-reachable JWK Set URI. Leave empty when issuer discovery is reachable from the backend."
  type        = string
  default     = ""
}
variable "oidc_audience" {
  type    = string
  default = "launchguard-api"
}
variable "oidc_roles_claim" {
  type    = string
  default = "roles"
}
variable "oidc_dashboard_client_id" {
  description = "Public SPA client ID; this is not a secret."
  type        = string
  default     = ""
}
variable "oidc_connect_src" {
  description = "OIDC origin allowed by the dashboard Content Security Policy, for example https://id.example.com."
  type        = string
  default     = ""
}
variable "database_name" {
  type    = string
  default = "launchguard"
}
variable "database_username" {
  type    = string
  default = "launchguard"
}
variable "database_engine_version" {
  description = "RDS PostgreSQL engine version. Confirm availability in the selected region with a real AWS plan before applying."
  type        = string
  default     = "18.6"
}
variable "database_instance_class" {
  description = "Billable RDS instance class created by every apply."
  type        = string
  default     = "db.t4g.micro"
}
variable "database_allocated_storage_gib" {
  type    = number
  default = 20
}
variable "database_backup_retention_days" {
  type    = number
  default = 1
}
variable "database_deletion_protection" {
  type    = bool
  default = false
}
variable "database_skip_final_snapshot" {
  type    = bool
  default = true
}
variable "ecr_retained_image_count" {
  type    = number
  default = 10
}
variable "log_retention_days" {
  type    = number
  default = 7
}
variable "desired_counts" {
  description = "Billable Fargate task counts. Counts default to zero but ALB and RDS baseline resources are still created."
  type        = map(number)
  default = {
    backend              = 0
    dashboard            = 0
    probe-worker         = 0
    payment-service      = 0
    order-service        = 0
    notification-service = 0
  }
}
variable "task_cpu" {
  type = map(number)
  default = {
    backend              = 512
    dashboard            = 256
    probe-worker         = 512
    payment-service      = 256
    order-service        = 256
    notification-service = 256
  }
}
variable "task_memory" {
  type = map(number)
  default = {
    backend              = 1024
    dashboard            = 512
    probe-worker         = 1024
    payment-service      = 512
    order-service        = 512
    notification-service = 512
  }
}
variable "enable_github_oidc" {
  type    = bool
  default = false
}
variable "create_github_oidc_provider" {
  type    = bool
  default = false
}
variable "existing_github_oidc_provider_arn" {
  type    = string
  default = ""
}
variable "github_repository" {
  type    = string
  default = "doctor277/launchguard"
}
variable "github_environment" {
  type    = string
  default = "aws-dev"
}
