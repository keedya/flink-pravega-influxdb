#!/usr/bin/env bash
# Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.

# Build the installer archive, including the Java JAR file and all dependencies.

: ${LOCAL_DOCKER_REGISRY?"You must export LOCAL_DOCKER_REGISRY"}
set -ex
ROOT_DIR=$(readlink -f $(dirname $0)/..)
source ${ROOT_DIR}/scripts/env.sh
INSTALLER_BUILD_DIR=${ROOT_DIR}/build/installer/${APP_NAME}
INSTALLER_TGZ=${ROOT_DIR}/build/installer/${APP_NAME}-${APP_VERSION}.tgz

# Delete output directories and files.
rm -rf ${INSTALLER_BUILD_DIR} ${INSTALLER_TGZ}
mkdir -p ${INSTALLER_BUILD_DIR}
mkdir -p ${INSTALLER_BUILD_DIR}/docker_images

# tag and copy docker images
docker pull ${LOCAL_DOCKER_REGISRY}/influxdb:1.8.0-alpine
docker tag ${LOCAL_DOCKER_REGISRY}/influxdb:1.8.0-alpine influxdb:1.8.0-alpine

docker pull ${LOCAL_DOCKER_REGISRY}/curl:latest
docker tag ${LOCAL_DOCKER_REGISRY}/curl:latest curl:latest
pushd ${INSTALLER_BUILD_DIR}/docker_images
docker save influxdb:1.8.0-alpine curl:latest | gzip > influx.tar.gz
popd


# Build Flink application JAR.
pushd ${ROOT_DIR}/
./gradlew shadowJar ${GRADLE_OPTIONS}
popd
# Download and extract Gradle.
# Gradle will be included in the installer archive.
GRADLE_VERSION=6.3
GRADLE_FILE=${ROOT_DIR}/build/installer/gradle-${GRADLE_VERSION}-bin.zip
[ -f ${GRADLE_FILE} ] || wget --no-verbose -O ${GRADLE_FILE} https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip
unzip -q -d ${INSTALLER_BUILD_DIR} ${GRADLE_FILE}
mv -v ${INSTALLER_BUILD_DIR}/gradle-${GRADLE_VERSION} ${INSTALLER_BUILD_DIR}/gradle

# Copy Flink application JAR.
mkdir -p ${INSTALLER_BUILD_DIR}/flinkprocessor/build/libs
cp -v \
 ${ROOT_DIR}/flinkprocessor/build/libs/flinkprocessor-${APP_VERSION}.jar\
 ${INSTALLER_BUILD_DIR}/flinkprocessor/build/libs/
# Copy other files required for an offline install.
cp -rv \
  ${ROOT_DIR}/charts \
  ${ROOT_DIR}/installer \
  ${ROOT_DIR}/dashboards \
  ${ROOT_DIR}/scripts \
  ${ROOT_DIR}/values \
  ${ROOT_DIR}/LICENSE \
  ${ROOT_DIR}/README.md \
  ${INSTALLER_BUILD_DIR}/

rm -f -v ${INSTALLER_BUILD_DIR}/scripts/*-local.sh

# Create installer archive.
GZIP="--rsyncable" tar -C ${INSTALLER_BUILD_DIR}/.. -czf ${INSTALLER_TGZ} ${APP_NAME}

ls -lh ${INSTALLER_TGZ}