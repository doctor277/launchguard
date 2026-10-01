output "cluster_arn" { value = aws_ecs_cluster.this.arn }
output "cluster_name" { value = aws_ecs_cluster.this.name }
output "service_arns" { value = values(aws_ecs_service.this)[*].id }
output "service_names" { value = { for name, service in aws_ecs_service.this : name => service.name } }
output "task_role_arns" { value = concat(values(aws_iam_role.execution)[*].arn, values(aws_iam_role.task)[*].arn) }
output "alb_dns_name" { value = aws_lb.this.dns_name }
output "application_url" { value = "${var.certificate_arn == null ? "http" : "https"}://${aws_lb.this.dns_name}" }
output "service_discovery_namespace" { value = aws_service_discovery_private_dns_namespace.this.name }

