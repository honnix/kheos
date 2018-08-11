# kheos

[![Codacy Badge](https://api.codacy.com/project/badge/Grade/ad23a2e5b98945b9979fce95f5fa435f)](https://www.codacy.com/app/hxliang1982/kheos?utm_source=github.com&utm_medium=referral&utm_content=honnix/kheos&utm_campaign=badger)
[![CircleCI](https://circleci.com/gh/honnix/kheos/tree/master.svg?style=shield)](https://circleci.com/gh/honnix/kheos)
[![Coverage Status](https://codecov.io/gh/honnix/kheos/branch/master/graph/badge.svg)](https://codecov.io/gh/honnix/kheos)
[![Maven Central](https://img.shields.io/maven-central/v/io.honnix/kheos.svg)](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.honnix%22%20kheos)
[![License](https://img.shields.io/github/license/honnix/kheos.svg)](LICENSE)

A Kotlin implementation of [HEOS API Spec].

## Why

For unknown reason, HEOS decided to go with plain socket which is anything than
being modern (despite that many commands do return JSON!)

The goal of this project is to add an gRPC interface to make it easier interacting
with HEOS from other services. And of course we need common models, core lib, service
and client to make it complete.

## Usage

### Prerequisite

JDK8 and Maven.

### To build

```
$ git clone git@github.com:honnix/kheos.git
$ mvn package
```

This gives you a Docker image as bonus that you can ship directly, or you can use a pre-built
image [here](https://hub.docker.com/r/honnix/kheos-service/tags/).

### To start the service:

```
$ java -jar kheos-service/target/kheos-service.jar
```

Or

```
docker run -d honnix/kheos-service:<tag>
```

By default the service listens on 8888 and can be configured by env `GRPC_PORT`.

Use `KHEOS_HEOS_HOST` to configure where to find your HEOS.

For details and other configurations, refer to [application.conf](kheos-service/src/main/resources/application.conf).

### To start hacking

Import the maven project to IntelliJ (it works nicer with Kotlin, of course) and start sending PRs!

## kheos-proto

The place where all protobuf files are stored.

## kheos-common

This module contains mostly data classes and utilities.

## kheos-lib

This module contains core libraries that connect to HEOS, send heartbeat and commands,
and discover HEOS speakers.

Test cases are the best place to get a quick idea how to use the lib.

## kheos-service

This module exposes gRPC interface to interact with HEOS.

[HEOS API Spec]: http://www2.aerne.com/Public/dok-sw.nsf/0c6187bc750a16fcc1256e3c005a9740/9193bea412104506c1257dbd00298c78/$FILE/HEOS_CLI_ProtocolSpecification-Verion-1.3.pdf
