#!/bin/bash

set -ex

mvn -s ~/.m2/settings.xml.honnix release:prepare -DpushChanges=false

mvn -s ~/.m2/settings.xml.honnix release:perform -DlocalCheckout=true \
  -Darguments="-DskipTests -Ddockerfile.skip=true"

git push origin HEAD
git push --tags
