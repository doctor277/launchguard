locals {
  azs = slice(var.availability_zones, 0, 2)
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = var.name }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-igw" }
}

resource "aws_subnet" "public" {
  for_each                = toset(local.azs)
  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.value
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, index(local.azs, each.value))
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-public-${each.value}", Tier = "public" }
}

resource "aws_subnet" "application" {
  for_each          = toset(local.azs)
  vpc_id            = aws_vpc.this.id
  availability_zone = each.value
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, 4 + index(local.azs, each.value))
  tags              = { Name = "${var.name}-app-${each.value}", Tier = "private" }
}

resource "aws_subnet" "data" {
  for_each          = toset(local.azs)
  vpc_id            = aws_vpc.this.id
  availability_zone = each.value
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, 8 + index(local.azs, each.value))
  tags              = { Name = "${var.name}-data-${each.value}", Tier = "isolated" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  count      = var.enable_nat_gateway ? 1 : 0
  domain     = "vpc"
  tags       = { Name = "${var.name}-nat" }
  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  count         = var.enable_nat_gateway ? 1 : 0
  allocation_id = aws_eip.nat[0].id
  subnet_id     = values(aws_subnet.public)[0].id
  tags          = { Name = "${var.name}-nat" }
}

resource "aws_route_table" "application" {
  for_each = aws_subnet.application
  vpc_id   = aws_vpc.this.id
  dynamic "route" {
    for_each = var.enable_nat_gateway ? [1] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = aws_nat_gateway.this[0].id
    }
  }
  tags = { Name = "${var.name}-app-${each.key}" }
}

resource "aws_route_table_association" "application" {
  for_each       = aws_subnet.application
  subnet_id      = each.value.id
  route_table_id = aws_route_table.application[each.key].id
}

resource "aws_route_table" "data" {
  for_each = aws_subnet.data
  vpc_id   = aws_vpc.this.id
  tags     = { Name = "${var.name}-data-${each.key}" }
}

resource "aws_route_table_association" "data" {
  for_each       = aws_subnet.data
  subnet_id      = each.value.id
  route_table_id = aws_route_table.data[each.key].id
}

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Public entry point; ingress is empty unless explicitly allowed"
  vpc_id      = aws_vpc.this.id
  dynamic "ingress" {
    for_each = { for pair in setproduct(toset(var.allowed_ingress_cidrs), toset([80, 443])) : "${pair[0]}:${pair[1]}" => pair }
    content {
      description = "Explicit demo client"
      from_port   = ingress.value[1]
      to_port     = ingress.value[1]
      protocol    = "tcp"
      cidr_blocks = [ingress.value[0]]
    }
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.name}-alb" }
}

resource "aws_security_group" "application" {
  name        = "${var.name}-application"
  description = "Private ECS tasks"
  vpc_id      = aws_vpc.this.id
  ingress {
    description     = "ALB to dashboard/backend"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  ingress {
    description = "Probe worker to private demo services"
    from_port   = 8081
    to_port     = 8083
    protocol    = "tcp"
    self        = true
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.name}-application" }
}

resource "aws_security_group" "database_client" {
  name        = "${var.name}-database-client"
  description = "Attached only to the backend task that requires PostgreSQL"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-database-client" }
}

resource "aws_security_group" "kafka_client" {
  name        = "${var.name}-kafka-client"
  description = "Attached only to backend and probe-worker tasks that require Kafka"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-kafka-client" }
}

resource "aws_security_group" "database" {
  name        = "${var.name}-database"
  description = "PostgreSQL from LaunchGuard tasks only"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.database_client.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.name}-database" }
}

resource "aws_security_group" "kafka" {
  name        = "${var.name}-kafka"
  description = "MSK IAM listener from LaunchGuard tasks only"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [aws_security_group.kafka_client.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.name}-kafka" }
}

resource "aws_security_group" "endpoints" {
  count       = var.enable_vpc_endpoints ? 1 : 0
  name        = "${var.name}-endpoints"
  description = "HTTPS from private ECS tasks"
  vpc_id      = aws_vpc.this.id
  ingress {
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    security_groups = [aws_security_group.application.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_vpc_endpoint" "interface" {
  for_each            = var.enable_vpc_endpoints ? toset(["ecr.api", "ecr.dkr", "logs", "secretsmanager"]) : toset([])
  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${data.aws_region.current.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = values(aws_subnet.application)[*].id
  security_group_ids  = [aws_security_group.endpoints[0].id]
  tags                = { Name = "${var.name}-${replace(each.value, ".", "-")}" }
}

resource "aws_vpc_endpoint" "s3" {
  count             = var.enable_vpc_endpoints ? 1 : 0
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = values(aws_route_table.application)[*].id
  tags              = { Name = "${var.name}-s3" }
}

data "aws_region" "current" {}

