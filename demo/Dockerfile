# Builds the demo as a native executable and packages it in an image from scratch. Build from the repository root:
#
#   docker build -f demo/Dockerfile -t tits-demo .

# GraalVM Community 25 (multi-arch index digest). No GraalVM release supports Java 27 yet, so the build targets 25.
FROM ghcr.io/graalvm/native-image-community:25i5-25.0.5.0.3-ol10@sha256:2800b5bd11cbb9bf11c92591d05ea37d9a735522bdc0e86827302d998843ad79 AS build

ARG MAVEN_VERSION=3.10.0
ARG MAVEN_SHA512=908b1501bfb420bf7c8affb855534a9c407fd6099367bfb9f2f2dcb8e9799102bffb84518cde74c679bd76870247c6528683abdd620581bffa90f95d92d175aa
RUN curl -fsSL -o /tmp/maven.tar.gz https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
 && echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c - \
 && tar -xzf /tmp/maven.tar.gz -C /opt \
 && rm /tmp/maven.tar.gz
ENV PATH=/opt/apache-maven-${MAVEN_VERSION}/bin:$PATH

WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -pl demo -am -DskipTests -Dmaven.compiler.release=25 \
      -Daether.artifactResolver.postProcessor.trustedChecksums.failIfMissing=true

# NATIVE_IMAGE_OPTS passes extra options to native-image. CI sets it to -Ob (quick build mode), which builds faster
# but optimizes less, as it only checks that the image runs.
ARG NATIVE_IMAGE_OPTS=

# --static-nolibc links everything but glibc statically, which works on both amd64 and arm64 (fully static musl
# builds are only available for amd64). glibc and the dynamic loader are therefore copied into the image below.
# Native executables run their shutdown hooks on SIGTERM, also when running as PID 1.
RUN mkdir -p /rootfs \
 && native-image \
      ${NATIVE_IMAGE_OPTS} \
      --static-nolibc \
      -march=compatibility \
      -o /rootfs/demo \
      -jar demo/target/demo.jar \
 && ldd /rootfs/demo | grep -o '/[^ ]*' | xargs -I{} cp --parents -L {} /rootfs \
 && mkdir -m 1777 /rootfs/tmp

FROM scratch
COPY --from=build /rootfs /
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/demo"]
