variable "name" { type = string }
variable "environment_name" { type = string }
variable "aws_region" { type = string }
variable "vpc_id" { type = string }
variable "public_subnet_ids" { type = list(string) }
variable "application_subnet_ids" { type = list(string) }
variable "alb_security_group_id" { type = string }
variable "application_security_group_id" { type = string }
variable "database_client_security_group_id" { type = string }
variable "kafka_client_security_group_id" { type = string }
variable "repository_urls" { type = map(string) }
variable "repository_arns" { type = map(string) }
variable "image_tag" { type = string }
variable "desired_counts" { type = map(number) }
variable "task_cpu" { type = map(number) }
variable "task_memory" { type = map(number) }
variable "log_retention_days" { type = number }
variable "database_endpoint" { type = string }
variable "database_name" { type = string }
variable "database_username" { type = string }
variable "database_secret_arn" { type = string }
variable "kafka_bootstrap_servers" { type = string }
variable "msk_cluster_arn" {
  type    = string
  default = null
}
variable "msk_cluster_uuid" {
  type    = string
  default = null
}
variable "certificate_arn" {
  type    = string
  default = null
}
variable "otel_exporter_endpoint" {
  type    = string
  default = ""
}

