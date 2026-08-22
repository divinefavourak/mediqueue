# MediQueue -- build and run image.
#
# Two stages: the first has a full JDK and compiles, the second keeps only a JRE and the
# finished jar. The result is roughly a third of the size of a single-stage image, and
# nothing that could compile arbitrary code ships to production.

# ---------------------------------------------------------------- build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# The driver is copied first and on its own. Docker caches each layer, so as long as
# lib/ is unchanged this layer is reused and only the source below is recompiled.
COPY lib/ lib/
COPY src/ src/
COPY package.sh .

RUN chmod +x package.sh && ./package.sh

# ------------------------------------------------------------------ run stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Runs as a non-root user. If the application is ever compromised, the attacker lands as
# a user who owns nothing rather than as root inside the container.
RUN useradd --system --create-home --uid 10001 mediqueue
USER mediqueue

COPY --from=build --chown=mediqueue:mediqueue /src/dist/mediqueue.jar ./mediqueue.jar

# Safe defaults for anything the operator forgets. Demo accounts share a password that is
# published in the README, so they stay off; the image is a production artifact.
ENV MEDIQUEUE_DEMO_SEED=false \
    MEDIQUEUE_SECURITY_COOKIE_SECURE=true \
    PORT=8080

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: containers get a memory limit at run time,
# and a hardcoded heap either wastes what it was given or gets the process OOM-killed.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -jar /app/mediqueue.jar"]
