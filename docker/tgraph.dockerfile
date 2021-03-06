FROM registry.cn-beijing.aliyuncs.com/songjinghe/tgraph-cache:latest
MAINTAINER Jinghe Song <songjh@act.buaa.edu.cn>

# ENV MAVEN_OPTS "-Xmx512m"

# build latest version
WORKDIR /tgraph/temporal-storage
RUN git pull
RUN mvn -B install -Dmaven.test.skip=true

WORKDIR /tgraph/temporal-neo4j
RUN git pull
RUN mvn -B install -Dmaven.test.skip=true -Dlicense.skip=true -Dlicensing.skip=true -pl org.neo4j:neo4j-cypher -am

WORKDIR /tgraph/TGraph-demo-test
RUN git pull
RUN mvn -B install -DskipTests

ENTRYPOINT /bin/bash