variable "name" { type = string }
variable "vpc_cidr" { type = string }
variable "availability_zones" { type = list(string) }
variable "enable_nat_gateway" { type = bool }
variable "enable_vpc_endpoints" { type = bool }
variable "allowed_ingress_cidrs" { type = list(string) }

