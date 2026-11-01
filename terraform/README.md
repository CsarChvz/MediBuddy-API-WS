# Infraestructura Terraform para MeddiBudy API (Oracle Cloud Always Free)

Esta configuración aprovisiona una infraestructura completa y **100% gratuita de por vida (Always Free)** en Oracle Cloud Infrastructure (OCI) para desplegar el backend y servidor de WebSockets de MeddiBudy.

---

## 🏗️ Recursos Creados

1. **Virtual Cloud Network (VCN):** Red privada virtual dedicada (`10.0.0.0/16`).
2. **Internet Gateway & Route Table:** Conexión pública a internet.
3. **Security List:**
   * Puerto `22` (SSH).
   * Puerto `80` (HTTP).
   * Puerto `443` (HTTPS / WSS).
   * Puerto `8080` (API REST y WebSockets de Spring Boot).
4. **Subred Pública Regional:** `10.0.1.0/24`.
5. **Instancia de Cómputo ARM (Ampere A1 Flex):**
   * **2 OCPUs** y **8 GB de RAM** (dentro del límite gratuito de 4 OCPUs y 24 GB de RAM).
   * Sistema Operativo: **Ubuntu 22.04 LTS (aarch64)**.
   * Volumen de arranque: **50 GB** (límite gratuito hasta 200 GB).
   * **Cloud-Init:** Auto-instalación de Docker, Docker Compose, ajuste de iptables y permisos para el usuario `ubuntu`.

---

## 📋 Requisitos Previos

1. Cuenta activa en [Oracle Cloud](https://cloud.oracle.com/).
2. [Terraform CLI](https://developer.hashicorp.com/terraform/install) instalado.
3. Tu par de llaves SSH local (`~/.ssh/id_rsa.pub` o `~/.ssh/id_ed25519.pub`).

---

## 🔑 Paso 1: Generar API Key en Oracle Cloud

Para que Terraform se comunique con tu cuenta de OCI:

1. Entra a la consola web de OCI.
2. Ve a la esquina superior derecha (ícono de perfil) -> **My Profile** (o **User Settings**).
3. En el menú lateral izquierdo, selecciona **API Keys**.
4. Haz clic en **Add API Key**:
   * Selecciona **Generate API Key Pair**.
   * Descarga la llave privada (`.pem`) y guárdala localmente (por ejemplo en `~/.oci/oci_api_key.pem`).
   * Asigna permisos de lectura solo a tu usuario:
     ```bash
     chmod 600 ~/.oci/oci_api_key.pem
     ```
   * Haz clic en **Add**.
5. Se mostrará una ventana con los valores de configuración. Copia:
   * `tenancy` (tenancy_ocid)
   * `user` (user_ocid)
   * `fingerprint`
   * `region`

---

## ⚙️ Paso 2: Configurar las variables en Terraform

1. Entra al directorio `terraform/`:
   ```bash
   cd terraform
   ```
2. Copia el archivo de ejemplo:
   ```bash
   cp terraform.tfvars.example terraform.tfvars
   ```
3. Edita `terraform.tfvars` con tus credenciales y tu llave pública SSH:
   ```hcl
   tenancy_ocid     = "ocid1.tenancy.oc1..aaaaaaaaxxx..."
   user_ocid        = "ocid1.user.oc1..aaaaaaaaxxx..."
   fingerprint      = "xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx:xx"
   private_key_path = "~/.oci/oci_api_key.pem"
   region           = "us-ashburn-1"
   compartment_ocid = "ocid1.tenancy.oc1..aaaaaaaaxxx..."

   ssh_public_key   = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI..."
   ```

---

## 🚀 Paso 3: Inicializar y Desplegar

```bash
# 1. Inicializar el provider de OCI
terraform init

# 2. Revisar el plan de ejecución
terraform plan

# 3. Aplicar los cambios
terraform apply
```

Al terminar, Terraform imprimirá en la terminal:
* La **IP pública** del servidor.
* El comando exacto para conectarte por SSH (`ssh ubuntu@<IP>`).
* Las URLs de la API y de los WebSockets.

---

## 🐳 Paso 4: Desplegar el Contenedor de la API

Una vez aprovisionado el servidor:

1. Conéctate por SSH:
   ```bash
   ssh ubuntu@<IP_PUBLICA>
   ```
2. Clona el repositorio en el servidor (o transfiere la imagen Docker desde Docker Hub/GHCR):
   ```bash
   git clone https://github.com/CsarChvz/MediBuddy-API-WS.git
   cd MediBuddy-API-WS
   ```
3. Ejecuta el contenedor conectándolo a tu base de datos externa (ej. Neon / Supabase):
   ```bash
   docker build -t meddibuddy-api .

   docker run -d \
     --name meddibuddy-api \
     --restart always \
     -p 8080:8080 \
     -e SPRING_DATASOURCE_URL="jdbc:postgresql://<HOST_EXTERNO>:5432/<DB_NAME>?sslmode=require" \
     -e SPRING_DATASOURCE_USERNAME="<USUARIO_DB>" \
     -e SPRING_DATASOURCE_PASSWORD="<PASSWORD_DB>" \
     meddibuddy-api
   ```

4. Prueba tus WebSockets apuntando a:
   ```text
   ws://<IP_PUBLICA>:8080/ws
   ```
