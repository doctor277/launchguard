resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
  tags       = { Name = var.name }
}

resource "aws_db_instance" "this" {
  identifier                      = var.name
  engine                          = "postgres"
  engine_version                  = var.engine_version
  instance_class                  = var.instance_class
  allocated_storage               = var.allocated_storage_gib
  max_allocated_storage           = max(var.allocated_storage_gib, 100)
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = var.database_name
  username                        = var.master_username
  manage_master_user_password     = true
  port                            = 5432
  db_subnet_group_name            = aws_db_subnet_group.this.name
  vpc_security_group_ids          = [var.security_group_id]
  publicly_accessible             = false
  multi_az                        = false
  backup_retention_period         = var.backup_retention_days
  copy_tags_to_snapshot           = true
  deletion_protection             = var.deletion_protection
  skip_final_snapshot             = var.skip_final_snapshot
  final_snapshot_identifier       = var.skip_final_snapshot ? null : "${var.name}-final"
  auto_minor_version_upgrade      = true
  apply_immediately               = false
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  tags                            = { Name = var.name }
}

