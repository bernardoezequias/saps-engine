#!/bin/bash
#
# Start crawler application

if [[ $# -ne 2 ]]; then
  echo "Usage:" $0 "db-instance-ip db-port"
  exit 1
fi

#RECEIVED PARAM
DB_INSTANCE_IP=$1
DB_PORT=$2

#GLOBAL CONSTANTS
SANDBOX_DIR=/home/fogbow/sebal-engine
CONF_DIR=$SANDBOX_DIR/config
CONF_FILE_PATH=$CONF_DIR/sebal.conf
OUT_LOG_PATH=$SANDBOX_DIR/log
LIBRARY_PATH=/usr/local/lib

function main() {
  echo "Starting crawler app"
  sudo java -Djava.library.path=$LIBRARY_PATH -cp target/sebal-scheduler-0.0.1-SNAPSHOT.jar:target/lib/* org.fogbowcloud.sebal.engine.sebal.crawler.CrawlerMain $CONF_FILE_PATH $DB_INSTANCE_IP $DB_PORT > $OUT_LOG_PATH/crawler-app.out 2> $OUT_LOG_PATH/crawler-app.err &
}

main
