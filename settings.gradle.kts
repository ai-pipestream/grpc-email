rootProject.name = "grpc-email"

include("email-api")
include("email-service")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
