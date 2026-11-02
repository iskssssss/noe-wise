#!/bin/sh
set -eu

umask 077
if [ ! -s /ca/ca.key ] || [ ! -s /ca/ca.crt ]; then
  openssl req -new -x509 -nodes -newkey rsa:3072 -sha256 -days 3650 \
    -subj '/CN=SkillForge Sandbox Egress CA/O=SkillForge' \
    -keyout /ca/ca.key \
    -out /ca/ca.crt
fi
if [ ! -s /ca/mitmproxy-dhparam.pem ]; then
  openssl dhparam -dsaparam -out /ca/mitmproxy-dhparam.pem 2048
fi
mkdir -p /ca/public
cat /ca/ca.key /ca/ca.crt > /ca/mitmproxy-ca.pem
cp /ca/ca.crt /ca/mitmproxy-ca-cert.pem
cp /ca/ca.crt /ca/public/ca.crt
chmod 0600 /ca/ca.key /ca/mitmproxy-ca.pem
chmod 0644 /ca/ca.crt /ca/mitmproxy-ca-cert.pem /ca/mitmproxy-dhparam.pem /ca/public/ca.crt
chown -R 65532:65532 /ca
