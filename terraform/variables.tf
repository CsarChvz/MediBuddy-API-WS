# -----------------------------------------------------------------------------
# OCI Authentication & Tenancy
# -----------------------------------------------------------------------------
variable "tenancy_ocid" {
  description = "OCID del tenancy de Oracle Cloud"
  type        = string
}

variable "user_ocid" {
  description = "OCID del usuario de Oracle Cloud con permisos para crear recursos"
  type        = string
}

variable "fingerprint" {
  description = "Fingerprint de la llave pública API de OCI"
  type        = string
}

variable "private_key_path" {
  description = "Ruta absoluta al archivo .pem de la llave privada de OCI"
  type        = string
}

variable "region" {
  description = "Región de OCI donde se desplegarán los recursos (ej. us-ashburn-1, us-phoenix-1, sa-saopaulo-1)"
  type        = string
  default     = "us-ashburn-1"
}

variable "compartment_ocid" {
  description = "OCID del compartment donde se crearán los recursos (generalmente el tenancy_ocid en cuentas personales)"
  type        = string
}

# -----------------------------------------------------------------------------
# Compute & Instance Config (OCI Always Free: Ampere A1 Flex)
# -----------------------------------------------------------------------------
variable "app_name" {
  description = "Nombre base para los recursos del proyecto"
  type        = string
  default     = "meddibuddy-api"
}

variable "environment" {
  description = "Ambiente de despliegue (prod, dev, staging)"
  type        = string
  default     = "prod"
}

variable "instance_shape" {
  description = "Shape de cómputo en OCI. VM.Standard.A1.Flex forma parte del nivel Always Free"
  type        = string
  default     = "VM.Standard.A1.Flex"
}

variable "instance_ocpus" {
  description = "Número de OCPUs para la instancia ARM. (Nivel gratuito permite hasta 4 OCPUs en total)"
  type        = number
  default     = 2
}

variable "instance_memory_in_gbs" {
  description = "Cantidad de memoria RAM en GB. (Nivel gratuito permite hasta 24 GB en total)"
  type        = number
  default     = 8
}

variable "boot_volume_size_in_gbs" {
  description = "Tamaño del volumen de arranque en GB. (Nivel gratuito incluye hasta 200 GB de almacenamiento)"
  type        = number
  default     = 50
}

variable "ssh_public_key" {
  description = "Contenido de la llave pública SSH para acceder a la instancia (ej. ssh-rsa AAAA...)"
  type        = string
}

variable "availability_domain_index" {
  description = "Índice del Availability Domain a utilizar (0 para AD-1)"
  type        = number
  default     = 0
}
