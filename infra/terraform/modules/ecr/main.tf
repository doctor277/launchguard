resource "aws_ecr_repository" "this" {
  for_each             = var.repository_names
  name                 = "${var.name}/${each.value}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  tags = { Name = "${var.name}/${each.value}" }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name
  policy     = jsonencode({ rules = [{ rulePriority = 1, description = "Retain recent immutable images", selection = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = var.retained_image_count }, action = { type = "expire" } }] })
}

