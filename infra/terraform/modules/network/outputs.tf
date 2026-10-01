output "vpc_id" { value = aws_vpc.this.id }
output "public_subnet_ids" { value = values(aws_subnet.public)[*].id }
output "application_subnet_ids" { value = values(aws_subnet.application)[*].id }
output "data_subnet_ids" { value = values(aws_subnet.data)[*].id }
output "alb_security_group_id" { value = aws_security_group.alb.id }
output "application_security_group_id" { value = aws_security_group.application.id }
output "database_client_security_group_id" { value = aws_security_group.database_client.id }
output "kafka_client_security_group_id" { value = aws_security_group.kafka_client.id }
output "database_security_group_id" { value = aws_security_group.database.id }
output "kafka_security_group_id" { value = aws_security_group.kafka.id }

