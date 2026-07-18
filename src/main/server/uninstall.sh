#!/bin/bash

# Keeloke TV Server uninstall script

# Check if the script is run as root
if [ "$EUID" -ne 0 ]; then
  echo "Please run this script as root or use sudo."
  exit 1
fi

read -p "This script will completely remove Keeloke TV Server all datas. Are you sure? (yes/no): " CONFIRM
if [[ "$CONFIRM" != "yes" ]]; then
  echo "Uninstallation canceled."
  exit 0
fi


# Define variables
AMS_DIR="/usr/local/antmedia"
SERVICE_FILE="/etc/systemd/system/antmedia.service"
LOG_DIR="/var/log/antmedia"

# Stop Keeloke TV Server service
echo "Stopping Keeloke TV Server service..."
systemctl stop antmedia

# Disable Keeloke TV Server service
echo "Disabling Keeloke TV Server service..."
systemctl disable antmedia

# Remove Keeloke TV Server files and directories
if [ -d "$AMS_DIR" ]; then
  echo "Removing Keeloke TV Server directory: $AMS_DIR"
  rm -rf "$AMS_DIR"
else
  echo "Keeloke TV Server directory not found: $AMS_DIR"
fi

# Remove systemd service file
if [ -f "$SERVICE_FILE" ]; then
  echo "Removing systemd service file: $SERVICE_FILE"
  rm -f "$SERVICE_FILE"
else
  echo "Systemd service file not found: $SERVICE_FILE"
fi

# Reload systemd
systemctl daemon-reload

# Remove logs
if [ -d "$LOG_DIR" ]; then
  echo "Removing log directory: $LOG_DIR"
  rm -rf "$LOG_DIR"
else
  echo "Log directory not found: $LOG_DIR"
fi

echo "Keeloke TV Server has been successfully uninstalled."

exit 0
