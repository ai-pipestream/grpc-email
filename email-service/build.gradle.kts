plugins {
    application
}

dependencies {
    implementation(project(":email-api"))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)
    implementation(libs.angus.mail)
    implementation(libs.poi)
    implementation(libs.poi.scratchpad)
    runtimeOnly(libs.log4j.core)

    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass = "ai.pipestream.email.server.GrpcEmailServer"
}
