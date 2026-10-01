variable "name" { type = string }
variable "github_repository" { type = string }
variable "github_environment" { type = string }
variable "create_provider" { type = bool }
variable "existing_provider_arn" { type = string }
variable "ecr_repository_arns" { type = list(string) }
variable "ecs_cluster_arn" { type = string }
variable "ecs_service_arns" { type = list(string) }
variable "ecs_task_role_arns" { type = list(string) }

