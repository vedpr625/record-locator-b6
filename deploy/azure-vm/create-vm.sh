#!/usr/bin/env bash
# Run on YOUR machine (needs Azure CLI: az login first). Creates a small VM and opens
# port 8080 ONLY to your own IP. Edit the variables, then: bash create-vm.sh
set -euo pipefail

RG=rg-record-locator-demo
LOCATION=southindia            # pick a region near your users
VM=vm-record-locator-demo
SIZE=Standard_B2s              # 2 vCPU / 4 GB, enough for the demo
ADMIN=azureuser
MY_IP="${MY_IP:-$(curl -s https://api.ipify.org)}"   # who may reach port 8080

az group create -n "$RG" -l "$LOCATION"
az vm create -g "$RG" -n "$VM" --image Ubuntu2404 --size "$SIZE" \
  --admin-username "$ADMIN" --generate-ssh-keys --public-ip-sku Standard
# SSH (22) is opened by az vm create; restrict it too:
NSG="${VM}NSG"
az network nsg rule update -g "$RG" --nsg-name "$NSG" -n default-allow-ssh --source-address-prefixes "$MY_IP/32" || true
az network nsg rule create -g "$RG" --nsg-name "$NSG" -n allow-record-locator \
  --priority 1010 --access Allow --protocol Tcp --direction Inbound \
  --destination-port-ranges 8080 --source-address-prefixes "$MY_IP/32"

IP=$(az vm show -d -g "$RG" -n "$VM" --query publicIps -o tsv)
echo "VM public IP: $IP   (port 8080 open only to $MY_IP)"
echo "Next:"
echo "  (cd ../.. && mvn -DskipTests package)"
echo "  scp ../../target/record-locator-2.0.0.jar $ADMIN@$IP:~/record-locator.jar"
echo "  scp record-locator-demo.env record-locator-demo.service setup-demo.sh $ADMIN@$IP:~/"
echo "  ssh $ADMIN@$IP 'sudo bash setup-demo.sh'"
