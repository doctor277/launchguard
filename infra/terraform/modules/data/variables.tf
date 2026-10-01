variable "name" { type = string }
variable "subnet_ids" { type = list(string) }
variable "security_group_id" { type = string }
variable "database_name" { type = string }
variable "master_username" { type = string }
variable "engine_version" { type = string }
variable "instance_class" { type = string }
variable "allocated_storage_gib" { type = number }
variable "backup_retention_days" { type = number }
variable "deletion_protection" { type = bool }
variable "skip_final_snapshot" { type = bool }

