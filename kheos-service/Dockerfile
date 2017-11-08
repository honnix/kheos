FROM openjdk:8
MAINTAINER Hongxin Liang <hxliang1982@gmail.com>

ADD target/lib                  /opt/kheos-service/lib
ADD target/kheos-service.jar    /opt/kheos-service/kheos-service.jar

ENTRYPOINT ["/usr/bin/java", "-jar", "/opt/kheos-service/kheos-service.jar"]
