output "repository_urls" { value = { for name, repository in aws_ecr_repository.this : name => repository.repository_url } }
output "repository_arns" { value = values(aws_ecr_repository.this)[*].arn }
output "repository_arns_by_name" { value = { for name, repository in aws_ecr_repository.this : name => repository.arn } }

