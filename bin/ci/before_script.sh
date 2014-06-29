#!/bin/bash

cd bin/ci
wget http://apache.osuosl.org/zookeeper/zookeeper-3.4.6/zookeeper-3.4.6.tar.gz
tar xf zookeeper-3.4.6.tar.gz
cp zoo.cfg zookeeper-3.4.6/conf/
cd zookeeper-3.4.6
mkdir data
sudo ./bin/zkServer.sh start
sleep 5
