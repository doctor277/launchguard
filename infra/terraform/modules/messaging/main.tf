resource "aws_msk_serverless_cluster" "this" {
  count        = var.enabled ? 1 : 0
  cluster_name = var.name
  vpc_config {
    subnet_ids         = var.subnet_ids
    security_group_ids = [var.security_group_id]
  }
  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }
  tags = { Name = var.name }
}

