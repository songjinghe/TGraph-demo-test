#!/bin/bash

# This script pack the main functions of the project
# How to use this script:
# > source runTask.sh
# > truck_download 2017 6
# > ...

unset PREPARE
unset IS_COMPILED
unset PREPARE_TEST
unset PREPARE_OLD
unset PREPARE_TSC

export MAVEN_OPTS='-Xmx50g -Xms4g'
# export MAVEN_OPTS='-Xmx18g -Xms12g'
# # Debug options
#export MAVEN_OPTS='-Xmx18g -Xms10g -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005'

#======================================== environment variable ========================================

#the local storage of the TGraphDB
export DB_PATH="C:\tgraph\test-db\tgraph1d"
#the directory where the CSV files are stored
export RAW_DATA_PATH="E:\test-data"
#the start date of the date
export DATA_START=0501
#the end date of the date
export DATA_END=0501
#host
export DB_HOST=localhost
#the start time of the query operation
export TEMPORAL_DATA_START=201005011000
#the end time of the query operation
export TEMPORAL_DATA_END=201005011800
#the start time of creating index
export INDEX_TEMPORAL_DATA_START=201005010900
#the end time of creating index
export INDEX_TEMPORAL_DATA_END=201005011900
#the result file is saved in the file directory
export RESULT_DATA_PATH="E:\tgraph\test-result"
#index id of aggregation max
export INDEX_ID_OF_MAX=1
#index id of aggregation duration
export INDEX_ID_OF_DURATION=1
#index id of entity temporal condition test
export INDEX_ID_OF_CONDITION=1

export VERIFY_RESULT=false

#====================================================================================================


# Function: print system info of current machine (both hardware and software), no argument needed.
function systemInfo() {
    if [ -z ${IS_COMPILED+x} ]
    then
        IS_COMPILED=' clean compile '
    else
        IS_COMPILED=''
    fi
    mvn -B ${IS_COMPILED} exec:java \
        -Dexec.mainClass="edu.buaa.client.RuntimeEnv"
}

## Function: Start TGraph TCP Server which accept TCypher queries.
## Example: tcypherServerStart path-to-db-dir
## Explain: path-to-db-dir is a TGraph DB folder which contains traffic demo road network topology
#function runTCypherServer() {
#    mvn -B clean compile exec:java \
#        -Dexec.mainClass="simple.TCypherSocketServer" \3
#        -Dexec.classpathScope="test" \
#        -Dexec.args="$1"
#}

#======================================== server ON an OFF ========================================

#server without index
function runTGraphKernelServer(){
  mvn -B --offline compile exec:java -Dexec.mainClass="edu.buaa.server.TGraphKernelTcpServer"
}
#server with index
function runTGraphIndexedKernelServer(){
  mvn -B --offline compile exec:java -Dexec.mainClass="edu.buaa.server.TGraphIndexedKernelTcpServer"
}
#close server
function closeServer(){
  echo EXIT | nc localhost 8438
}

#====================================================================================================
#function runSQLServer(){
#  export DB_PATH=/tmp/testdb
#  mvn -B --offline compile exec:java -Dexec.mainClass="edu.buaa.server.TGraphKernelTcpServer"
#}
#
#function runNeo4jKernelServer(){
#  export DB_PATH="E:\tgraph\test-db"
#  export DB_TYPE=array
#  export DB_TYPE=treemap
#  mvn -B --offline compile exec:java -Dexec.mainClass="edu.buaa.server.Neo4jKernelTcpServer"
#}


#======================================== TESTS without INDEX ========================================

function runWriteTest() {
  export TEMPORAL_DATA_PER_TX=100
  export MAX_CONNECTION_CNT=1
  mvn -B --offline test -Dtest=simple.tgraph.kernel.WriteTemporalPropertyTest
}

function runSnapshotTest() {
  export TEST_PROPERTY_NAME=travel_time
  export MAX_CONNECTION_CNT=16
  export SERVER_RESULT_FILE="Result_SnapshotTest.gz"
  mvn -B --offline test -Dtest=simple.tgraph.kernel.SnapshotTest
}

function runSnapshotAggregationMaxTest() {
  export TEST_PROPERTY_NAME=travel_time
  export SERVER_RESULT_FILE="Result_SnapshotAggregationMaxTest.gz"
  export MAX_CONNECTION_CNT=16
  mvn -B --offline test -Dtest=simple.tgraph.kernel.SnapshotAggregationMaxTest
}

function runSnapshotAggregationDurationTest() {
  export TEST_PROPERTY_NAME=full_status
  export SERVER_RESULT_FILE="Result_SnapshotAggregationDurationTest.gz"
  export MAX_CONNECTION_CNT=16
  mvn -B --offline test -Dtest=simple.tgraph.kernel.SnapshotAggregationDurationTest
}

function runEntityTemporalConditionTest() {
  export TEST_PROPERTY_NAME=travel_time
  export TEMPORAL_CONDITION=600
  export SERVER_RESULT_FILE="Result_EntityTemporalConditionTest.gz"
  export MAX_CONNECTION_CNT=16
  mvn -B --offline test -Dtest=simple.tgraph.kernel.EntityTemporalConditionTest
}

function autoTest() {
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ SnapshotTest is Starting ]--------------- \033[0m"
  sleep 5
  runSnapshotTest
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ End of SnapshotTest ]--------------- \033[0m"
  sleep 60
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ SnapshotAggregationMaxTest is Starting ]--------------- \033[0m"
  sleep 5
  runSnapshotAggregationMaxTest
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ End of SnapshotTest is Starting ]--------------- \033[0m"
  sleep 60
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ SnapshotAggregationDurationTest is Starting ]--------------- \033[0m"
  sleep 5
  runSnapshotAggregationDurationTest
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ End of SnapshotTest is Starting ]--------------- \033[0m"
  sleep 60
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ EntityTemporalConditionTest is Starting ]--------------- \033[0m"
  sleep 5
  runEntityTemporalConditionTest
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ End of SnapshotTest is Starting ]--------------- \033[0m"
  sleep 60
  echo -e "\033[47;30m [Tgraph Test Info]---------------[ End of all tests, Server is closing ]--------------- \033[0m"
  sleep 5
  closeServer

  EOF
}


#function runReachableAreaQueryTest() {
#  export TEST_START_CROSS_ID=75124
#  export TEMPORAL_DATA_START=201006300830
#  export TRAVEL_TIME=50000
#  export DB_HOST=localhost
#  export RAW_DATA_PATH="E:\tgraph\test-result"
#  export SERVER_RESULT_FILE="Result_EntityTemporalConditionTest.gz"
#  export MAX_CONNECTION_CNT=16
#  export VERIFY_RESULT=false
#  mvn -B --offline test -Dtest=simple.tgraph.kernel.ReachableAreaQueryTest
#}

#========================================== TESTS with INDEX =========================================

function runCreateAggrMaxIndex() {
  export INDEX_PROPERTY_NAME=travel_time
  export SERVER_RESULT_FILE="ID_CreateAggrMaxIndexTest.gz"
  export MAX_CONNECTION_CNT=1
  mvn -B --offline test -Dtest=simple.tgraph.kernel.index.CreateTGraphAggrMaxIndexTest
}

function runSnapshotAggregationMaxIndexTest() {
  export SERVER_RESULT_FILE="Result_SnapshotAggregationMaxIndexTest.gz"
  export MAX_CONNECTION_CNT=1
  mvn -B --offline test -Dtest=simple.tgraph.kernel.index.SnapshotAggregationMaxIndexTest
}

function runCreateAggrDurationIndex {
  export SERVER_RESULT_FILE="ID_CreateAggrDurationIndexTest.gz"
  export MAX_CONNECTION_CNT=1
  mvn -B --offline test -Dtest=simple.tgraph.kernel.index.CreateTGraphAggrDurationIndexTest
}

function runSnapshotAggregationDurationIndexTest() {
  export TEST_PROPERTY_NAME=full_status
  export SERVER_RESULT_FILE="Result_SnapshotAggregationDurationIndexTest.gz"
  export MAX_CONNECTION_CNT=16
  mvn -B --offline test -Dtest=simple.tgraph.kernel.SnapshotAggregationDurationIndexTest
}

function runEntityTemporalConditionIndexTest() {
  export TEMPORAL_CONDITION=600
  export SERVER_RESULT_FILE="Result_EntityTemporalConditionTest.gz"
  export MAX_CONNECTION_CNT=16
  mvn -B --offline test -Dtest=simple.tgraph.kernel.EntityTemporalConditionTest
}

#function autoTestWithIndex() {
#
#  TXT_FILE_PATH="E:\tgraph\test-result"
#  SHELL_FILE_PATH="E:\TGraphDB\TGraph-demo-test"
#
#
#  runCreateAggrMaxIndex
#
#  closeServer
#
#  sleep 60
#
#  cd $TXT_FILE_PATH
#  firstline=`head -1 INDEX_ID_OF_MAX.txt`
#  export INDEX_ID_OF_MAX=$firstline
#  cd $SHELL_FILE_PATH
#
#  runSnapshotAggregationMaxIndexTest
#
#  sleep 60
#
#  runCreateAggrDurationIndex
#
#  closeServer
#
#  sleep 60
#
#  cd $TXT_FILE_PATH
#  firstline=`head -1 INDEX_ID_OF_DURATION.txt`
#  export INDEX_ID_OF_DURATION=$firstline
#  cd $SHELL_FILE_PATH
#
#  runSnapshotAggregationDurationIndexTest
#
#
#}


#function runReachableAreaQueryTest() {
#  export TEST_START_CROSS_ID=75124
#  export TEMPORAL_DATA_START=201006300830
#  export TRAVEL_TIME=50000
#  export DB_HOST=localhost
#  export RAW_DATA_PATH="E:\tgraph\test-result"
#  export SERVER_RESULT_FILE="Result_EntityTemporalConditionTest.gz"
#  export MAX_CONNECTION_CNT=16
#  export VERIFY_RESULT=false
#  mvn -B --offline test -Dtest=simple.tgraph.kernel.ReachableAreaQueryTest
#}

## Function: Test TGraph TCypher Server write performance.
## Example: tcypherClientWriteTest /media/song/test/db-network-only-ro 192.168.1.141 8 10 200000 /media/song/test/data-set/beijing-traffic/TGraph/byday/100501
## Explain:
##  /media/song/test/db-network-only-ro is a TGraph DB folder which contains traffic demo road network topology
##  192.168.1.141  is the TCypher Server hostname
##  8 is the number of connections to the server(both server and client use one thread to process one connection
##  10 is the number of Cypher queries per transaction
##  200000 is the total number of data lines to send.(from the data file)
##  /media/song/test/data-set/beijing-traffic/TGraph/byday/100501 is the path of the data file
#function tcypherClientWriteSpropTest() {
#    if [ -z ${IS_COMPILED+x} ]
#    then
#        IS_COMPILED=' clean test-compile '
#    else
#        IS_COMPILED=''
#    fi
#    mvn -B ${IS_COMPILED} exec:java \
#        -Dexec.mainClass="org.act.temporal.test.tcypher.WriteStaticPropertyTest" \
#        -Dexec.classpathScope="test" \
#        -Dexec.args="$1 $2 $3 $4 $5 $6"
#}
#
#
#
#function genBenchmark() {
#  export WORK_DIR="E:"
#  export BENCHMARK_FILE_OUTPUT=benchmark
#  export TEMPORAL_DATA_PER_TX=100
#  export TEMPORAL_DATA_START=0503
#  export TEMPORAL_DATA_END=0504
#  export REACHABLE_AREA_TX_CNT=20
#  mvn -B --offline compile exec:java -Dexec.mainClass="edu.buaa.benchmark.BenchmarkTxGenerator"
#}
#
#function genResult() {
#  export BENCHMARK_FILE_INPUT="E:\tgraph\test-data\benchmark.gz"
#  export BENCHMARK_FILE_OUTPUT="E:\tgraph\test-data\benchmark-with-result.gz"
#  export REACHABLE_AREA_REPLACE_TX=true
#  mvn -B --offline compile exec:java -Dexec.mainClass="edu.buaa.benchmark.BenchmarkTxResultGenerator"
#}
#
#function runBenchmark() {
#  export DB_TYPE=tgraph_kernel
#  #export DB_TYPE=sql_server
#  #export DB_HOST=39.96.57.88
#  export DB_HOST=localhost
#  export BENCHMARK_FILE_INPUT="E:\tgraph\test-data\benchmark-with-result.gz"
#  export MAX_CONNECTION_CNT=1
#  export VERIFY_RESULT=true
#  mvn -B --offline compile exec:java -Dexec.mainClass="edu.buaa.benchmark.BenchmarkRunner"
#}