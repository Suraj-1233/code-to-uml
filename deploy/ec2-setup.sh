#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# One-time EC2 setup for code-to-uml (Amazon Linux 2023).
# Installs Docker + Compose + swap, issues a Let's Encrypt cert, and
# launches the stack. After this, GitHub Actions auto-deploys on every push.
#
# Usage (on the EC2 box):
#   curl -sSL https://raw.githubusercontent.com/Suraj-1233/code-to-uml/main/deploy/ec2-setup.sh -o setup.sh
#   DOCKER_USERNAME=<your-dockerhub-user> bash setup.sh
#
# Prerequisites (do these FIRST, or cert issuance will fail):
#   • DNS A records: codetouml.in AND www.codetouml.in  →  this server's public IP
#   • Security Group inbound: open ports 80 and 443 (22 already open)
# ──────────────────────────────────────────────────────────────
set -euo pipefail

DOCKER_USERNAME="${DOCKER_USERNAME:-}"
DOMAIN="codetouml.in"
EMAIL="surajkannujiya517@gmail.com"
APP_DIR="/opt/code-to-uml"
COMPOSE_URL="https://raw.githubusercontent.com/Suraj-1233/code-to-uml/main/docker-compose.prod.yml"

if [ -z "$DOCKER_USERNAME" ]; then
  echo "❌ DOCKER_USERNAME is required. Run:  DOCKER_USERNAME=<your-dockerhub-user> bash setup.sh"
  exit 1
fi

echo "==> 1/6  Swap (2 GB)"
if ! sudo swapon --show | grep -q /swapfile; then
  sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "==> 2/6  Docker + Compose plugin"
sudo dnf install -y docker >/dev/null
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user || true
sudo mkdir -p /usr/local/lib/docker/cli-plugins
if [ ! -x /usr/local/lib/docker/cli-plugins/docker-compose ]; then
  sudo curl -sSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
  sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
fi

echo "==> 3/6  App directory + compose file + .env"
sudo mkdir -p "$APP_DIR" /var/www/certbot
sudo chown -R ec2-user:ec2-user "$APP_DIR"
curl -sSL "$COMPOSE_URL" -o "$APP_DIR/docker-compose.prod.yml"
echo "DOCKER_USERNAME=$DOCKER_USERNAME" > "$APP_DIR/.env"

echo "==> 4/6  TLS certificate (Let's Encrypt)"
PUBLIC_IP="$(curl -s https://checkip.amazonaws.com || true)"
DNS_IP="$(getent hosts "$DOMAIN" | awk '{print $1}' | head -1 || true)"
echo "    public IP: ${PUBLIC_IP:-unknown} | ${DOMAIN} resolves to: ${DNS_IP:-nothing}"
sudo docker stop code-to-uml-frontend 2>/dev/null || true   # free port 80 for standalone
if sudo docker run --rm -p 80:80 \
      -v /etc/letsencrypt:/etc/letsencrypt \
      -v /var/lib/letsencrypt:/var/lib/letsencrypt \
      certbot/certbot certonly --standalone \
      -d "$DOMAIN" -d "www.$DOMAIN" --non-interactive --agree-tos -m "$EMAIL"; then
  echo "    ✅ certificate issued"
else
  echo ""
  echo "    ⚠️  Cert issuance failed — likely DNS not pointing here yet, or ports 80/443 closed."
  echo "    Fix DNS A records (codetouml.in + www → ${PUBLIC_IP}) and the Security Group, then re-run this script."
  exit 1
fi

echo "==> 5/6  Launch the stack"
cd "$APP_DIR"
sudo docker compose -f docker-compose.prod.yml pull
sudo docker compose -f docker-compose.prod.yml up -d

echo "==> 6/6  Auto-renew timer (renews cert, briefly bounces the frontend)"
sudo tee /etc/systemd/system/certbot-renew.service >/dev/null <<'UNIT'
[Unit]
Description=Renew Let's Encrypt certificate for code-to-uml
[Service]
Type=oneshot
ExecStartPre=-/usr/bin/docker stop code-to-uml-frontend
ExecStart=/usr/bin/docker run --rm -p 80:80 -v /etc/letsencrypt:/etc/letsencrypt -v /var/lib/letsencrypt:/var/lib/letsencrypt certbot/certbot renew --quiet
ExecStartPost=-/usr/bin/docker start code-to-uml-frontend
UNIT
sudo tee /etc/systemd/system/certbot-renew.timer >/dev/null <<'UNIT'
[Unit]
Description=Twice-daily Let's Encrypt renewal check
[Timer]
OnCalendar=*-*-* 03,15:00:00
RandomizedDelaySec=1h
Persistent=true
[Install]
WantedBy=timers.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now certbot-renew.timer

echo ""
echo "✅ Done.  https://${DOMAIN} should now be live."
echo "   (Future pushes to main auto-deploy via GitHub Actions.)"
