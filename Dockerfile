# Build stage: compile, run the full test suite, and assemble the
# distribution. An image never ships from a tree whose tests did not pass.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon build :email-service:installDist

# Runtime: JRE only, non-root, nothing writable needed. The server is
# diskless by doctrine; run with --read-only and it works unchanged.
FROM eclipse-temurin:25-jre
COPY --from=build /src/email-service/build/install/email-service /opt/grpc-email
RUN useradd --system --no-create-home grpcemail
USER grpcemail
EXPOSE 50054
ENTRYPOINT ["/opt/grpc-email/bin/email-service"]
