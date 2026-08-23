# Build stage: compile, run the full test suite, and assemble the
# distribution. An image never ships from a tree whose tests did not pass.
#
# Both stages are Docker Hardened Images (dhi.io). The build stage is the
# -jdk-dev variant, the only one with a shell and a compiler; the runtime
# is the plain tag, which has no shell, no package manager and already runs
# as a non-root uid. Anything that once needed RUN in the runtime stage
# (like useradd) is gone.
FROM dhi.io/eclipse-temurin:25-jdk-dev AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon build :email-service:installDist

# Runtime: non-root by default, nothing writable needed. The server is
# diskless by doctrine; run with --read-only and it works unchanged.
FROM dhi.io/eclipse-temurin:25
COPY --from=build /src/email-service/build/install/email-service /opt/grpc-email
EXPOSE 50054
# No shell in the runtime image, so the installDist launcher script (a
# /bin/sh script) cannot run. Invoke java on the lib/ classpath instead;
# the JVM expands the /* wildcard itself.
ENTRYPOINT ["java", "-cp", "/opt/grpc-email/lib/*", "ai.pipestream.email.server.GrpcEmailServer"]
