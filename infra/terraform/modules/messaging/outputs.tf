output "cluster_arn" { value = try(aws_msk_serverless_cluster.this[0].arn, null) }
output "cluster_uuid" { value = try(aws_msk_serverless_cluster.this[0].cluster_uuid, null) }
output "bootstrap_brokers" { value = try(aws_msk_serverless_cluster.this[0].bootstrap_brokers_sasl_iam, null) }

