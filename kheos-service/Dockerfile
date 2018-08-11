FROM gcr.io/distroless/java:latest
MAINTAINER Hongxin Liang <hxliang1982@gmail.com>

ADD target/lib                  /opt/kheos-service/lib
ADD target/kheos-service.jar    /opt/kheos-service/kheos-service.jar

EXPOSE 8888/TCP

CMD ["/opt/kheos-service/kheos-service.jar"]
