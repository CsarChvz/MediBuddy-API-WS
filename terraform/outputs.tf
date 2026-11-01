output "instance_public_ip" {
  description = "Dirección IP pública de la instancia en OCI"
  value       = oci_core_instance.api_server.public_ip
}

output "instance_private_ip" {
  description = "Dirección IP privada de la instancia dentro de la VCN"
  value       = oci_core_instance.api_server.private_ip
}

output "ssh_connection_command" {
  description = "Comando para conectarse a la máquina por SSH"
  value       = "ssh ubuntu@${oci_core_instance.api_server.public_ip}"
}

output "api_url_http" {
  description = "URL base directa al puerto de la API"
  value       = "http://${oci_core_instance.api_server.public_ip}:8080"
}

output "websocket_url" {
  description = "URL base de conexión para WebSockets (ESP32 / Android)"
  value       = "ws://${oci_core_instance.api_server.public_ip}:8080/ws"
}
