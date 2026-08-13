package ai.pipestream.email.server;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * grpc-email: a diskless gRPC collector for RFC 822 (.eml) and Outlook
 * (.msg) messages. Bytes arrive as a stream, parse entirely in memory, and
 * leave as typed events -- envelope first, then body parts, then
 * attachments, then a status trailer. Nothing is written to disk and no
 * external process is executed, ever, so the container runs --read-only.
 */
public final class GrpcEmailServer {

  private static final String SERVICE_NAME = "ai.pipestream.email.v1.EmailParseService";

  private GrpcEmailServer() {}

  public static void main(String[] args) throws Exception {
    final int port = intFromEnv("GRPC_EMAIL_PORT", 50054, 1, 65535);
    final long maxDocumentBytes =
        intFromEnv("GRPC_EMAIL_MAX_DOCUMENT_MIB", 64, 1, 1024) * 1024L * 1024L;
    final long maxAttachmentBytes =
        intFromEnv("GRPC_EMAIL_MAX_ATTACHMENT_MIB", 32, 1, 1024) * 1024L * 1024L;
    final int cores = Runtime.getRuntime().availableProcessors();
    final int maxConcurrent =
        intFromEnv("GRPC_EMAIL_MAX_CONCURRENT_PARSES", Math.max(2, cores), 1, 256);
    final int metricsInterval = intFromEnv("GRPC_EMAIL_METRICS_INTERVAL_SECONDS", 60, 0, 86400);

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    EmailParseServiceImpl service = new EmailParseServiceImpl(
        maxDocumentBytes, maxAttachmentBytes, maxConcurrent, executor);
    HealthStatusManager health = new HealthStatusManager();
    Server server =
        Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            // Allow a full-size message to arrive as a single chunk, plus framing.
            .maxInboundMessageSize((int) Math.min(Integer.MAX_VALUE, maxDocumentBytes + (1 << 20)))
            .addService(service)
            .addService(health.getHealthService())
            .addService(ProtoReflectionService.newInstance())
            .addService(ProtoReflectionServiceV1.newInstance())
            .build()
            .start();
    health.setStatus(SERVICE_NAME,
        io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
    System.out.println("grpc-email " + EmailParseServiceImpl.SERVICE_VERSION
        + " listening on 0.0.0.0:" + port + " (POI " + org.apache.poi.Version.getVersion()
        + ", max " + (maxDocumentBytes >> 20) + " MiB message / "
        + (maxAttachmentBytes >> 20) + " MiB attachment, " + maxConcurrent
        + " concurrent parses)");

    if (metricsInterval > 0) {
      Thread metrics = new Thread(() -> {
        while (true) {
          try {
            TimeUnit.SECONDS.sleep(metricsInterval);
          } catch (InterruptedException interrupted) {
            return;
          }
          System.out.println("grpc-email metrics: messages{parsed=" + service.parsed.get()
              + ",rejected=" + service.rejected.get() + ",failed=" + service.failed.get()
              + "} content{body_parts=" + service.bodyPartsEmitted.get()
              + ",attachments=" + service.attachmentsSeen.get()
              + ",bytes=" + service.bytesRead.get() + "}");
        }
      }, "grpc-email-metrics");
      metrics.setDaemon(true);
      metrics.start();
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      health.enterTerminalState();
      server.shutdown();
      try {
        if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
          server.shutdownNow();
        }
      } catch (InterruptedException interrupted) {
        server.shutdownNow();
      }
      executor.shutdown();
    }, "grpc-email-shutdown"));
    server.awaitTermination();
  }

  private static int intFromEnv(String name, int fallback, int min, int max) {
    String configured = System.getenv(name);
    if (configured == null || configured.isBlank()) {
      return fallback;
    }
    final int value;
    try {
      value = Integer.parseInt(configured.strip());
    } catch (NumberFormatException bad) {
      throw new IllegalArgumentException(name + " must be an integer, got: " + configured);
    }
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          name + " must be in [" + min + ", " + max + "], got: " + value);
    }
    return value;
  }
}
