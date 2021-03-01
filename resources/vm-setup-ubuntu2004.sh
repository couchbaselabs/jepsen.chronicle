#!/bin/bash

echo "Running vm-setup script"

apt-get update
apt-get install -y gcc g++
snap install --classic erlang

echo "AAAAAAAAAAAAAAAAAAAA" > /home/vagrant/.erlang.cookie
chown vagrant:vagrant /home/vagrant/.erlang.cookie
chmod 400 /home/vagrant/.erlang.cookie

# Force git to use https over ssh
sudo -u vagrant git config --global url."https://github.com/".insteadOf git@github.com:
