# -----------------------------------------------------------------------------
# Virtual Cloud Network (VCN)
# -----------------------------------------------------------------------------
resource "oci_core_vcn" "main" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.0.0.0/16"]
  display_name   = "${var.app_name}-${var.environment}-vcn"
  dns_label      = "meddibuddy"

  freeform_tags = {
    "Project"     = var.app_name
    "Environment" = var.environment
  }
}

# -----------------------------------------------------------------------------
# Internet Gateway
# -----------------------------------------------------------------------------
resource "oci_core_internet_gateway" "igw" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${var.app_name}-${var.environment}-igw"
  enabled        = true

  freeform_tags = {
    "Project"     = var.app_name
    "Environment" = var.environment
  }
}

# -----------------------------------------------------------------------------
# Route Table (Encamina el tráfico de salida a Internet)
# -----------------------------------------------------------------------------
resource "oci_core_route_table" "public_rt" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${var.app_name}-${var.environment}-public-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.igw.id
  }

  freeform_tags = {
    "Project"     = var.app_name
    "Environment" = var.environment
  }
}

# -----------------------------------------------------------------------------
# Security List (Reglas de Firewall a nivel de VCN)
# -----------------------------------------------------------------------------
resource "oci_core_security_list" "public_sl" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${var.app_name}-${var.environment}-public-sl"

  # Salida libre a todo internet
  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
    stateless   = false
  }

  # Entrada SSH (Puerto 22)
  ingress_security_rules {
    protocol  = "6" # TCP
    source    = "0.0.0.0/0"
    stateless = false

    tcp_options {
      min = 22
      max = 22
    }
    description = "SSH Access"
  }

  # Entrada HTTP (Puerto 80)
  ingress_security_rules {
    protocol  = "6" # TCP
    source    = "0.0.0.0/0"
    stateless = false

    tcp_options {
      min = 80
      max = 80
    }
    description = "HTTP Traffic (Nginx / Certbot)"
  }

  # Entrada HTTPS (Puerto 443)
  ingress_security_rules {
    protocol  = "6" # TCP
    source    = "0.0.0.0/0"
    stateless = false

    tcp_options {
      min = 443
      max = 443
    }
    description = "HTTPS Traffic (Secure Sockets WSS)"
  }

  # Entrada API / WebSockets directa (Puerto 8080)
  ingress_security_rules {
    protocol  = "6" # TCP
    source    = "0.0.0.0/0"
    stateless = false

    tcp_options {
      min = 8080
      max = 8080
    }
    description = "Spring Boot API & WebSocket Port"
  }

  freeform_tags = {
    "Project"     = var.app_name
    "Environment" = var.environment
  }
}

# -----------------------------------------------------------------------------
# Regional Public Subnet
# -----------------------------------------------------------------------------
resource "oci_core_subnet" "public_subnet" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.main.id
  cidr_block                 = "10.0.1.0/24"
  display_name               = "${var.app_name}-${var.environment}-public-subnet"
  dns_label                  = "public"
  route_table_id             = oci_core_route_table.public_rt.id
  security_list_ids          = [oci_core_security_list.public_sl.id]
  prohibit_public_ip_on_vnic = false

  freeform_tags = {
    "Project"     = var.app_name
    "Environment" = var.environment
  }
}
