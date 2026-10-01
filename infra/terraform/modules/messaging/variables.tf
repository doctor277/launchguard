variable "name" { type = string }
variable "enabled" { type = bool }
variable "subnet_ids" { type = list(string) }
variable "security_group_id" { type = string }

