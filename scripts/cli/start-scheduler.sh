#!/bin/bash
#
# Start scheduler application

if [[ $# -ne 4 ]]; then
  echo "Usage:" $0 "scheduler-instance-ip db-port nfs-instance-ip nfs-port"
  exit 1
fi

#RECEIVED PARAM
SCHEDULER_INSTANCE_IP=$1
DB_PORT=$2
NFS_INSTANCE_IP=$3
NFS_PORT=$4

#GLOBAL CONSTANTS
SANDBOX_DIR=/home/fogbow/sebal-engine
SPEC_FILE=$SANDBOX_DIR/config/initialSpec
CONF_DIR=$SANDBOX_DIR/config
CONF_FILE_PATH=$CONF_DIR/sebal.conf
OUT_LOG_PATH=$SANDBOX_DIR/log
LIBRARY_PATH=/usr/local/lib

function main() {
  echo "Starting scheduler app"
  sudo java -Djava.library.path=$LIBRARY_PATH -cp target/sebal-scheduler-0.0.1-SNAPSHOT.jar:target/lib/* org.fogbowcloud.sebal.engine.SebalMain $CONF_FILE_PATH $SCHEDULER_INSTANCE_IP $DB_PORT $NFS_INSTANCE_IP $NFS_PORT > $OUT_LOG_PATH/scheduler-app.out 2> $OUT_LOG_PATH/scheduler-app.err &
}

main
