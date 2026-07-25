#!/usr/bin/env bash
set -euo pipefail

echo "==> Atualizando sistema"
sudo apt-get update -y
sudo apt-get upgrade -y
sudo apt-get install -y ca-certificates curl gnupg git ufw

echo "==> Instalando Docker (ARM64 ok)"
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update -y
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"

echo "==> Firewall local (SSH + HTTP)"
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw --force enable || true

echo
echo "Docker instalado. Saia e entre de novo no SSH (ou: newgrp docker)."
echo "Depois clone o repo e suba o HookGuard — veja docs/ORACLE.md"
