output "application_url" { value = module.platform.application_url }
output "alb_dns_name" { value = module.platform.alb_dns_name }
output "ecs_cluster_name" { value = module.platform.cluster_name }
output "ecs_service_names" { value = module.platform.service_names }
output "ecr_repository_urls" { value = module.ecr.repository_urls }
output "rds_endpoint" {
  value     = module.data.endpoint
  sensitive = true
}
output "rds_secret_arn" {
  value     = module.data.master_secret_arn
  sensitive = true
}
output "msk_cluster_arn" { value = module.messaging.cluster_arn }
output "github_deploy_role_arn" { value = try(module.github_oidc[0].role_arn, null) }
output "demo_service_urls" {
  value = {
    payment      = "http://payment-service.${module.platform.service_discovery_namespace}:8081"
    order        = "http://order-service.${module.platform.service_discovery_namespace}:8082"
    notification = "http://notification-service.${module.platform.service_discovery_namespace}:8083"
  }
}
